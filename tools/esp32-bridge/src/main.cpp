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
#include <Arduino.h>
#include <Preferences.h>
#include <WiFi.h>
#include <esp_bt.h>
#include <esp_bt_main.h>
#include <esp_bt_device.h>
#include <esp_gap_bt_api.h>
#include <esp_spp_api.h>
#include <freertos/stream_buffer.h>

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

static bool parseMac(const String &s, esp_bd_addr_t out) {
  int v[6];
  if (sscanf(s.c_str(), "%x:%x:%x:%x:%x:%x", &v[0], &v[1], &v[2], &v[3], &v[4], &v[5]) != 6) return false;
  for (int i = 0; i < 6; i++) out[i] = v[i];
  return true;
}

static void printHex(const char *tag, const uint8_t *d, size_t n) {
  Serial.printf("%s ", tag);
  for (size_t i = 0; i < n; i++) Serial.printf("%02X", d[i]);
  Serial.println();
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
  return 0;
}

static void gapCb(esp_bt_gap_cb_event_t event, esp_bt_gap_cb_param_t *p) {
  switch (event) {
    case ESP_BT_GAP_AUTH_CMPL_EVT:
      Serial.printf("bt auth %s (status %d)\n", p->auth_cmpl.stat == ESP_BT_STATUS_SUCCESS ? "ok" : "FAILED", p->auth_cmpl.stat);
      break;
    case ESP_BT_GAP_PIN_REQ_EVT: {  // legacy pairing fallback
      esp_bt_pin_code_t pin = {'0', '0', '0', '0'};
      esp_bt_gap_pin_reply(p->pin_req.bda, true, 4, pin);
      Serial.println("bt pin request answered with 0000");
      break;
    }
    case ESP_BT_GAP_CFM_REQ_EVT:     // SSP numeric comparison: no screen here, so accept
      esp_bt_gap_ssp_confirm_reply(p->cfm_req.bda, true);
      Serial.printf("bt ssp confirm %06lu accepted\n", (unsigned long)p->cfm_req.num_val);
      break;
    default:
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
    Serial.printf("bt connect %s scn %d\n", NAMES[c], scn);
    esp_spp_connect(ESP_SPP_SEC_AUTHENTICATE, ESP_SPP_ROLE_MASTER, scn, peer);
    return;
  }
  connecting = false;
}

static void sppCb(esp_spp_cb_event_t event, esp_spp_cb_param_t *p) {
  switch (event) {
    case ESP_SPP_INIT_EVT:
      Serial.println("bt spp ready");
      break;
    case ESP_SPP_DISCOVERY_COMP_EVT:
      if (p->disc_comp.status != ESP_SPP_SUCCESS) {
        Serial.printf("bt discovery failed (status %d); is the radio on and in range?\n", p->disc_comp.status);
        foundCount = 0;
        connecting = false;
        break;
      }
      foundCount = p->disc_comp.scn_num;
      for (int i = 0; i < foundCount; i++) {
        foundScn[i] = p->disc_comp.scn[i];
        strncpy(foundName[i], p->disc_comp.service_name[i] ? p->disc_comp.service_name[i] : "", SPP_NAME_MAX);
        foundName[i][SPP_NAME_MAX] = 0;
        Serial.printf("bt service scn %d \"%s\"\n", foundScn[i], foundName[i]);
      }
      Serial.printf("bt chose ctl scn %d, aud scn %d\n", chooseScn(CTL), chooseScn(AUD));
      connectNext();
      break;
    case ESP_SPP_OPEN_EVT: {
      int c = handle[CTL] ? AUD : CTL;
      if (p->open.status != ESP_SPP_SUCCESS) {
        Serial.printf("bt open %s FAILED (status %d)\n", NAMES[c], p->open.status);
        connecting = false;
        break;
      }
      handle[c] = p->open.handle;
      congested[c] = false;
      Serial.printf("bt open %s ok (handle %lu)\n", NAMES[c], (unsigned long)p->open.handle);
      connectNext();
      break;
    }
    case ESP_SPP_CL_INIT_EVT:
      if (p->cl_init.status != ESP_SPP_SUCCESS) {
        Serial.printf("bt client init FAILED (status %d)\n", p->cl_init.status);
        connecting = false;
      }
      break;
    case ESP_SPP_CLOSE_EVT: {
      int c = channelForHandle(p->close.handle);
      if (c >= 0) {
        handle[c] = 0;
        Serial.printf("bt close %s\n", NAMES[c]);
      }
      break;
    }
    case ESP_SPP_DATA_IND_EVT: {
      int c = channelForHandle(p->data_ind.handle);
      if (c < 0) break;
      size_t sent = xStreamBufferSend(fromRadio[c], p->data_ind.data, p->data_ind.len, 0);
      if (sent < p->data_ind.len) Serial.printf("bt %s buffer full, dropped %u bytes\n", NAMES[c], (unsigned)(p->data_ind.len - sent));
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
    Serial.println("bad radio mac; set it with: mac AA:BB:CC:DD:EE:FF");
    return;
  }
  connecting = true;
  lastAttempt = millis();
  Serial.printf("bt discovering %s\n", radioMac.c_str());
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
    Serial.printf("wifi joining \"%s\"\n", ssid.c_str());
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
    Serial.printf("wifi access point \"%s\" at %s (password: type 'appass' on this console)\n", AP_SSID, WiFi.softAPIP().toString().c_str());
  }
}

