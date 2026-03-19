package com.hamradio.aaos.radio.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DataModelsTest {

    // -----------------------------------------------------------------------
    // HtStatus
    // -----------------------------------------------------------------------

    @Test
    fun `HtStatus decode parses all flags`() {
        // b0: power=1 tx=1 sq=1 rx=0 dbl=01 scan=1 radio=0  → 0b11101110 = 0xEE
        // b1: chALow=5 gps=1 hfp=0 aoc=0                     → 0b01011000 = 0x58
        val bytes = byteArrayOf(0xEE.toByte(), 0x58.toByte(), 0x60.toByte(), 0x00)
        val s = HtStatus.decode(bytes)!!
        assertEquals(true,  s.isPowerOn)
        assertEquals(true,  s.isInTx)
        assertEquals(true,  s.isSquelchOpen)
        assertEquals(false, s.isInRx)
        assertEquals(true,  s.isScan)
        assertEquals(false, s.isRadioMode)
        assertEquals(true,  s.isGpsLocked)
        assertEquals(6,     s.rssi)  // 0x60 >> 4 = 6
    }

    @Test
    fun `HtStatus decode returns null for empty input`() {
        assertNull(HtStatus.decode(byteArrayOf()))
        assertNull(HtStatus.decode(byteArrayOf(0x80.toByte())))
    }

    @Test
    fun `HtStatus DISCONNECTED sentinel is sane`() {
        assertEquals(false, HtStatus.DISCONNECTED.isPowerOn)
        assertEquals(false, HtStatus.DISCONNECTED.isInTx)
        assertEquals(0,     HtStatus.DISCONNECTED.rssi)
    }

    // -----------------------------------------------------------------------
    // SubAudio
    // -----------------------------------------------------------------------

    @Test
    fun `SubAudio CTCSS round-trips`() {
        val raw  = (100.0f * 100).toInt()  // 10000 → 100.0 Hz
        val sa   = raw.toSubAudio()
        assertNotNull(sa as? SubAudio.Ctcss)
        assertEquals(100.0f, (sa as SubAudio.Ctcss).hz, 0.01f)
        assertEquals(raw, sa.toRaw())
    }

    @Test
    fun `SubAudio DCS round-trips`() {
        val raw  = 23
        val sa   = raw.toSubAudio()
        assertNotNull(sa as? SubAudio.Dcs)
        assertEquals(23, (sa as SubAudio.Dcs).code)
        assertEquals(raw, sa.toRaw())
    }

    @Test
    fun `SubAudio None for 0`() {
        assertEquals(SubAudio.None, 0.toSubAudio())
        assertEquals(0, SubAudio.None.toRaw())
    }

    // -----------------------------------------------------------------------
    // RfChannel encode/decode
    // -----------------------------------------------------------------------

    @Test
    fun `RfChannel encode then decode preserves key fields`() {
        val ch = RfChannel(
            channelId    = 3,
            name         = "HILLTOP",
            txFreqHz     = 147_180_000,
            rxFreqHz     = 146_580_000,
            txSubAudio   = SubAudio.Ctcss(127.3f),
            rxSubAudio   = SubAudio.Ctcss(127.3f),
            scan         = true,
            txAtMaxPower = true,
            bandwidth    = BandwidthType.WIDE,
        )
        val encoded = RfChannel.encode(ch)
        val decoded = RfChannel.decode(encoded)!!

        assertEquals(ch.channelId,  decoded.channelId)
        assertEquals(ch.name,       decoded.name)
        assertEquals(ch.txFreqHz,   decoded.txFreqHz)
        assertEquals(ch.rxFreqHz,   decoded.rxFreqHz)
        assertEquals(true,          decoded.scan)
        assertEquals(true,          decoded.txAtMaxPower)
        assertEquals(BandwidthType.WIDE, decoded.bandwidth)

        // Tone: 127.3 Hz → raw = 12730 → decode back to 127.3
        val tone = decoded.rxSubAudio as? SubAudio.Ctcss
        assertNotNull(tone)
        assertEquals(127.3f, tone!!.hz, 0.1f)
    }

    @Test
    fun `RfChannel decode returns null for short input`() {
        assertNull(RfChannel.decode(ByteArray(10)))
    }

    @Test
    fun `RfChannel simplex channel txFreq equals rxFreq`() {
        val ch = RfChannel(0, "SIMP", 146_520_000, 146_520_000)
        val d  = RfChannel.decode(RfChannel.encode(ch))!!
        assertEquals(d.txFreqHz, d.rxFreqHz)
    }

    // -----------------------------------------------------------------------
    // Byte helpers
    // -----------------------------------------------------------------------

    @Test
    fun `getInt and putInt round-trip`() {
        val buf = ByteArray(4)
        putInt(buf, 0, 0xDEADBEEF.toInt())
        assertEquals(0xDEADBEEF.toInt(), getInt(buf, 0))
    }

    @Test
    fun `getShort and putShort round-trip`() {
        val buf = ByteArray(2)
        putShort(buf, 0, 0xABCD)
        assertEquals(0xABCD, getShort(buf, 0))
    }
}
