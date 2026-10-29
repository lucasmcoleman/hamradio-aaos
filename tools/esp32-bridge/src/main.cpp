// ESP32 classic-Bluetooth bridge for the DB50-B (job #1140).
//
// The head unit can't finish a classic Bluetooth pair with the radio, so this board
// pairs instead and relays the radio's RFCOMM channels as plain TCP:
//   port 8701  control (GAIA-framed Benshi, bytes passed through untouched)
//   port 8702  audio
// One TCP client per port. The app keeps all protocol logic; the bridge only moves bytes.
//
// USB serial console (115200) for bench work and setup:
//   status | scan | connect | disconnect | mac AA:BB:CC:DD:EE:FF
//   scn <ctl> <audio>        pick RFCOMM channels (0 = auto from discovery)
//   wifi <ssid> <password>   join a network (stored, never printed)
//   ap                       forget the network and run as access point
//   tx <hex>                 send bytes on the control channel; replies print as "rx ctl <hex>"
//   otapass                  show the password for Wi-Fi firmware updates
//
// Over Wi-Fi, for when the board is in the truck:
//   port 8703  console log (recent backlog, then live); accepts status scan connect
//              disconnect echo tx. Setup commands stay USB-only.
//   port 8080  POST /update with the firmware .bin (basic auth "bridge" / otapass)
#include <Arduino.h>
#include <Preferences.h>
#include <Update.h>
#include <WebServer.h>
#include <WiFi.h>
#include <esp_bt.h>
#include <esp_bt_main.h>
#include <esp_bt_device.h>
#include <esp_gap_bt_api.h>
#include <esp_spp_api.h>
#include <freertos/stream_buffer.h>
#include <lwip/sockets.h>

#define SPP_NAME_MAX 32

static const char *AP_SSID = "hamradio-bridge";
static const uint16_t PORTS[2] = {8701, 8702};
enum { CTL = 0, AUD = 1 };
static const char *NAMES[2] = {"ctl", "aud"};

Preferences prefs;
String radioMac = "38:D2:00:01:68:A8";
uint8_t wantScn[2] = {0, 0};   // 0 = choose from discovery

esp_bd_addr_t peer;
volatile uint32_t handle[2] = {0, 0};
volatile bool congested[2] = {false, false};
volatile bool connecting = false;
uint8_t foundScn[ESP_SPP_MAX_SCN];
char foundName[ESP_SPP_MAX_SCN][SPP_NAME_MAX + 1];
volatile int foundCount = -1;   // -1 = no discovery yet
StreamBufferHandle_t fromRadio[2];
WiFiServer *server[2];
WiFiClient client[2];
bool echoCtl = true;            // print control traffic on the console
uint32_t lastAttempt = 0;
uint32_t connectStart = 0;      // when the pending discovery or channel open began
static const uint32_t CONNECT_TIMEOUT_MS = 15000;  // the radio never answers an open on a channel it isn't serving
// Audio was on RFCOMM 2 in every capture, whichever channel control was on; 3 also opens but stays silent.
static const uint8_t AUD_CANDIDATES[] = {2, 3};
uint8_t audTries = 0;
uint32_t audDropped = 0;             // audio bytes dropped because the TCP client fell behind
uint32_t audStallSince = 0;          // when the audio client stopped taking data, 0 if it is keeping up

// Pairing knobs, settable on the console while finding what the radio accepts.
static const esp_spp_sec_t SEC_MASKS[3] = {ESP_SPP_SEC_NONE, ESP_SPP_SEC_AUTHENTICATE,
                                           ESP_SPP_SEC_AUTHENTICATE | ESP_SPP_SEC_ENCRYPT};
