# DB50-B / Benshi Radio Protocol Research

## Summary

The Radioddity DB50-B uses the **Benshi protocol** — a fully reverse-engineered,
open-source documented Bluetooth protocol shared across a family of radios from
Benshi/Vero/RadioOddity/BTech. There is no need to reverse engineer the APK;
the community has already done it and published working libraries.

---

## Hardware Platform

The DB50-B is an OEM variant of the **Vero VR-N7600** platform. All radios in this
family run the same firmware and speak the same protocol:

| Radio | Manufacturer | Status |
|---|---|---|
| Radioddity GA-5WB | RadioOddity | Fully tested |
| BTech UV-Pro | BTech | Fully tested |
| Vero VR-N76 | Vero/VGC | Tested |
| Vero VR-N7500 | Vero/VGC | Tested |
| Vero VR-N7600 | Vero/VGC | Tested |
| Radioddity DB50-B | RadioOddity | Same platform, should work |
| BTech GMRS-Pro | BTech | Untested |

---

## Open Source Libraries (Use These)

### 1. `benlink` (Python) — Primary reference
- **Repo:** https://github.com/khusmann/benlink
- **Author:** Kyle Husmann, KC3SLD
- **Language:** Python (async)
- **Transport:** BLE (via `bleak`) and Bluetooth Classic RFCOMM
- **PyPI:** `pip install benlink`
- **Docs:** https://benlink.kylehusmann.com/

This is the canonical reverse-engineering reference. The btsnoop capture files
used to derive the protocol are also committed to the repo under `btsnoop/`.

### 2. `HtStation` (Node.js)
- **Repo:** https://github.com/Ylianst/HtStation
- **Language:** JavaScript / Node.js
- **Transport:** Bluetooth Classic RFCOMM (via `bluetooth-serial-port`)
- **npm:** `npm install htstation`

Full base station implementation for Raspberry Pi. Includes BBS, WinLink,
APRS, and Home Assistant integration.

### 3. `flutter_benlink` (Dart/Flutter)
- **Repo:** https://github.com/SarahRoseLives/flutter_benlink
- **Language:** Dart / Flutter

Mobile-ready implementation if you want a Flutter Android app.

---

## Transport Layers

The radio exposes **two** independent Bluetooth transports:

### BLE (Bluetooth Low Energy)
Used for programming, settings, and control commands.

| Role | UUID |
|---|---|
| Service | `00001100-d102-11e1-9b23-00025b00a5a5` |
| Write characteristic | `00001101-d102-11e1-9b23-00025b00a5a5` |
| Indicate characteristic | `00001102-d102-11e1-9b23-00025b00a5a5` |

- Write commands to the **write** characteristic (with response)
- Receive replies/notifications via **indicate** on the indicate characteristic
- Over BLE, messages are sent **raw** (no GAIA framing)

### Bluetooth Classic RFCOMM (SPP)
Used for the same command protocol but over a serial port profile.

- Connect to the SPP service channel (discovered via `findSerialPortChannel`)
- Messages are wrapped in **GAIA frames** (see below)
- Also used for KISS TNC (APRS/packet data) on a separate channel/UUID

### KISS TNC (APRS / Packet)
- Standard KISS protocol over BT Classic
- Fully documented open standard — see http://www.ax25.net/kiss.aspx
- Works with APRSdroid, Xastir, Direwolf, etc.

---

## GAIA Frame Format (RFCOMM only)

When using Bluetooth Classic RFCOMM, each message is wrapped in a GAIA frame:

```
Byte 0:   0xFF          (sync / start byte)
Byte 1:   0x01          (version)
Byte 2:   flags         (bit 0 = checksum present)
Byte 3:   payload_len   (number of bytes AFTER the 4-byte command header)
Bytes 4+: [command_group(2)] [is_reply(1)] [command(15)] [body...]
          ← 4 bytes header, not counted in payload_len →
[Last]:   checksum      (optional, XOR of bytes 1..N-1, if flags bit 0 set)
```

Over BLE, the GAIA wrapper is omitted — just send the raw message bytes.

---

## Message Structure

Every command/reply follows this layout (the "data" inside the GAIA frame,
or the raw BLE payload):

```
Bits 0-15:  command_group   (16 bits) — BASIC=2, EXTENDED=10
Bit 16:     is_reply        (1 bit)   — 0=command, 1=reply
Bits 17-31: command         (15 bits) — command ID (see below)
Bits 32+:   body            (variable) — command-specific payload
```

---

## Command Groups

### BASIC (group = 2)

