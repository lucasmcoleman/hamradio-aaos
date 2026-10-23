"""Byte relay for the hamradio Diagnostics link (job #875).

The truck drops inbound connections, so the app dials out to TRUCK_PORT here and Lucas's
PC dials PC_PORT. When both sides are present the relay sends the truck one GO byte (0x01)
and then copies bytes both ways. TLS runs end to end between the app and the PC; the relay
never sees plaintext. One pair at a time.

    python3 relay.py [--truck-net 192.168.0.] [--pc 192.168.0.30] [--bind 0.0.0.0]
"""
import argparse
import selectors
import socket
import sys
import threading
import time

TRUCK_PORT = 47391
PC_PORT = 47392
GO = b"\x01"
IDLE_LIMIT = 35 * 60          # the app turns itself off after 30 min anyway
WRITE_TIMEOUT = 30            # a peer that stops reading ends the pair (codex #945)


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, flush=True)


def alive(s):
    """False if the peer has closed (the waiting truck never sends before GO)."""
    try:
        s.setblocking(False)
        try:
            return s.recv(1, socket.MSG_PEEK) != b""
        except BlockingIOError:
            return True
        finally:
            s.setblocking(True)
    except OSError:
        return False


def pipe(a, b, write_timeout=WRITE_TIMEOUT, idle_limit=IDLE_LIMIT):
    """Copy both ways until either side closes, goes idle, or stops reading.
    Both sockets are always closed on the way out."""
    for s in (a, b):
        s.settimeout(write_timeout)   # bounds sendall; recv only runs after select says ready
    sel = selectors.DefaultSelector()
    sel.register(a, selectors.EVENT_READ, b)
    sel.register(b, selectors.EVENT_READ, a)
    last = time.time()
    try:
        while time.time() - last < idle_limit:
            for key, _ in sel.select(timeout=5):
                data = key.fileobj.recv(65536)
                if not data:
                    return
                key.data.sendall(data)
                last = time.time()
    except OSError:                 # includes socket.timeout from a stalled reader
        pass
    finally:
        sel.close()
        for s in (a, b):
            try:
                s.close()
            except OSError:
                pass


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--truck-net", default="192.168.0.", help="truck must connect from this prefix")
    ap.add_argument("--pc", default="192.168.0.30", help="only this address may use the PC side")
    ap.add_argument("--bind", default="0.0.0.0")
    ap.add_argument("--truck-port", type=int, default=TRUCK_PORT)
    ap.add_argument("--pc-port", type=int, default=PC_PORT)
    ap.add_argument("--write-timeout", type=float, default=WRITE_TIMEOUT)
    args = ap.parse_args()

    truck_srv = socket.create_server((args.bind, args.truck_port))
    pc_srv = socket.create_server((args.bind, args.pc_port))
    lock = threading.Lock()
    state = {"truck": None, "busy": False}

    def accept_truck():
        while True:
            s, (ip, _) = truck_srv.accept()
            if not ip.startswith(args.truck_net) and ip != "127.0.0.1":
                log("truck side refused", ip)
                s.close()
                continue
            with lock:
                if state["busy"]:
                    s.close()
                    continue
                old, state["truck"] = state["truck"], s
            if old:
                old.close()
            log("truck waiting from", ip)

    threading.Thread(target=accept_truck, daemon=True).start()
    log(f"relay up: truck -> :{args.truck_port}, PC -> :{args.pc_port} (PC allowed: {args.pc})")
    while True:
        pc, (ip, _) = pc_srv.accept()
        if ip not in (args.pc, "127.0.0.1"):
            log("PC side refused", ip)
            pc.close()
            continue
        with lock:
            truck, state["truck"] = state["truck"], None
            if truck is not None and not alive(truck):
                truck.close()             # app turned the link off; it left a dead socket
                truck = None
            if truck is None or state["busy"]:
                if truck:
                    state["truck"] = truck
                pc.close()
                log("PC from", ip, "but no truck waiting")
                continue
            state["busy"] = True
        try:
            truck.settimeout(5)
            truck.sendall(GO)
        except OSError:
            truck.close()
            pc.close()
            with lock:
                state["busy"] = False
            log("truck went away before GO")
            continue
        log("paired PC", ip, "with truck")

        def run(a=truck, b=pc):
            try:
                pipe(a, b, write_timeout=args.write_timeout)
            finally:
                with lock:
                    state["busy"] = False
                log("pair ended")

        threading.Thread(target=run, daemon=True).start()


if __name__ == "__main__":
    sys.exit(main())
