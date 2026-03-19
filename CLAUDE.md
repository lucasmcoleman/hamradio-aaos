# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
./gradlew assembleDebug        # Debug APK (mock off, .debug suffix)
./gradlew assembleMockRadio    # Mock variant (mock always on)
./gradlew assembleRelease      # Release APK (minified, signed)
./gradlew bundleRelease        # Release AAB for Play Store
./gradlew test                 # All unit tests
```

Release signing requires `keystore.properties` in project root (gitignored):
```
storeFile=<path>
storePassword=<pass>
keyAlias=key0
keyPassword=<pass>
```

Install to AAOS emulator:
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell monkey -p com.kk4fvc.benshiradiocontrol.debug -c android.intent.category.LAUNCHER 1
```

## Architecture

**MVVM with reactive state:** Transport → RadioController → MainViewModel → Compose UI

```
UI (Compose Screens)
  ↓ actions (setVolume, pttDown, updateSettings)
MainViewModel (StateFlow exposure, VFO state, preferences)
  ↓
RadioController (@Singleton, command dispatch, debouncing, audio focus)
  ↓
IRadioTransport (BleTransport | MockTransport | DisconnectedTransport)
  ↓
Benshi Protocol (BenshiMessage encode/decode, GAIA framing for RFCOMM)
```

**Transport is injected once by Hilt at startup.** Switching between mock/BLE requires app restart (Activity.recreate via restartEvent). DisconnectedTransport is used when no device address is configured.

## Protocol Layer

The Benshi protocol is a binary command/reply system. Wire format:
- Bytes 0-1: commandGroup (16-bit BE)
- Bit 16: isReply flag
- Bits 17-31: command ID (15 bits)
- Bytes 4+: body (command-specific)

Over BLE: raw BenshiMessage bytes. Over RFCOMM: wrapped in GaiaFrame (sync + version + flags + length + payload + optional checksum).

Key data structures in `DataModels.kt`:
- **RfChannel** (25 bytes) — frequency, tone, power, bandwidth, mute
- **RadioSettings** (20 bytes) — channels A/B encoded as split nibbles across bytes 0 and 9
- **HtStatus** (4 bytes) — real-time TX/RX/squelch/RSSI/GPS state
- **BssSettings** (46 bytes) — APRS callsign, symbol, beacon config

Settings writes use `patchRawData()` which preserves unknown bytes from the original radio response, only modifying known fields.

## Critical Patterns

**NEVER use `return@Column` or `return@lambda` in Compose screen composables.** This changes the composable group count between recompositions (null→non-null state) and causes `IndexOutOfBoundsException` in Compose runtime's `Stack.pop()`. Use `if/else` wrapping instead:
```kotlin
// WRONG — crashes on recomposition
if (settings == null) { Text("Loading"); return@Column }
val s = settings!!

// CORRECT — stable group structure
val s = settings
if (s == null) { Text("Loading") } else { /* full UI */ }
```

**Settings write debouncing:** `updateSettings()` and `updateBssSettings()` use AtomicInteger generation counters with 500ms delay to avoid flooding BLE on slider drags. The pattern: optimistic UI update → increment gen → delay → check gen unchanged → send.

**AudioFocus for PTT:** `pttDown()` requests `AUDIOFOCUS_GAIN_TRANSIENT` (pauses music), `pttUp()` abandons focus.

**Thread safety:** `logBuffer` (ArrayDeque) is accessed from IO dispatcher and main thread — all access must be `synchronized(logBuffer)`.

## State Management

- **StateFlow** for observable state (channels, volume, connectionState)
- **MutableSharedFlow** (no buffer) for one-shot events (errorEvent, restartEvent) — never use `extraBufferCapacity` for events that trigger `recreate()`
- **Preferences as StateFlow:** RadioPrefs values that affect UI (rxAudioRoute, pttToggleMode, etc.) are mirrored as MutableStateFlow in ViewModel for Compose observation
- All Compose state collection uses `collectAsStateWithLifecycle()`, never conditionally

## Navigation

Bottom nav has 4 static tabs (Home, Channels, Settings, APRS). Debug screen is accessible only from Settings → Developer → Debug Console. The tab list must never change size dynamically — Compose NavigationBar crashes on group count changes.

Navigation uses `popUpTo(startDestination) { saveState = true }` + `restoreState = true` for all routes including programmatic navigation from HomeScreen.

## App Identity

- **Package:** `com.kk4fvc.benshiradiocontrol` (release), `.debug` suffix for debug
- **Namespace:** `com.hamradio.aaos` (internal R class, do not change)
- **Target:** AAOS (Android Automotive), landscape-only, minSdk 29

## Design Conventions

- Dark cockpit theme: background 0xFF0A0A0F, accent amber 0xFFFFB300
- TX = Red (0xFFE53935), RX = Green (0xFF00C853) — enforced everywhere
- Power levels are radio-agnostic: High/Medium/Low (not wattage)
- Frequency display follows ham convention: show RX freq normally, TX freq only during active transmit
- AAOS touch targets: minimum 48dp, nav bar items 72dp
