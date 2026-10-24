package com.hamradio.aaos.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.util.UUID

class DiagCommandTest {
    private val radio = "38:D2:00:01:68:A8"

    @Test fun parsesFixedCommands() {
        assertEquals(DiagCommand.Info, DiagCommand.parse(" info "))
        assertEquals(DiagCommand.Scan, DiagCommand.parse("scan"))
        assertEquals(DiagCommand.Uuids(radio), DiagCommand.parse("uuids 38:d2:00:01:68:a8"))
        assertEquals(DiagCommand.Log(50), DiagCommand.parse("log 50"))
        assertEquals(DiagCommand.Prop("ro.build.fingerprint"), DiagCommand.parse("prop ro.build.fingerprint"))
    }

    @Test fun parsesRfcommTargets() {
        val spp = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        assertEquals(DiagCommand.Rfcomm(radio, spp, null, false, 1500), DiagCommand.parse("rfcomm $radio 1101 insecure"))
        assertEquals(DiagCommand.Rfcomm(radio, null, 4, true, 0), DiagCommand.parse("rfcomm $radio ch:4 secure 0"))
        val full = "00001203-0000-1000-8000-00805f9b34fb"
        assertEquals(UUID.fromString(full), (DiagCommand.parse("rfcomm $radio $full secure") as DiagCommand.Rfcomm).uuid)
    }

    @Test fun refusesAnythingElse() {
        listOf(
            "", "sh -c id", "prop ro.x;rm", "uuids notamac", "rfcomm $radio ch:99 secure",
            "rfcomm $radio 1101 maybe", "rfcomm $radio 1101 secure 999999", "log 0", "bond $radio extra", "scan 99",
        ).forEach { assertTrue(it, DiagCommand.parse(it) is DiagCommand.Invalid) }
    }

    @Test fun onlyPcOrLoopbackAllowed() {
        assertTrue(DiagCommand.isAllowedPeer(InetAddress.getByName("192.168.0.30"), "192.168.0.30"))
        assertTrue(DiagCommand.isAllowedPeer(InetAddress.getByName("127.0.0.1"), "192.168.0.30"))
        assertFalse(DiagCommand.isAllowedPeer(InetAddress.getByName("192.168.0.31"), "192.168.0.30"))
    }
}
