package com.hamradio.aaos.radio.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AprsPacketTest {

    /** AX.25 address field: 6 chars shifted left one bit + SSID byte. */
    private fun ax25Address(callsign: String, ssid: Int, last: Boolean): ByteArray {
        val out = ByteArray(7)
        val padded = callsign.padEnd(6, ' ')
        for (i in 0 until 6) out[i] = ((padded[i].code and 0x7F) shl 1).toByte()
        out[6] = (((ssid and 0x0F) shl 1) or 0x60 or (if (last) 1 else 0)).toByte()
        return out
    }

    private fun buildFrame(
        src: String, srcSsid: Int, info: String,
        digis: List<Pair<String, Int>> = emptyList(),
    ): ByteArray {
        var frame = ax25Address("APRS", 0, last = false) +
            ax25Address(src, srcSsid, last = digis.isEmpty())
        digis.forEachIndexed { i, (call, ssid) ->
            frame = frame + ax25Address(call, ssid, last = i == digis.size - 1)
        }
        return frame + byteArrayOf(0x03, 0xF0.toByte()) + info.toByteArray(Charsets.US_ASCII)
    }

    @Test
    fun `decode parses source destination and info`() {
        val frame = buildFrame("W1AW", 9, "!3746.49N/12225.16W>Test mobile")
        val p = AprsPacket.decode(frame)
        assertNotNull(p)
        assertEquals("W1AW", p!!.source)
        assertEquals(9, p.sourceSsid)
        assertEquals("APRS", p.destination)
        assertEquals("W1AW-9", p.displayCallsign)
        assertTrue(p.info.startsWith("!3746.49N"))
    }

    @Test
    fun `decode parses uncompressed position`() {
        val frame = buildFrame("N0CALL", 0, "!3746.49N/12225.16W>Test")
        val p = AprsPacket.decode(frame)!!
        assertNotNull(p.latitude)
        assertNotNull(p.longitude)
        assertEquals(37.0 + 46.49 / 60.0, p.latitude!!, 0.0001)
        assertEquals(-(122.0 + 25.16 / 60.0), p.longitude!!, 0.0001)
    }

    @Test
    fun `decode parses digipeater path`() {
        val frame = buildFrame(
            "KI4ZZZ", 7, "!3746.49N/12225.16W>Via digi",
            digis = listOf("WIDE1" to 1, "WIDE2" to 1),
        )
        val p = AprsPacket.decode(frame)!!
        assertEquals("KI4ZZZ", p.source)
        assertEquals(listOf("WIDE1-1", "WIDE2-1"), p.digipeaters)
    }

    @Test
    fun `decode handles packet without position`() {
        val frame = buildFrame("W1AW", 1, ">Status only, no position here")
        val p = AprsPacket.decode(frame)!!
        assertNull(p.latitude)
        assertNull(p.longitude)
        assertEquals(">Status only, no position here", p.info)
    }

    @Test
    fun `decode returns null for garbage`() {
        assertNull(AprsPacket.decode(ByteArray(5)))
    }

    @Test
    fun `TncDataFragment single final fragment round-trips`() {
        val payload = "test payload".toByteArray()
        val f = TncDataFragment.decode(byteArrayOf(0x80.toByte()) + payload)!!
        assertTrue(f.isFinal)
        assertEquals(0, f.fragmentId)
        assertEquals(String(payload), String(f.payload))
        assertNull(f.channelId)
    }
}