static void status() {
  Serial.printf("radio %s  ctl %s  aud %s\n", radioMac.c_str(), handle[CTL] ? "open" : "closed", handle[AUD] ? "open" : "closed");
  Serial.printf("scn wanted ctl %d aud %d; discovered %d services\n", wantScn[CTL], wantScn[AUD], foundCount);
  if (WiFi.getMode() == WIFI_STA)
    Serial.printf("wifi sta %s  ip %s\n", WiFi.isConnected() ? "connected" : "not connected", WiFi.localIP().toString().c_str());
  else
    Serial.printf("wifi ap %s  ip %s\n", AP_SSID, WiFi.softAPIP().toString().c_str());
  for (int c = 0; c < 2; c++) Serial.printf("tcp %u %s\n", PORTS[c], client[c] && client[c].connected() ? "client connected" : "idle");
}

static void command(String line) {
  line.trim();
  if (!line.length()) return;
  int sp = line.indexOf(' ');
  String cmd = sp < 0 ? line : line.substring(0, sp);
  String arg = sp < 0 ? "" : line.substring(sp + 1);
  arg.trim();
  if (cmd == "status") status();
  else if (cmd == "scan" || cmd == "connect") { foundCount = -1; startRadio(); }
  else if (cmd == "disconnect") stopRadio();
  else if (cmd == "mac") { radioMac = arg; radioMac.toUpperCase(); prefs.putString("mac", radioMac); Serial.printf("radio mac %s\n", radioMac.c_str()); }
  else if (cmd == "scn") {
    int a = 0, b = 0;
    sscanf(arg.c_str(), "%d %d", &a, &b);
    wantScn[CTL] = a; wantScn[AUD] = b;
    prefs.putUChar("scnctl", a); prefs.putUChar("scnaud", b);
    Serial.printf("scn ctl %d aud %d\n", a, b);
  }
  else if (cmd == "wifi") {
    int s2 = arg.indexOf(' ');
    if (s2 < 0) { Serial.println("usage: wifi <ssid> <password>"); return; }
    prefs.putString("ssid", arg.substring(0, s2));
    prefs.putString("pass", arg.substring(s2 + 1));
    Serial.println("wifi saved; restarting wifi");
    startWifi();
  }
  else if (cmd == "ap") { prefs.remove("ssid"); prefs.remove("pass"); startWifi(); }
  else if (cmd == "appass") Serial.printf("ap password %s\n", prefs.getString("appass", "").c_str());
  else if (cmd == "echo") { echoCtl = arg != "off"; Serial.printf("echo %s\n", echoCtl ? "on" : "off"); }
  else if (cmd == "tx") {
    uint8_t buf[256];
    size_t n = 0;
    for (size_t i = 0; i + 1 < arg.length() && n < sizeof(buf); i += 2) buf[n++] = strtoul(arg.substring(i, i + 2).c_str(), nullptr, 16);
    Serial.println(sendToRadio(CTL, buf, n) ? "tx ok" : "tx FAILED: control channel not open");
  }
  else Serial.println("commands: status scan connect disconnect mac scn wifi ap appass echo tx");
}

