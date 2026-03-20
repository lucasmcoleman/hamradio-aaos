# Benshi Radio Control

Android Automotive OS (AAOS) app for controlling Benshi-protocol ham radios over Bluetooth.

Built for vehicle head units — large touch targets, driving-safe UX, landscape-only layout.

## Supported Radios

Any radio using the Benshi/GAIA protocol, including:
- Radioddity DB50-B
- BTECH Commander Pro (GMRS-PRO)

## Features

### Home Screen
- Dual-channel display (A/B) with live TX/RX indicators
- VFO mode with frequency, offset, tone, and bandwidth control
- Press-and-hold PTT or tap-to-toggle PTT mode
- TX power selector (High / Medium / Low)
- S-meter with S9+ labels
- Volume control with real-time updates

### Channel Management
- Full channel editor: frequency, CTCSS/DCS tones, power, bandwidth, mute
- Add new channels or save VFO as channel
- Band-aware offset buttons (600 kHz for 2m, 5 MHz for 70cm)
- Frequency warnings for out-of-band operation (non-blocking)

### APRS / BSS
- Master APRS enable/disable
- Station identity: callsign, SSID (with presets), symbol (with presets)
- Beacon configuration: message, interval, TTL, max forwards
- APRS packet reception with AX.25 decoding and position parsing
- Dedicated APRS channel assignment on Channel B

### Settings
- RF: Squelch, scan, dual watch, tail elimination, PTT lock
- Audio routing: independent RX speaker and TX mic selection (Radio / Vehicle / Both)
- AudioFocus integration: music auto-pauses during TX
- Behavior: auto-switch on RX, PTT mode (momentary / toggle)
- Developer: mock radio mode, debug console

### Protocol
- Bit-level Settings parsing (22-byte wire format with all fields)
- TNC data fragment reassembly for APRS packet reception
- Debounced settings writes to avoid BLE queue flooding
- Full command/reply protocol with notification subscriptions

## Architecture

```
Compose UI  -->  MainViewModel  -->  RadioController  -->  IRadioTransport
                                         |
                                    BenshiMessage
                                    (encode/decode)
```

**Transport implementations:**
- `BleTransport` — Bluetooth Low Energy (GATT)
- `MockTransport` — Full radio simulator for development
- `DisconnectedTransport` — No-op when unconfigured

## Building

```bash
./gradlew assembleDebug        # Debug APK
./gradlew assembleRelease      # Signed release APK
./gradlew bundleRelease        # Signed AAB for Play Store
./gradlew test                 # Unit tests
```

Release signing requires a `keystore.properties` file in the project root (not committed):
```
storeFile=/path/to/keystore
storePassword=...
keyAlias=key0
keyPassword=...
```

## Requirements

- Android 10+ (API 29)
- Bluetooth LE support
- Landscape display (AAOS head unit or tablet)

## License

All rights reserved.
