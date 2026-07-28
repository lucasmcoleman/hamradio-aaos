# Codebase Audit — July 2026

Full audit of the Benshi AAOS radio-control app: protocol layer, transports,
controller, ViewModel, and all Compose screens. This document lists what was
found, what was fixed on which branch, and what remains as recommendations.

Supersedes `REVIEW.md` (the earlier Opus review — every item in it had already
been addressed except where noted below).

---

## Branches

| Branch | Contents |
|---|---|
| `claude/benshi-radio-audit-rpz6ot` | This audit + all correctness/bug fixes (low risk) |
| `claude/benshi-transport-switching` | Runtime transport switching — architectural change (stacked on the audit branch) |

---

## Bugs fixed on `claude/benshi-radio-audit-rpz6ot`

### Protocol layer

1. **CTCSS tone truncation** (`DataModels.kt`)
   `(hz * 100).toInt()` truncates: `79.7f * 100` is `7969.9997` in float math,
   so tones 69.3, 79.7, 141.3, 254.1 (and others) were written to the radio
   one centihertz low. A repeater expecting 79.7 would be sent 79.69.
   Fixed with `roundToInt()`; regression test covers all 50 standard tones.

2. **GAIA checksum never verified** (`BenshiMessage.kt`)
   Frames with the checksum flag were accepted without validating the
   checksum, so corrupt RFCOMM bytes could parse as valid messages. Decode
   now verifies and consumes-but-discards corrupt frames. Also, when a
   garbage buffer contains no sync byte at all, decode now discards the whole
   buffer instead of retaining one garbage byte forever.

### RadioController

3. **Error replies parsed as data.** A reply with a non-SUCCESS status still
   fell through to the payload decoders, so an error reply to READ_SETTINGS
   could overwrite good state with garbage. Now returns early.

4. **Outbound commands never appeared in the Debug Console.** `appendLog` was
   only called for inbound messages; the `OUT` direction was dead code. Sent
   commands are now logged, so the console shows real TX/RX traffic.

5. **Reconnect watcher stacking.** Every `connect()` call (e.g. the Retry
   button) spawned a new never-ending state-collector coroutine. Now guarded
   to a single watcher.

6. **`setScan(enable)` ignored its argument.** It always sent the
   PF_TOGGLE_CH_SCAN toggle, so a stale UI switch could invert the radio's
   actual scan state. Now only toggles when radio state ≠ requested state.

7. **Channel fetch pacing.** The DevInfo-reply fetch loop and
   `refreshChannels()` sent up to 64 READ_RF_CH commands back-to-back with no
   spacing (the init sequence uses 50 ms). Both now pace at 50 ms and both
   respect MAX_CHANNELS.

### Transports

8. **BLE GATT writes were fire-and-forget** (`BleTransport.kt`). Android BLE
   allows one outstanding write; issuing another before
   `onCharacteristicWrite` fires silently drops it. The init sequence sends a
   burst of ~20 commands, so most would be lost on a real BLE connection.
   The send worker now awaits the write ack (2 s timeout) before the next
   write.

9. **BleTransport could never reconnect.** `disconnect()` closed the send
   channel permanently — any later `connect()` had a dead channel, and
   `send()` would throw. The channel is now recreated on connect.

10. **Unexpected BLE drops reported `DISCONNECTED` instead of `ERROR`**, so
    RadioController's ERROR→CONNECTED reconnect watcher never engaged for
    BLE. Deliberate disconnects still report DISCONNECTED; unexpected drops
    now report ERROR.

11. **RFCOMM auto-reconnect never started when the *initial* connect failed**
    (radio powered off when the car starts). Reconnect now also starts from
    the initial-connect failure path, is guarded against stacking multiple
    loops, and is cancelled by a deliberate `disconnect()`.

### Mock transport

12. **PTT did nothing in mock mode.** The mock parsed the DO_PROG_FUNC body
    as `[buttonId|action, effect]` but the app sends `[0x00, effectType]`
    (the format confirmed working on the DB50-B), so `isInTx` was always set
    false. The mock now treats PTT as a toggle like the real radio, and also
    handles PF_TOGGLE_CH_SCAN.

13. **Mock GPS replies used the wrong wire format.** It emitted 32-bit
    microdegrees, but `RadioPosition.decode` expects 24-bit `deg × 60 × 500`.
    Decoded mock positions were garbage. Now emits the correct format with
    altitude/speed/heading, so smart beaconing is exercisable in mock mode.

### ViewModel / UI

14. **RX audio routing was backwards.** `applyAudioRouting()` set
    `localSpeaker = 2` for "speaker on", but the hardware-tested semantics
    (commit b541f79, and the Settings screen) are 0–1 = ON, 2–3 = OFF.
    Selecting "Vehicle" turned the radio's speaker **on** and vice versa.

15. **PTT toggle mode read stale state.** The `pointerInput(toggleMode)`
    gesture lambda captured `isTransmitting` once at composition; tap-to-stop
    then saw a stale `false` forever. Fixed with `rememberUpdatedState`.

16. **Smart beaconing hammered radio flash.** The interval was rewritten
    (WRITE_BSS + STORE_SETTINGS — a flash write) any time the interpolated
    interval changed, which at varying speed meant potentially every 5 s
    check. Intervals are now quantized to steps (90/180/300/600/900/1800 s)
    and only written when the step changes. Turn-triggered "beacon now" no
    longer fakes it by setting a 10 s interval it never restored — it sends
    `DO_PROG_FUNC(PF_SEND_LOCATION)`, which beacons immediately with no
    settings write.