static const char *SEC_NAMES[3] = {"none", "auth", "encrypt"};
static const esp_bt_io_cap_t IOCAPS[4] = {ESP_BT_IO_CAP_NONE, ESP_BT_IO_CAP_IO, ESP_BT_IO_CAP_OUT, ESP_BT_IO_CAP_IN};
static const char *IOCAP_NAMES[4] = {"none", "yesno", "display", "keyboard"};
uint8_t secLevel = 1, ioCap = 0;
String pinCode = "0000";

static int indexOf(const char *const *names, int n, const String &s) {
  for (int i = 0; i < n; i++) if (s == names[i]) return i;
  return -1;
}

static void applyIoCap() {
  esp_bt_sp_param_t param = ESP_BT_SP_IOCAP_MODE;
  esp_bt_io_cap_t cap = IOCAPS[ioCap];
  esp_bt_gap_set_security_param(param, &cap, sizeof(cap));
}

// Console output goes to USB serial and to a ring the Wi-Fi log port replays. Bluetooth
// callbacks print from their own task, so ring writes are locked.
static const uint16_t LOG_PORT = 8703;
static const size_t RING = 8192;
static char ring[RING];
static uint32_t ringW = 0, ringR = 0;  // total bytes written; next byte the log client gets
static SemaphoreHandle_t ringLock;
WiFiServer logServer(LOG_PORT);
WiFiClient logClient;
WebServer web(8080);

class Tee : public Print {
 public:
  size_t write(uint8_t b) override { return write(&b, 1); }
  size_t write(const uint8_t *d, size_t n) override {
    Serial.write(d, n);
    if (ringLock && xSemaphoreTake(ringLock, pdMS_TO_TICKS(20))) {
      for (size_t i = 0; i < n; i++) ring[(ringW + i) % RING] = d[i];
      ringW += n;
      xSemaphoreGive(ringLock);
    }
    return n;
  }
} out;

static void drainLog() {
  if (!logClient || !logClient.connected()) return;
  char chunk[512];
  size_t n = 0;
  if (xSemaphoreTake(ringLock, pdMS_TO_TICKS(20))) {
    if (ringW - ringR > RING) ringR = ringW - RING;  // client fell behind: skip to the oldest kept byte
    while (ringR < ringW && n < sizeof(chunk)) chunk[n++] = ring[ringR++ % RING];
    xSemaphoreGive(ringLock);
  }
  if (n) logClient.write((const uint8_t *)chunk, n);
}

static bool parseMac(const String &s, esp_bd_addr_t out) {
  int v[6];
  if (sscanf(s.c_str(), "%x:%x:%x:%x:%x:%x", &v[0], &v[1], &v[2], &v[3], &v[4], &v[5]) != 6) return false;
  for (int i = 0; i < 6; i++) out[i] = v[i];
  return true;
}

static void printHex(const char *tag, const uint8_t *d, size_t n) {
  out.printf("%s ", tag);
  for (size_t i = 0; i < n; i++) out.printf("%02X", d[i]);
  out.println();
}

static int channelForHandle(uint32_t h) {
  for (int c = 0; c < 2; c++) if (handle[c] == h) return c;
  return -1;
}

// Control is the first channel unless one is set; audio is whichever channel's name mentions audio.
static uint8_t chooseScn(int c) {
  if (wantScn[c]) return wantScn[c];
  if (foundCount <= 0) return 0;
  if (c == CTL) {
    for (int i = 0; i < foundCount; i++) {
      String n = String(foundName[i]); n.toLowerCase();
      if (n.indexOf("audio") < 0) return foundScn[i];
    }
    return foundScn[0];
  }
  for (int i = 0; i < foundCount; i++) {
    String n = String(foundName[i]); n.toLowerCase();
    if (n.indexOf("audio") >= 0) return foundScn[i];
  }
  // SPP discovery doesn't list the radio's audio service, and the DB50-B moves its channels
  // between connections, so try the channels its audio has been seen on, one per attempt.
  if (audTries >= sizeof(AUD_CANDIDATES)) return 0;
  return AUD_CANDIDATES[audTries];
}

