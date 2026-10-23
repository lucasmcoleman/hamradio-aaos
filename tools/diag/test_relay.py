"""Tests for relay.py (codex review #945).  Run: python -m unittest tools/diag/test_relay.py"""
import socket
import subprocess
import sys
import time
import unittest
from pathlib import Path

RELAY = Path(__file__).with_name("relay.py")


def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class RelayTest(unittest.TestCase):
    def setUp(self):
        self.truck_port, self.pc_port = free_port(), free_port()
        self.proc = subprocess.Popen(
            [sys.executable, "-u", str(RELAY), "--bind", "127.0.0.1",
             "--truck-port", str(self.truck_port), "--pc-port", str(self.pc_port),
             "--write-timeout", "2"],
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        deadline = time.time() + 10
        while time.time() < deadline:
            try:
                socket.create_connection(("127.0.0.1", self.pc_port), timeout=1).close()
                break
            except OSError:
                time.sleep(0.1)
        self.socks = []

    def tearDown(self):
        for s in self.socks:
            s.close()
        self.proc.kill()
        self.proc.wait()

    def connect(self, port):
        s = socket.create_connection(("127.0.0.1", port), timeout=5)
        self.socks.append(s)
        return s

    def pair(self):
        truck = self.connect(self.truck_port)
        time.sleep(0.3)
        pc = self.connect(self.pc_port)
        self.assertEqual(truck.recv(1), b"\x01", "truck gets GO once the PC is there")
        return truck, pc

    def test_pipes_both_ways(self):
        truck, pc = self.pair()
        pc.sendall(b"hello")
        self.assertEqual(truck.recv(5), b"hello")
        truck.sendall(b"back")
        self.assertEqual(pc.recv(4), b"back")

    def test_no_truck_means_pc_is_closed(self):
        pc = self.connect(self.pc_port)
        self.assertEqual(pc.recv(1), b"")

    def test_stalled_reader_releases_pair_and_next_pair_works(self):
        truck, pc = self.pair()
        truck.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 4096)
        pc.settimeout(15)
        chunk = b"x" * 65536
        sent_until_blocked = 0
        try:
            # The truck never reads, so the relay's forwarding write stalls; it must give up
            # after --write-timeout and close the PC side too.
            for _ in range(2000):
                pc.sendall(chunk)
                sent_until_blocked += 1
        except OSError:
            pass
        self.assertLess(sent_until_blocked, 2000, "relay should have dropped the stalled pair")
        time.sleep(1)
        truck2, pc2 = self.pair()          # busy state was released
        pc2.sendall(b"ok")
        self.assertEqual(truck2.recv(2), b"ok")

    def test_dead_waiting_truck_is_skipped(self):
        truck = self.connect(self.truck_port)
        time.sleep(0.3)
        truck.close()                      # app turned the link off
        time.sleep(0.3)
        pc = self.connect(self.pc_port)
        self.assertEqual(pc.recv(1), b"", "PC is not paired with a dead truck socket")


if __name__ == "__main__":
    unittest.main()