---

## Fixed on `claude/benshi-transport-switching`

**Transport switching via `Activity.recreate()` never worked.** The transport
is bound in Hilt's `SingletonComponent`, which lives for the entire process —
`recreate()` re-runs the activity but can never re-inject a new transport.
Toggling Mock Radio or picking a BLE device from the scan sheet appeared to
work but silently kept the old transport until the app was force-killed (the
Settings screen even shows a "please restart the app" dialog as a workaround).

The branch replaces the singleton-bound transport with a `TransportManager`
(itself the injected `IRadioTransport`) that owns a swappable inner transport
and re-emits its state/messages. Switching mock↔real or changing device
address now takes effect immediately: `toggleMockMode()` / `connectToDevice()`
call `switchTransport()` and reconnect in place. `restartEvent`, `recreate()`,
and the "Restart Required" dialog are removed.

---

## Known issues NOT fixed (recommendations)

These need hardware testing or product decisions, so they're documented
rather than changed:

1. **TX audio is never streamed.** `RadioAudioChannel.startTransmit()`
   (mic → SBC → RFCOMM) is fully implemented but nothing calls it — PTT only
   sends `PF_MAIN_PTT`, so the radio keys up using its own mic, and the
   "TX Microphone" setting (Radio/Vehicle/Auto) has no effect. Wiring
   `pttDown/pttUp` to `startTransmit/stopTransmit` when the route is
   Vehicle/Auto is the natural next step, but the SBC encoder is hand-rolled
   (see #2) and should be validated against the radio before shipping.

2. **The hand-rolled SBC codec is unverified DSP.** `SbcCodec.kt` implements
   the analysis/synthesis filter banks and a *simplified* loudness bit
   allocator. The bit allocation does not follow the A2DP spec algorithm
   exactly, so frames may not be bit-exact interoperable with the radio's
   decoder; audio could be distorted or rejected. Consider borrowing the
   allocation tables/algorithm from a reference implementation (e.g.
   sbc_analyze in bluez), or moving TX audio to HFP/AGHFP if the radio
   supports it (`keepAghfpLink` exists in settings). Also note the codec's
   filter buffers are shared object state — fine for one encode + one decode
   stream, not safe for anything more.

3. **BLE fragmentation is unsolvable as-written.** Raw Benshi-over-BLE has no
   length prefix, so if a message ever spans two GATT indications the
   receive path cannot know. Current code decodes each indication greedily.
   With MTU 512 negotiated this in practice never fragments, but if large
   payloads appear (channel bulk reads), consider the GAIA framing over BLE
   too (benlink uses complete frames per indication).

4. **`MainActivity.onDestroy` + `MainViewModel.onCleared` both disconnect a
   process-wide singleton controller.** Harmless today (single activity), but
   it means any future second activity/entry point (e.g. a car app service,
   media session) would fight over the connection. Longer term the radio
   connection belongs in a foreground `Service`, which would also keep the
   radio link (and APRS RX) alive when the app is backgrounded — on AAOS this
   matters if the user switches to Maps.

5. **`HtStatus.channelId` upper-nibble decoding is unverified.** The
   `chUpper` extraction from byte 3 matches the mock, but I could not verify
   against benlink for radios with >15 channels. If channel 16+ ever shows as
   0–15 in the status bar, this is where to look.

6. **APRS receive exists but has no UI.** `aprsPackets` collects decoded
   AX.25 packets (capped at 50) and nothing displays them. A received-packets
   list on the APRS screen (callsign, info, distance/bearing when GPS is
   locked) would make the TNC work visible.

7. **`SET_VOLUME` clamps to 0–15 in `RadioCommands.setVolume` but
   `VolumeSlider`'s range should be confirmed** against `DeviceInfo` — some
   Benshi radios report max volume; currently hardcoded.

8. **Play publishing config** (`com.github.triplet.play`) is applied
   conditionally on a key file — fine, but `versionCode 29 / versionName
   1.2.1` are manually maintained. Consider deriving from git tags.

9. **No CI.** A GitHub Actions workflow running `gradlew test` +
   `assembleDebug` on PRs would have caught several of the above (the repo
   also doesn't commit `gradle-wrapper.jar`, which CI would need — add it
   with `gradle wrapper`).

---

## What's solid (verified during audit)

- `BenshiMessage` encode/decode, byte helpers, reply-flag handling — correct,
  well tested.
- `RadioSettings` bit-packing: all 40+ fields tally to exactly 12 bytes
  before the VFO words; split-nibble channel A/B assembly is correct
  (round-trip test added).
- `BssSettings.patchRawData` correctly preserves unknown bits (e.g. bit 0 of
  byte 1, low nibble of byte 2) from the radio's original response.
- RFCOMM GAIA frame reassembly loop (partial frames, resync) is correct.
- TX=red / RX=green is enforced via `Color.kt` single source of truth
  everywhere; no stray hex colors.
- Compose screens follow the null-check-without-early-return pattern from
  CLAUDE.md; nav bar is statically 4 tabs; `collectAsStateWithLifecycle`
  used throughout.
- Settings write debouncing (gen-counter + 500 ms) works as documented.