| ID | Name | Description |
|---|---|---|
| 1 | GET_DEV_ID | Get device identifier |
| 2 | SET_REG_TIMES | Set registration times |
| 3 | GET_REG_TIMES | Get registration times |
| 4 | GET_DEV_INFO | Get device info (vendor, product, HW/SW version, capabilities) |
| 5 | READ_STATUS | Read power/battery status |
| 6 | REGISTER_NOTIFICATION | Subscribe to async events |
| 7 | CANCEL_NOTIFICATION | Unsubscribe from events |
| 8 | GET_NOTIFICATION | Poll notification |
| 9 | EVENT_NOTIFICATION | Async event from radio |
| 10 | READ_SETTINGS | Read general settings |
| 11 | WRITE_SETTINGS | Write general settings |
| 12 | STORE_SETTINGS | Persist settings to flash |
| 13 | READ_RF_CH | Read channel config by ID |
| 14 | WRITE_RF_CH | Write channel config |
| 15 | GET_IN_SCAN | Get scan state |
| 16 | SET_IN_SCAN | Set scan state |
| 17 | SET_REMOTE_DEVICE_ADDR | Set paired BT address |
| 18 | GET_TRUSTED_DEVICE | Get trusted device list |
| 19 | DEL_TRUSTED_DEVICE | Delete trusted device |
| 20 | GET_HT_STATUS | Get radio transceiver status (TX/RX/squelch/channel/GPS) |
| 21 | SET_HT_ON_OFF | Power radio on/off |
| 22 | GET_VOLUME | Get speaker volume |
| 23 | SET_VOLUME | Set speaker volume |
| 24 | RADIO_GET_STATUS | Get FM radio status |
| 25 | RADIO_SET_MODE | Set FM radio mode |
| 26 | RADIO_SEEK_UP | FM radio seek up |
| 27 | RADIO_SEEK_DOWN | FM radio seek down |
| 28 | RADIO_SET_FREQ | Set FM radio frequency |
| 29 | READ_ADVANCED_SETTINGS | Read advanced settings |
| 30 | WRITE_ADVANCED_SETTINGS | Write advanced settings |
| 31 | HT_SEND_DATA | Send TNC data fragment |
| 32 | SET_POSITION | Set GPS position |
| 33 | READ_BSS_SETTINGS | Read BSS/APRS settings |
| 34 | WRITE_BSS_SETTINGS | Write BSS/APRS settings |
| 35 | FREQ_MODE_SET_PAR | VFO mode set parameters |
| 36 | FREQ_MODE_GET_STATUS | VFO mode get status |
| 37 | READ_RDA1846S_AGC | Read AGC register |
| 38 | WRITE_RDA1846S_AGC | Write AGC register |
| 39 | READ_FREQ_RANGE | Read frequency range |
| 40 | WRITE_DE_EMPH_COEFFS | Write de-emphasis coefficients |
| 41 | STOP_RINGING | Stop alert tone |
| 42 | SET_TX_TIME_LIMIT | Set transmit time limit |
| 43 | SET_IS_DIGITAL_SIGNAL | Set digital signal mode |
| 44 | SET_HL | Set high/low power |
| 45 | SET_DID | Set device ID |
| 46 | SET_IBA | Set IBA |
| 47 | GET_IBA | Get IBA |
| 48 | SET_TRUSTED_DEVICE_NAME | Set trusted device name |
| 49 | SET_VOC | Set VOX |
| 50 | GET_VOC | Get VOX |
| 51 | SET_PHONE_STATUS | Set phone status |
| 52 | READ_RF_STATUS | Read RF status |
| 53 | PLAY_TONE | Play audio tone |
| 54 | GET_DID | Get device ID |
| 55 | GET_PF | Get programmable function buttons |
| 56 | SET_PF | Set programmable function buttons |
| 57 | RX_DATA | Receive TNC data |
| 58 | WRITE_REGION_CH | Write region channel |
| 59 | WRITE_REGION_NAME | Write region name |
| 60 | SET_REGION | Set active region |
| 61 | SET_PP_ID | Set PP ID |
| 62 | GET_PP_ID | Get PP ID |
| 63 | READ_ADVANCED_SETTINGS2 | Read advanced settings (page 2) |
| 64 | WRITE_ADVANCED_SETTINGS2 | Write advanced settings (page 2) |
| 65 | UNLOCK | Unlock radio |
| 66 | DO_PROG_FUNC | Execute programmed function |
| 67 | SET_MSG | Set message |
| 68 | GET_MSG | Get message |
| 69 | BLE_CONN_PARAM | Set BLE connection parameters |
| 70 | SET_TIME | Set clock |
| 71 | SET_APRS_PATH | Set APRS path |
| 72 | GET_APRS_PATH | Get APRS path |
| 73 | READ_REGION_NAME | Read region name |
| 74 | SET_DEV_ID | Set device ID |
| 75 | GET_PF_ACTIONS | Get PF button actions |
| 76 | GET_POSITION | Get GPS position |

### EXTENDED (group = 10)

| ID | Name |
|---|---|
| 769 | GET_BT_SIGNAL |
| 1825 | DEV_REGISTRATION |
| 16387 | GET_DEV_STATE_VAR |

---

## Key Data Structures

### RF Channel (READ_RF_CH / WRITE_RF_CH body)

