# Privacy Policy — Benshi Radio Control

**Last updated:** March 27, 2026

## Overview

Benshi Radio Control is a ham radio control application that connects to Benshi-protocol radios via Bluetooth. This app is designed for licensed amateur radio operators.

## Data Collection

**This app does not collect, store, or transmit any personal data to external servers.** There are no analytics, tracking, advertising, or telemetry of any kind.

## Permissions

The app requests the following device permissions, used solely for local radio communication:

| Permission | Purpose |
|------------|---------|
| **Bluetooth** (BLUETOOTH, BLUETOOTH_ADMIN, BLUETOOTH_SCAN, BLUETOOTH_CONNECT) | Discover and connect to your Benshi-protocol radio via Bluetooth Classic (RFCOMM) |
| **Microphone** (RECORD_AUDIO) | Capture voice audio for push-to-talk (PTT) transmission through the connected radio. Audio is encoded and sent directly to the radio over Bluetooth — it is never recorded, stored, or transmitted elsewhere. |
| **Location** (ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION) | Required by Android for Bluetooth device scanning. Also used to provide GPS coordinates to the radio for APRS position beaconing, if enabled by the user. Location data is sent only to the connected radio. |

## Data Storage

All app settings (device address, preferences) are stored locally on your device using Android SharedPreferences. No data is sent to any server, cloud service, or third party.

## APRS

If you enable APRS beaconing, your callsign and GPS position are transmitted over amateur radio frequencies by your radio hardware. This is standard amateur radio operation and is governed by your amateur radio license and applicable regulations — not by this app.

## Third-Party Services

This app does not use any third-party services, SDKs, or libraries that collect user data.

## Children's Privacy

This app is not directed at children under 13. It is a tool for licensed amateur radio operators.

## Changes

If this privacy policy is updated, the changes will be posted to this URL.

## Contact

For questions about this privacy policy, open an issue at [github.com/lucasmcoleman/hamradio-aaos](https://github.com/lucasmcoleman/hamradio-aaos/issues).
