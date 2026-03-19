# Opus Code Review — Full Codebase Audit

> Sonnet wrote the initial 37-file, 4300+ line implementation.
> This document is the Opus review, identifying what works, what's broken,
> and the exact fix sequence. Pick up from here in any environment.

---

## Branch

`claude/db50b-protocol-research-1os8P`

---

## Specific Requirements Verified

### ✅ TX = red, RX = green — everywhere
Single source of truth in `app/src/main/java/com/hamradio/aaos/ui/theme/Color.kt`:
```kotlin
val TxRed   = Color(0xFFE53935)   // TX always red
val RxGreen = Color(0xFF00C853)   // RX always green
```
Used correctly and exclusively in:
- `TxRxIndicator.kt` — dot colors, pulse animation
- `SignalMeter.kt` — bar colors (via `SignalFull` = RxGreen, `SignalLow` = TxRed)
- `ChannelCard.kt` — border and frequency label colors
- `HomeScreen.kt` — channel display border, freq text, `FreqText` composable
- `DebugScreen.kt` — log row background (TX=red, RX=green, notification=amber)

No hardcoded hex colors for TX/RX exist anywhere else.

### ✅ Generic for any Benshi-protocol radio
- UI screens have zero references to "DB50-B" or "Radioddity"
- Protocol layer is fully generic (`BenshiMessage`, `RadioCommands`, `DataModels`)
- `MockTransport` comment says "simulates a Radioddity DB50-B" — just a comment, not functional
- `strings.xml` app name is `BenshiRadio` — acceptable

---

## What Works Correctly

**Protocol message layer:**
- `BenshiMessage.encode()` / `.decode()`: big-endian framing, reply flag in MSB, body preserved
- Byte helpers (`getInt`, `putInt`, `getShort`, `putShort`): correct
- `SubAudio` encode/decode: CTCSS/DCS boundary at raw=6700 correct
- `HtStatus.decode()`: flag extraction, RSSI parse correct
- `RadioSettings.decode()`: split-nibble channel A/B assembly correct
- `RadioCommands` factory methods: correct payloads

**Build system:**
- Compose BOM 2024.02.00 + Kotlin 1.9.22 + Hilt 2.51 + AGP 8.3.0 — all compatible
- `assembleMockRadio` build variant correctly defined

**Android Manifest (AAOS-correct):**
- `android.hardware.type.automotive` feature declared (`required=false`)
- Bluetooth permissions for pre/post Android 12
- Landscape, fullscreen, config changes correct

**UI architecture:**
- `ViewModel.onCleared()` disconnects radio — no leak
- All StateFlows scoped to `viewModelScope`
- Navigation `popUpTo`/`saveState`/`restoreState` pattern correct
- 72dp nav bar, 28dp icons — good AAOS touch targets
- `collectAsStateWithLifecycle()` used throughout (not `collectAsState()`)

---

## Bugs to Fix

### BUG 1 — CRITICAL: RfChannel size mismatch (tests will NPE)
**File:** `app/src/main/java/com/hamradio/aaos/radio/protocol/DataModels.kt`

- `RfChannel.encode()` returns `ByteArray(25)`
- `RfChannel.decode()` has guard `if (bytes.size < 30) return null`
- `DataModelsTest` round-trip test: encode → 25 bytes → decode returns null → NPE

**Fix:**
```kotlin
// Change line ~100 in DataModels.kt from:
if (bytes.size < 30) return null
// to:
if (bytes.size < 25) return null
```

### BUG 2 — COMPILATION: Deprecated `Divider()` API
Material3 Compose 1.6+ replaced `Divider` with `HorizontalDivider`.

**Affected files:**
- `SettingsScreen.kt` — 2 calls: change `Divider(` → `HorizontalDivider(`
- `AprsScreen.kt` — 3 calls: same change
- `HomeScreen.kt` — unused import `androidx.compose.material3.Divider`, remove it

**Import to replace:**
```kotlin
// Old (delete this):
import androidx.compose.material3.Divider
// New:
import androidx.compose.material3.HorizontalDivider
```

### BUG 3 — LATENT: GaiaFrame 1-byte length truncation
**File:** `app/src/main/java/com/hamradio/aaos/radio/protocol/BenshiMessage.kt` ~line 95

```kotlin
frame[3] = payloadLen.toByte()  // silently wraps at 256
```
Current messages are small so it won't trigger, but should be guarded:
```kotlin
require(payloadLen <= 255) { "Benshi payload too large: $payloadLen bytes" }
frame[3] = payloadLen.toByte()
```

---

## Improvements Needed (Priority Order)

### HIGH — affects real-hardware correctness

**4. BleTransport: CCCD not verified before CONNECTED**
`BleTransport.kt` transitions to `CONNECTED` immediately after writing the indication
descriptor. It should wait for `onDescriptorWrite()` to confirm success first.
```kotlin
override fun onDescriptorWrite(gatt, descriptor, status) {
    if (status == BluetoothGatt.GATT_SUCCESS) {
        _connectionState.value = ConnectionState.CONNECTED
        startSendWorker()
    } else {
        _connectionState.value = ConnectionState.ERROR
    }
}
```