```
channel_id:       8 bits  — channel index
tx_mod:           2 bits  — 0=FM, 1=AM, 2=DMR
tx_freq:          30 bits — frequency in 100Hz units (÷1e6 = MHz)
rx_mod:           2 bits  — 0=FM, 1=AM, 2=DMR
rx_freq:          30 bits — frequency in 100Hz units
tx_sub_audio:     16 bits — 0=none, 1-6699=DCS code, 6700-25410=CTCSS×100
rx_sub_audio:     16 bits — same encoding
scan:             1 bit
tx_at_max_power:  1 bit
talk_around:      1 bit
bandwidth:        1 bit   — 0=narrow, 1=wide
pre_de_emph_bypass: 1 bit
sign:             1 bit
tx_at_med_power:  1 bit
tx_disable:       1 bit
fixed_freq:       1 bit
fixed_bandwidth:  1 bit
fixed_tx_power:   1 bit
mute:             1 bit
_pad:             4 bits (0)
name_str:         80 bits (10 ASCII chars)
```

For DMR channels, append: `tx_color(4) rx_color(4) slot(1) _pad(7)`

### HT Status (GET_HT_STATUS reply body)

```
is_power_on:      bit 7 of byte 0
is_in_tx:         bit 6
is_sq:            bit 5 (squelch open)
is_in_rx:         bit 4
double_channel:   bits 3-2
is_scan:          bit 1
is_radio:         bit 0 (FM radio mode)
is_gps_locked:    bit 3 of byte 1
is_hfp_connected: bit 2 (phone call)
is_aoc_connected: bit 1
channel_id:       upper nibble of byte 1 (lower) + bits from byte 2 (upper)
rssi:             upper nibble of byte 2
curr_region:      lower nibble of byte 2 + upper 2 bits of byte 3
```

### Notifications (REGISTER_NOTIFICATION)

Subscribe with `REGISTER_NOTIFICATION` to receive async events:

| ID | Event |
|---|---|
| 1 | HT_STATUS_CHANGED |
| 2 | DATA_RXD (TNC packet received) |
| 3 | NEW_INQUIRY_DATA |
| 4 | RESTORE_FACTORY_SETTINGS |
| 5 | HT_CH_CHANGED |
| 6 | HT_SETTINGS_CHANGED |
| 7 | RINGING_STOPPED |
| 8 | RADIO_STATUS_CHANGED |
| 9 | USER_ACTION |
| 10 | SYSTEM_EVENT |
| 11 | BSS_SETTINGS_CHANGED |
| 12 | DATA_TXD (TNC packet transmitted) |
| 13 | POSITION_CHANGE |

### TNC Data Fragmentation (HT_SEND_DATA)

Large packets are split into fragments (max MTU ~50 bytes):

```
is_final_fragment: 1 bit
with_channel_id:   1 bit
fragment_id:       6 bits
data:              variable (payload bytes, less 1 byte if channel_id present)
channel_id:        8 bits (only if with_channel_id=1)
```

---

## Reply Status Codes

All write/set commands return a `reply_status` byte:

| Value | Meaning |
|---|---|
| 0 | SUCCESS |
| 1 | NOT_SUPPORTED |
| 2 | NOT_AUTHENTICATED |
| 3 | INSUFFICIENT_RESOURCES |
| 4 | AUTHENTICATING |
| 5 | INVALID_PARAMETER |
| 6 | INCORRECT_STATE |
| 7 | IN_PROGRESS |

---

## Quick Start (Python)

```python
pip install benlink
```

```python
import asyncio
from benlink.controller import RadioController

async def main():
    # BLE connection (use device UUID / MAC)
    async with RadioController.new_ble("XX:XX:XX:XX:XX:XX") as radio:
        print(radio.device_info)
        print(radio.settings)

        # Read channel 0
        ch = await radio.get_rf_ch(0)
        print(ch)

        # Change channel A to channel 5
        await radio.write_settings(channel_a=5)

asyncio.run(main())
```

---

## Android App Architecture (for this project)

For the AAOS (Android Automotive OS) app:

1. **BLE transport** — use Android `BluetoothLeGatt` or Kotlin `bleak`-equivalent
   - Service UUID: `00001100-d102-11e1-9b23-00025b00a5a5`
   - Write: `00001101-d102-11e1-9b23-00025b00a5a5`
   - Notify/Indicate: `00001102-d102-11e1-9b23-00025b00a5a5`

2. **Message encoding** — raw bytes (no GAIA wrapper for BLE):
   - `command_group` (16 bits big-endian)
   - `is_reply` + `command` (packed into 16 bits)
   - body payload

3. **RFCOMM** — if using classic BT:
   - Wrap in GAIA frame: `[0xFF][0x01][0x00][payload_len][data]`

4. **KISS TNC** — APRS/packet:
   - Standard KISS over RFCOMM, separate channel from command protocol
   - Use existing KISS libraries (e.g., `net.ab0oo.aprs.parser`)

---

## Source Material

- **benlink Python library:** https://github.com/khusmann/benlink
  - Original reverse-engineering via btsnoop HCI captures
  - btsnoop input files committed under `btsnoop/input/`
- **HtStation Node.js:** https://github.com/Ylianst/HtStation
- **flutter_benlink:** https://github.com/SarahRoseLives/flutter_benlink
- **HTCommander (C#):** https://github.com/Ylianst/HTCommander
- **GAIA frame spec:** https://slideplayer.com/slide/12945885/
- **KISS TNC spec:** http://www.ax25.net/kiss.aspx