static void gapCb(esp_bt_gap_cb_event_t event, esp_bt_gap_cb_param_t *p) {
  switch (event) {
    case ESP_BT_GAP_AUTH_CMPL_EVT:
      out.printf("bt auth %s (status %d)\n", p->auth_cmpl.stat == ESP_BT_STATUS_SUCCESS ? "ok" : "FAILED", p->auth_cmpl.stat);
      break;
    case ESP_BT_GAP_PIN_REQ_EVT: {  // legacy pairing fallback
      esp_bt_pin_code_t code;
      uint8_t len = min((size_t)ESP_BT_PIN_CODE_LEN, (size_t)pinCode.length());
      memcpy(code, pinCode.c_str(), len);
      esp_bt_gap_pin_reply(p->pin_req.bda, true, len, code);
      out.printf("bt pin request (min 16 digits: %d) answered with %s\n", p->pin_req.min_16_digit, pinCode.c_str());
      break;
    }
    case ESP_BT_GAP_CFM_REQ_EVT:     // SSP numeric comparison: no screen here, so accept
      esp_bt_gap_ssp_confirm_reply(p->cfm_req.bda, true);
      out.printf("bt ssp confirm %06lu accepted\n", (unsigned long)p->cfm_req.num_val);
      break;
    case ESP_BT_GAP_KEY_NOTIF_EVT:
      out.printf("bt ssp passkey shown %06lu\n", (unsigned long)p->key_notif.passkey);
      break;
    case ESP_BT_GAP_KEY_REQ_EVT:     // radio wants a passkey typed here: send the PIN as a number
      esp_bt_gap_ssp_passkey_reply(p->key_req.bda, true, pinCode.toInt());
      out.printf("bt ssp passkey request answered with %s\n", pinCode.c_str());
      break;
    case ESP_BT_GAP_MODE_CHG_EVT:
      break;
    default:
      out.printf("bt gap event %d\n", event);
      break;
  }
}

static void connectNext() {
  for (int c = 0; c < 2; c++) {
    if (handle[c]) continue;
    uint8_t scn = chooseScn(c);
    if (!scn) continue;
    if (c == AUD && !handle[CTL]) return;  // control first
    connecting = true;
    connectStart = millis();
    out.printf("bt connect %s scn %d\n", NAMES[c], scn);
    esp_spp_connect(SEC_MASKS[secLevel], ESP_SPP_ROLE_MASTER, scn, peer);
    return;
  }
  connecting = false;
}

static void sppCb(esp_spp_cb_event_t event, esp_spp_cb_param_t *p) {
  switch (event) {
    case ESP_SPP_INIT_EVT:
      out.println("bt spp ready");
      break;
    case ESP_SPP_DISCOVERY_COMP_EVT:
      if (p->disc_comp.status != ESP_SPP_SUCCESS) {
        out.printf("bt discovery failed (status %d); is the radio on and in range?\n", p->disc_comp.status);
        foundCount = 0;
        connecting = false;
        break;
      }
      foundCount = p->disc_comp.scn_num;
      for (int i = 0; i < foundCount; i++) {
        foundScn[i] = p->disc_comp.scn[i];
        strncpy(foundName[i], p->disc_comp.service_name[i] ? p->disc_comp.service_name[i] : "", SPP_NAME_MAX);
        foundName[i][SPP_NAME_MAX] = 0;
        out.printf("bt service scn %d \"%s\"\n", foundScn[i], foundName[i]);
      }
      out.printf("bt chose ctl scn %d, aud scn %d\n", chooseScn(CTL), chooseScn(AUD));
      connectNext();
      break;
    case ESP_SPP_OPEN_EVT: {
      int c = handle[CTL] ? AUD : CTL;
      if (p->open.status != ESP_SPP_SUCCESS) {
        out.printf("bt open %s FAILED (status %d)\n", NAMES[c], p->open.status);
        if (c == AUD) { audTries++; connectNext(); }  // next audio candidate, if any
        else connecting = false;
        break;
      }
      handle[c] = p->open.handle;
      congested[c] = false;
      out.printf("bt open %s ok (handle %lu)\n", NAMES[c], (unsigned long)p->open.handle);
      connectNext();
      break;
    }
    case ESP_SPP_CL_INIT_EVT:
      if (p->cl_init.status != ESP_SPP_SUCCESS) {
        out.printf("bt client init FAILED (status %d)\n", p->cl_init.status);
        connecting = false;
      }
      break;
    case ESP_SPP_CLOSE_EVT: {
      int c = channelForHandle(p->close.handle);
      if (c >= 0) {
        handle[c] = 0;
        out.printf("bt close %s\n", NAMES[c]);
      }
      break;
    }
    case ESP_SPP_DATA_IND_EVT: {
      int c = channelForHandle(p->data_ind.handle);
      if (c < 0) break;
      size_t sent = xStreamBufferSend(fromRadio[c], p->data_ind.data, p->data_ind.len, 0);
      if (sent < p->data_ind.len) out.printf("bt %s buffer full, dropped %u bytes\n", NAMES[c], (unsigned)(p->data_ind.len - sent));
      break;
    }
    case ESP_SPP_CONG_EVT: {
      int c = channelForHandle(p->cong.handle);
      if (c >= 0) congested[c] = p->cong.cong;
      break;
    }
    default:
      break;
  }
}