**5. BleTransport: No frame reassembly**
`onCharacteristicChanged` treats each indication as a complete message. BLE MTU is
~50 bytes by default; large channel reads or settings will be fragmented and silently
discarded. Need a receive buffer:
```kotlin
private val receiveBuffer = ByteArrayOutputStream()
// in onCharacteristicChanged: append to buffer, try GaiaFrame.decode in a loop
```

**6. RadioController: No timeout on init sequence**
`initializeRadio()` sends GET_DEV_INFO then waits for a reply. If the radio doesn't
respond, the coroutine hangs forever. Wrap with:
```kotlin
withTimeoutOrNull(10_000L) { initializeRadio() }
    ?: _connectionState.value = ConnectionState.ERROR
```

### MEDIUM — robustness

**7. BleTransport: No MTU negotiation**
Add `gatt.requestMtu(512)` immediately after `onServicesDiscovered` success.
Overrides default 23-byte MTU; wait for `onMtuChanged` before proceeding.

**8. BleTransport: sendWorker null-safety race**
`gatt` reference can be nulled by `disconnect()` while `sendWorker` is mid-write.
The `characteristic.setValue()` / `writeCharacteristic()` calls need null-safe access.

**9. RadioController: Fire-and-forget sends**
Commands are sent with no acknowledgment or retry. For writes (SET_VOLUME,
WRITE_SETTINGS, WRITE_RF_CH) a request/response pattern with `CompletableDeferred`
and timeout would prevent silent failures.

**10. BleTransport coroutine scope not injected**
`BleTransport` creates its own `CoroutineScope(Dispatchers.IO)` that's never
cancelled. Inject an `@ApplicationScope` via Hilt so the scope is tied to the app lifecycle.

**11. RadioModule: Silent mock fallback**
When no device address is stored, it silently creates a `BleTransport` with an empty
address. Should log a warning or immediately return a mock instead.

**12. Message log allocation**
```kotlin
// Current — allocates a new list on every message:
_messageLog.value = (_messageLog.value + entry).takeLast(200)
// Better:
private val logBuffer = ArrayDeque<LogEntry>(200)
// ...
if (logBuffer.size >= 200) logBuffer.removeFirst()
logBuffer.addLast(entry)
_messageLog.value = logBuffer.toList()
```

### LOW — polish

**13. MockTransport: unbounded channel list growth**
`applyChannel()` fills the list by index. A `channelId` of 1000 would allocate 1000
nulls. Add bounds check: `if (ch.channelId > 200) return`.

**14. `"__mock__"` sentinel** in RadioModule — replace with `null` check on device address.

**15. RadioPrefs: no MAC address validation** — add `BluetoothAdapter.checkBluetoothAddress()`.

---

## Verification Checklist (when resuming)

```
1. ./gradlew test                    # 21 unit tests — BUG 1 fix required
2. ./gradlew assembleDebug           # BUG 2 fix required (Divider)
3. ./gradlew assembleMockRadio       # mock variant
4. AAOS emulator: mock connects, channels load, TX/RX colors animate correctly
5. Real radio: BLE scan → connect → init sequence → channels populate
```

---

## File Map

```
app/src/main/java/com/hamradio/aaos/
  HamRadioApp.kt                        — Hilt @HiltAndroidApp
  MainActivity.kt                       — single-activity Compose host
  di/
    RadioModule.kt                      — Hilt providers (BLE or Mock)
    RadioPrefs.kt                       — SharedPreferences wrapper
  radio/
    RadioController.kt                  — init sequence, state, notification dispatch
    protocol/
      BenshiMessage.kt                  — GAIA frame + encode/decode + RadioCommands
      Commands.kt                       — 76 command ID constants
      DataModels.kt                     — RfChannel, HtStatus, RadioSettings, BssSettings, DeviceInfo
    transport/
      IRadioTransport.kt                — interface + ConnectionState
      BleTransport.kt                   — BluetoothGatt implementation
      MockTransport.kt                  — 16-channel simulator
  vm/
    MainViewModel.kt                    — Compose-facing ViewModel
  ui/
    AppNavigation.kt                    — NavHost + BottomBar (Home/Channels/Settings/APRS/Debug)
    theme/
      Color.kt                          — ALL colors (TxRed, RxGreen, etc.)
      Type.kt                           — AAOS-scale typography
      Theme.kt                          — MaterialTheme wrapper
    components/
      TxRxIndicator.kt                  — animated TX/RX/SQ pill
      SignalMeter.kt                    — 15-bar S-meter
      ChannelCard.kt                    — channel list item
      VolumeSlider.kt                   — volume control
      BatteryIndicator.kt               — battery level + icon
    screens/
      HomeScreen.kt                     — dual-channel display, main operating view
      ChannelsScreen.kt                 — searchable channel list, A/B slot picker
      SettingsScreen.kt                 — squelch, scan, mic gain, power settings
      AprsScreen.kt                     — BSS/APRS config read-out
      DebugScreen.kt                    — live protocol log, quick commands, device info
app/src/test/java/com/hamradio/aaos/radio/protocol/
  BenshiMessageTest.kt                  — 8 encode/decode tests
  DataModelsTest.kt                     — HtStatus, SubAudio, RfChannel, byte helper tests
  GaiaFrameTest.kt                      — GAIA framing tests
app/src/main/res/values/
  strings.xml                           — app_name = "BenshiRadio"
  themes.xml                            — base window theme (Compose does the rest)
```
