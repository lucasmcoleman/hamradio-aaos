"""Bench test for the ESP32 bridge (job #1140): pair with the radio, then ask it for device info.

  bench.py COM5            uses the board on COM5 (run `pio run -t upload` first)

Passes when the radio answers GET_DEV_INFO (group 2, command 4) with a reply (0x8004)
through the bridge's control channel. Needs pyserial (bundled with PlatformIO's penv).
"""
import sys
import time

import serial

GET_DEV_INFO = "FF01000000020004"  # GAIA header, no body: group BASIC (2), command 4


def wait_for(port, needles, timeout):
    end = time.time() + timeout
    while time.time() < end:
        line = port.readline().decode("utf-8", "replace").strip()
        if line:
            print("  <", line)
        for n in needles:
            if n in line:
                return line
    return None


def main():
    port = serial.Serial(sys.argv[1], 115200, timeout=1)
    time.sleep(0.5)
    port.write(b"status\n")
    wait_for(port, ["tcp 8702"], 5)
    port.write(b"connect\n")
    if not wait_for(port, ["bt open ctl ok"], 60):
        print("FAIL: control channel never opened")
        return 1
    port.write(f"tx {GET_DEV_INFO}\n".encode())
    reply = wait_for(port, ["rx ctl FF01"], 10)
    if reply and "00028004" in reply.replace(" ", ""):
        print("PASS: radio answered GET_DEV_INFO through the bridge")
        return 0
    print("FAIL: no GET_DEV_INFO reply")
    return 1


if __name__ == "__main__":
    sys.exit(main())