static void startRadio() {
  if (handle[CTL] || connecting) return;
  if (!parseMac(radioMac, peer)) {
    out.println("bad radio mac; set it with: mac AA:BB:CC:DD:EE:FF");
    return;
  }
  connecting = true;
  lastAttempt = connectStart = millis();
  audTries = 0;
  out.printf("bt discovering %s\n", radioMac.c_str());
  esp_spp_start_discovery(peer);
}

static void stopRadio() {
  for (int c = 0; c < 2; c++) if (handle[c]) esp_spp_disconnect(handle[c]);
  connecting = false;
}

static bool sendToRadio(int c, const uint8_t *d, size_t n) {
  if (!handle[c]) return false;
  // esp_spp_write copies the data; on congestion wait briefly instead of dropping.
  for (int i = 0; i < 50 && congested[c]; i++) delay(2);
  return esp_spp_write(handle[c], n, (uint8_t *)d) == ESP_OK;
}

static void startWifi() {
  String ssid = prefs.getString("ssid", "");
  WiFi.mode(WIFI_MODE_NULL);
  if (ssid.length()) {
    WiFi.mode(WIFI_STA);
    WiFi.setHostname("hamradio-bridge");
    WiFi.begin(ssid.c_str(), prefs.getString("pass", "").c_str());
    out.printf("wifi joining \"%s\"\n", ssid.c_str());
  } else {
    WiFi.mode(WIFI_AP);
    String appass = prefs.getString("appass", "");
    if (appass.length() < 8) {  // per-board random AP password, shown only on the USB console
      char buf[13];
      for (int i = 0; i < 12; i++) buf[i] = "abcdefghjkmnpqrstuvwxyz23456789"[esp_random() % 31];
      buf[12] = 0;
      appass = buf;
      prefs.putString("appass", appass);
    }
    WiFi.softAP(AP_SSID, appass.c_str());
    out.printf("wifi access point \"%s\" at %s (password: type 'appass' on this console)\n", AP_SSID, WiFi.softAPIP().toString().c_str());
  }
}

