"""Client for the hamradio app's Diagnostics link (job #875, TLS since codex review #941).

    python diag.py <relay-host[:port]> <truck-fingerprint> "info" "uuids 38:D2:00:01:68:A8" ...

<truck-fingerprint> is the SHA-256 "Key" shown on the head unit (colons optional). The
connection is refused unless the truck's certificate matches it exactly. The PC proves
itself with the client key in %USERPROFILE%\\.hamradio-diag (never copied anywhere else).
Each command's output is printed and appended to diag-<host>.log next to this script.
Options: --no-cert (test that the truck refuses a client without the PC certificate).
"""
import hashlib
import os
import re
import socket
import ssl
import sys
import time
from pathlib import Path

END = "<<END>>"
KEY_DIR = Path(os.environ["USERPROFILE"]) / ".hamradio-diag"


def read_block(f):
    lines = []
    for line in f:
        line = line.rstrip("\r\n")
        if line == END:
            return "\n".join(lines)
        lines.append(line)
    raise ConnectionError("link closed")


def main():
    args = sys.argv[1:]
    no_cert = "--no-cert" in args
    args = [a for a in args if a != "--no-cert"]
    host, _, port = args[0].partition(":")
    port = int(port or 47392)   # relay PC side; the truck dials out to the relay
    pin = args[1].replace(":", "").lower()
    if len(pin) != 64:
        sys.exit("fingerprint must be the 32-byte SHA-256 shown on the head unit")

    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE   # the self-signed truck cert is checked by exact pin below
    if not no_cert:
        ctx.load_cert_chain(KEY_DIR / "pc-client.crt", KEY_DIR / "pc-client.key")

    log = Path(__file__).with_name(f"diag-{host}.log")
    with socket.create_connection((host, port), timeout=180) as raw:
        with ctx.wrap_socket(raw) as s:
            got = hashlib.sha256(s.getpeercert(binary_form=True)).hexdigest()
            if got != pin:
                sys.exit(f"truck certificate {got} does not match the pinned fingerprint; nothing sent")
            print(f"TLS {s.version()}, truck key pinned. Tap Allow on the head unit...")
            f = s.makefile("r", encoding="utf-8", newline="\n")
            w = s.makefile("w", encoding="utf-8", newline="\n")
            print(read_block(f))
            with log.open("a", encoding="utf-8") as out:
                for cmd in args[2:]:
                    w.write(cmd + "\n")
                    w.flush()
                    text = read_block(f)
                    stamp = time.strftime("%Y-%m-%d %H:%M:%S")
                    shown = re.sub(r"pin:[0-9]+", "pin:****", cmd)   # never print or keep a PIN
                    print(f"> {shown}\n{text}\n")
                    out.write(f"[{stamp}] > {shown}\n{text}\n\n")


if __name__ == "__main__":
    main()