void setup() {
  Serial.begin(115200);
  delay(300);
  Serial.println("\nhamradio-bridge starting");
  prefs.begin("bridge", false);
  radioMac = prefs.getString("mac", radioMac);
  wantScn[CTL] = prefs.getUChar("scnctl", 0);
  wantScn[AUD] = prefs.getUChar("scnaud", 0);
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
  Serial.printf("btStarted at boot: %d, controller status: %d, bluedroid: %d\n",
                (int)btStarted(), (int)esp_bt_controller_get_status(),
                (int)esp_bluedroid_get_status());

  bool btUp = false;
  if (!btStart()) {
    Serial.printf("btStart() FAILED (controller status %d)\n",
                  (int)esp_bt_controller_get_status());
  } else {
    esp_err_t e = esp_bluedroid_init();
    Serial.printf("bluedroid_init -> 0x%x (%s)\n", e, esp_err_to_name(e));
    if (okOrDone(e)) {
      e = esp_bluedroid_enable();
      Serial.printf("bluedroid_enable -> 0x%x (%s)\n", e, esp_err_to_name(e));
    }
    Serial.printf("bt final status: controller %d, bluedroid %d\n",
                  (int)esp_bt_controller_get_status(), (int)esp_bluedroid_get_status());
    btUp = (esp_bt_controller_get_status() == ESP_BT_CONTROLLER_STATUS_ENABLED &&
            esp_bluedroid_get_status() == ESP_BLUEDROID_STATUS_ENABLED);
  }

  if (!btUp) {
    Serial.println("bluetooth unavailable; console + Wi-Fi stay up so you can inspect state");
  } else {
    esp_bt_dev_set_device_name("hamradio-bridge");
    esp_bt_gap_register_callback(gapCb);
    esp_bt_sp_param_t param = ESP_BT_SP_IOCAP_MODE;
    esp_bt_io_cap_t iocap = ESP_BT_IO_CAP_NONE;
    esp_bt_gap_set_security_param(param, &iocap, sizeof(iocap));
    esp_spp_register_callback(sppCb);
    esp_spp_init(ESP_SPP_MODE_CB);
    const uint8_t *own = esp_bt_dev_get_address();
    if (own)
      Serial.printf("bridge bt address %02X:%02X:%02X:%02X:%02X:%02X\n",
                    own[0], own[1], own[2], own[3], own[4], own[5]);
    else
      Serial.println("bridge bt address unavailable");
  }

  startWifi();
  for (int c = 0; c < 2; c++) { server[c]->begin(); server[c]->setNoDelay(true); }
  Serial.println("type 'connect' to pair with the radio, 'status' for state");
}

void loop() {
  static String line;
  while (Serial.available()) {
    char ch = Serial.read();
    if (ch == '\n' || ch == '\r') { command(line); line = ""; }
    else if (line.length() < 300) line += ch;
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
      Serial.printf("tcp %u client %s\n", PORTS[c], client[c].remoteIP().toString().c_str());
      if (!handle[CTL] && !connecting) startRadio();
    }
    size_t n;
    while ((n = xStreamBufferReceive(fromRadio[c], buf, sizeof(buf), 0)) > 0) {
      if (c == CTL && echoCtl) printHex("rx ctl", buf, n);
      if (client[c] && client[c].connected()) client[c].write(buf, n);
    }
    while (client[c] && client[c].connected() && client[c].available()) {
      n = client[c].read(buf, sizeof(buf));
      if (n > 0 && !sendToRadio(c, buf, n)) break;
    }
  }
  delay(1);
}