static void status() {
  out.printf("radio %s  ctl %s  aud %s\n", radioMac.c_str(), handle[CTL] ? "open" : "closed", handle[AUD] ? "open" : "closed");
  out.printf("scn wanted ctl %d aud %d; discovered %d services\n", wantScn[CTL], wantScn[AUD], foundCount);
  if (WiFi.getMode() == WIFI_STA)
    out.printf("wifi sta %s  ip %s\n", WiFi.isConnected() ? "connected" : "not connected", WiFi.localIP().toString().c_str());
  else
    out.printf("wifi ap %s  ip %s\n", AP_SSID, WiFi.softAPIP().toString().c_str());
  for (int c = 0; c < 2; c++) out.printf("tcp %u %s\n", PORTS[c], client[c] && client[c].connected() ? "client connected" : "idle");
  out.printf("audio dropped %lu bytes (slow client)\n", (unsigned long)audDropped);
}

static String otaPass() {
  String pass = prefs.getString("otapass", "");
  if (pass.length() < 8) {  // per-board random password, shown only on the USB console
    char buf[13];
    for (int i = 0; i < 12; i++) buf[i] = "abcdefghjkmnpqrstuvwxyz23456789"[esp_random() % 31];
    buf[12] = 0;
    pass = buf;
    prefs.putString("otapass", pass);
  }
  return pass;
}

static void startUpdateServer() {
  web.on(
      "/update", HTTP_POST,
      []() {
        if (!web.authenticate("bridge", otaPass().c_str())) return web.requestAuthentication();
        bool ok = !Update.hasError();
        web.send(ok ? 200 : 500, "text/plain", ok ? "updated; restarting\n" : "update FAILED; restarting\n");
        out.println(ok ? "ota update ok; restarting" : "ota update FAILED; restarting");
        delay(500);
        ESP.restart();  // bluetooth was stopped for the upload
      },
      []() {
        if (!web.authenticate("bridge", otaPass().c_str())) return;
        HTTPUpload &up = web.upload();
        if (up.status == UPLOAD_FILE_START) {
          // Once Bluetooth has been busy, Wi-Fi slows enough that uploads time out. Shut it
          // down for the update; the board restarts afterwards either way.
          stopRadio();
          btStop();
          out.printf("ota receiving %s (bluetooth stopped)\n", up.filename.c_str());
          if (!Update.begin(UPDATE_SIZE_UNKNOWN)) out.printf("ota begin FAILED: %s\n", Update.errorString());
        } else if (up.status == UPLOAD_FILE_WRITE) {
          if (Update.write(up.buf, up.currentSize) != up.currentSize)
            out.printf("ota write FAILED at %u: %s\n", (unsigned)up.totalSize, Update.errorString());
        } else if (up.status == UPLOAD_FILE_END) {
          if (Update.end(true)) out.printf("ota received %u bytes\n", (unsigned)up.totalSize);
          else out.printf("ota end FAILED: %s\n", Update.errorString());
        } else if (up.status == UPLOAD_FILE_ABORTED) {
          Update.abort();
          out.println("ota upload aborted; restarting to bring bluetooth back");
          delay(500);
          ESP.restart();
        }
      });
  web.begin();
}

static void command(String line, bool remote = false) {
  line.trim();
  if (!line.length()) return;
  int sp = line.indexOf(' ');
  String cmd = sp < 0 ? line : line.substring(0, sp);
  String arg = sp < 0 ? "" : line.substring(sp + 1);
  arg.trim();
  // Anyone on the network can reach the log port, so it can't change setup or read passwords.
  if (remote && !(cmd == "status" || cmd == "scan" || cmd == "connect" || cmd == "disconnect" || cmd == "echo" || cmd == "tx" ||
                  cmd == "sec" || cmd == "iocap" || cmd == "pin" || cmd == "unbond" || cmd == "reboot")) {
    out.printf("'%s' is USB-only\n", cmd.c_str());
    return;
  }
  if (cmd == "status") status();
  else if (cmd == "scan" || cmd == "connect") { foundCount = -1; startRadio(); }
  else if (cmd == "disconnect") stopRadio();
  else if (cmd == "mac") { radioMac = arg; radioMac.toUpperCase(); prefs.putString("mac", radioMac); out.printf("radio mac %s\n", radioMac.c_str()); }
  else if (cmd == "scn") {
    int a = 0, b = 0;
    sscanf(arg.c_str(), "%d %d", &a, &b);
    wantScn[CTL] = a; wantScn[AUD] = b;
    prefs.putUChar("scnctl", a); prefs.putUChar("scnaud", b);
    out.printf("scn ctl %d aud %d\n", a, b);
  }
  else if (cmd == "wifi") {
    int s2 = arg.indexOf(' ');
    if (s2 < 0) { out.println("usage: wifi <ssid> <password>"); return; }
    prefs.putString("ssid", arg.substring(0, s2));
    prefs.putString("pass", arg.substring(s2 + 1));
    out.println("wifi saved; restarting wifi");
    startWifi();
  }
  else if (cmd == "ap") { prefs.remove("ssid"); prefs.remove("pass"); startWifi(); }
  else if (cmd == "appass") out.printf("ap password %s\n", prefs.getString("appass", "").c_str());
  else if (cmd == "otapass") Serial.printf("ota password %s\n", otaPass().c_str());  // USB only, never to the log ring
  else if (cmd == "echo") { echoCtl = arg != "off"; out.printf("echo %s\n", echoCtl ? "on" : "off"); }
  else if (cmd == "sec") {
    int i = indexOf(SEC_NAMES, 3, arg);
    if (i >= 0) { secLevel = i; prefs.putUChar("sec", i); }
    out.printf("sec %s (none|auth|encrypt)\n", SEC_NAMES[secLevel]);
  }
  else if (cmd == "iocap") {
    int i = indexOf(IOCAP_NAMES, 4, arg);
    if (i >= 0) { ioCap = i; prefs.putUChar("iocap", i); applyIoCap(); }
    out.printf("iocap %s (none|yesno|display|keyboard)\n", IOCAP_NAMES[ioCap]);
  }
  else if (cmd == "pin") {
    if (arg.length()) { pinCode = arg; prefs.putString("pin", pinCode); }
    out.printf("pin %s\n", pinCode.c_str());
  }
  else if (cmd == "reboot") { out.println("rebooting"); delay(300); ESP.restart(); }
  else if (cmd == "unbond") {  // forget stored link keys so the next connect pairs fresh
    int n = esp_bt_gap_get_bond_device_num();
    esp_bd_addr_t list[16];
    if (n > 16) n = 16;
    esp_bt_gap_get_bond_device_list(&n, list);
    for (int i = 0; i < n; i++) esp_bt_gap_remove_bond_device(list[i]);
    out.printf("removed %d bonds\n", n);
  }
  else if (cmd == "tx") {
    uint8_t buf[256];
    size_t n = 0;
    for (size_t i = 0; i + 1 < arg.length() && n < sizeof(buf); i += 2) buf[n++] = strtoul(arg.substring(i, i + 2).c_str(), nullptr, 16);
    out.println(sendToRadio(CTL, buf, n) ? "tx ok" : "tx FAILED: control channel not open");
  }
  else out.println("commands: status scan connect disconnect mac scn wifi ap appass otapass echo tx sec iocap pin unbond reboot");
}

void setup() {
  ringLock = xSemaphoreCreateMutex();
  Serial.begin(115200);
  delay(300);
  out.printf("\nhamradio-bridge starting (built %s %s)\n", __DATE__, __TIME__);
  prefs.begin("bridge", false);
  radioMac = prefs.getString("mac", radioMac);
  wantScn[CTL] = prefs.getUChar("scnctl", 0);
  wantScn[AUD] = prefs.getUChar("scnaud", 0);
  secLevel = min((int)prefs.getUChar("sec", 1), 2);
  ioCap = min((int)prefs.getUChar("iocap", 0), 3);
  pinCode = prefs.getString("pin", pinCode);
  for (int c = 0; c < 2; c++) {
    fromRadio[c] = xStreamBufferCreate(c == AUD ? 16384 : 4096, 1);
    server[c] = new WiFiServer(PORTS[c]);
  }

  // Canonical classic-BT (SPP) bring-up: init the controller with the default
  // (BTDM) config, then enable CLASSIC_BT only. We deliberately do NOT call
  // esp_bt_controller_mem_release(ESP_BT_MODE_BLE) before init: on this BT-dual
  // build that makes esp_bt_controller_init reject the config and return
  // ESP_ERR_INVALID_STATE even from a cold (IDLE) controller. The unused BLE
  // memory stays reserved (a few KB) which this small firmware can spare; a
  // working, reliable BT bring-up matters more than reclaiming it.
  // Each step is status-guarded (the core can leave the controller already
  // INITED/ENABLED at boot, and re-running a done step returns INVALID_STATE),
  // reports the exact failing call, and never falls through to GAP/SPP with a
  // half-up stack (that dereferenced a NULL own-address and boot-looped).
  // Bring up the classic-BT controller through Arduino's maintained helper
  // btStart() instead of hand-calling esp_bt_controller_init/enable: the manual
  // sequence returned ESP_ERR_INVALID_STATE on every call from a cold IDLE
  // controller on this toolchain. btStart() uses the controller mode from
  // sdkconfig (BTDM here) and is idempotent. Bluedroid is then layered on top,
  // the same order BluetoothSerial uses. INVALID_STATE on a bluedroid step is
  // treated as "already up"; success is read from the FINAL status only.
  auto okOrDone = [](esp_err_t e) { return e == ESP_OK || e == ESP_ERR_INVALID_STATE; };
  out.printf("btStarted at boot: %d, controller status: %d, bluedroid: %d\n",
                (int)btStarted(), (int)esp_bt_controller_get_status(),
                (int)esp_bluedroid_get_status());

  bool btUp = false;
  if (!btStart()) {
    out.printf("btStart() FAILED (controller status %d)\n",
                  (int)esp_bt_controller_get_status());
  } else {
    esp_err_t e = esp_bluedroid_init();
    out.printf("bluedroid_init -> 0x%x (%s)\n", e, esp_err_to_name(e));
    if (okOrDone(e)) {
      e = esp_bluedroid_enable();
      out.printf("bluedroid_enable -> 0x%x (%s)\n", e, esp_err_to_name(e));
    }
    out.printf("bt final status: controller %d, bluedroid %d\n",
                  (int)esp_bt_controller_get_status(), (int)esp_bluedroid_get_status());
    btUp = (esp_bt_controller_get_status() == ESP_BT_CONTROLLER_STATUS_ENABLED &&
            esp_bluedroid_get_status() == ESP_BLUEDROID_STATUS_ENABLED);
  }

  if (!btUp) {
    out.println("bluetooth unavailable; console + Wi-Fi stay up so you can inspect state");
  } else {
    esp_bt_dev_set_device_name("hamradio-bridge");
    esp_bt_gap_register_callback(gapCb);
    applyIoCap();
    out.printf("pairing: sec %s, iocap %s\n", SEC_NAMES[secLevel], IOCAP_NAMES[ioCap]);
    esp_spp_register_callback(sppCb);
    esp_spp_init(ESP_SPP_MODE_CB);
    const uint8_t *own = esp_bt_dev_get_address();
    if (own)
      out.printf("bridge bt address %02X:%02X:%02X:%02X:%02X:%02X\n",
                    own[0], own[1], own[2], own[3], own[4], own[5]);
    else
      out.println("bridge bt address unavailable");
  }

  startWifi();
  for (int c = 0; c < 2; c++) { server[c]->begin(); server[c]->setNoDelay(true); }
  logServer.begin();
  startUpdateServer();
  out.println("type 'connect' to pair with the radio, 'status' for state");
}

void loop() {
  static String line;
  while (Serial.available()) {
    char ch = Serial.read();
    if (ch == '\n' || ch == '\r') { command(line); line = ""; }
    else if (line.length() < 300) line += ch;
  }

  static String remoteLine;
  if (logServer.hasClient()) {
    if (logClient && logClient.connected()) logClient.stop();
    logClient = logServer.available();
    remoteLine = "";
    if (xSemaphoreTake(ringLock, pdMS_TO_TICKS(20))) {  // replay what's still in the ring
      ringR = ringW > RING ? ringW - RING : 0;
      xSemaphoreGive(ringLock);
    }
  }
  while (logClient && logClient.connected() && logClient.available()) {
    char ch = logClient.read();
    if (ch == '\n' || ch == '\r') { command(remoteLine, true); remoteLine = ""; }
    else if (remoteLine.length() < 300) remoteLine += ch;
  }
  drainLog();
  web.handleClient();

  // An open on a channel the radio isn't serving never completes; give up on it so later
  // attempts aren't blocked (audio moves to its next candidate).
  if (connecting && millis() - connectStart > CONNECT_TIMEOUT_MS) {
    out.printf("bt connect timed out (%s)\n", handle[CTL] ? "aud" : "ctl");
    if (handle[CTL]) { audTries++; connectNext(); }
    else connecting = false;
  }

  // Keep retrying the radio every 15 s once a TCP client wants it.
  bool wanted = (client[CTL] && client[CTL].connected());
  if (wanted && !handle[CTL] && !connecting && millis() - lastAttempt > 15000) startRadio();

  uint8_t buf[1024];
  for (int c = 0; c < 2; c++) {
    if (server[c]->hasClient()) {
      if (client[c] && client[c].connected()) client[c].stop();  // newest client wins
      client[c] = server[c]->available();
      client[c].setNoDelay(true);
      if (c == AUD) {  // blocking sends with a short timeout for live audio (see the write below)
        int fd = client[c].fd();
        fcntl(fd, F_SETFL, fcntl(fd, F_GETFL, 0) & ~O_NONBLOCK);
        struct timeval tv = {0, 20000};
        setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));
      }
      out.printf("tcp %u client %s\n", PORTS[c], client[c].remoteIP().toString().c_str());
      if (!handle[CTL] && !connecting) startRadio();
    }
    size_t n;
    bool stalled = false;  // this pass's audio send already timed out: discard the rest
    while ((n = xStreamBufferReceive(fromRadio[c], buf, sizeof(buf), 0)) > 0) {
      if (c == CTL && echoCtl) printHex("rx ctl", buf, n);
      if (!client[c] || !client[c].connected()) continue;
      if (c == CTL) { client[c].write(buf, n); continue; }
      // Audio is live: if the client isn't keeping up, drop audio rather than block the loop,
      // which would also stall the control channel. WiFiClient::write retries for up to 10 s,
      // so send on the socket directly with the short timeout set at accept, at most once a pass.
      if (stalled) { audDropped += n; continue; }
      size_t done = 0;
      while (done < n) {
        int sent = send(client[c].fd(), buf + done, n - done, 0);
        if (sent <= 0) {
          if (errno != EAGAIN && errno != EWOULDBLOCK) {
            out.printf("tcp %u send error %d; dropping client\n", PORTS[c], errno);
            client[c].stop();
          }
          stalled = true;
          break;
        }
        done += sent;
      }
      audDropped += n - done;
    }
    if (c == AUD) {
      if (!stalled) audStallSince = 0;
      else if (!audStallSince) audStallSince = millis();
      else if (millis() - audStallSince > 3000 && client[c]) {  // a reader stuck this long is gone
        out.printf("tcp %u client stalled 3 s; dropping it\n", PORTS[c]);
        client[c].stop();
        audStallSince = 0;
      }
    }
    while (client[c] && client[c].connected() && client[c].available()) {
      n = client[c].read(buf, sizeof(buf));
      if (n > 0 && !sendToRadio(c, buf, n)) break;
    }
  }
  delay(1);
}
