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

    @Test
    fun `SubAudio CTCSS tones with inexact float representations round correctly`() {
        // 79.7f * 100 = 7969.9997 in float math — must round to 7970, not truncate to 7969
        assertEquals(7970,  SubAudio.Ctcss(79.7f).toRaw())
        assertEquals(6930,  SubAudio.Ctcss(69.3f).toRaw())
        assertEquals(14130, SubAudio.Ctcss(141.3f).toRaw())
        assertEquals(25410, SubAudio.Ctcss(254.1f).toRaw())
        // Every standard tone must survive an encode/decode round-trip
        val standardTones = listOf(
            67.0f, 69.3f, 71.9f, 74.4f, 77.0f, 79.7f, 82.5f, 85.4f, 88.5f, 91.5f,
            94.8f, 97.4f, 100.0f, 103.5f, 107.2f, 110.9f, 114.8f, 118.8f, 123.0f,
            127.3f, 131.8f, 136.5f, 141.3f, 146.2f, 151.4f, 156.7f, 159.8f, 162.2f,
            165.5f, 167.9f, 171.3f, 173.8f, 177.3f, 179.9f, 183.5f, 186.2f, 189.9f,
            192.8f, 196.6f, 199.5f, 203.5f, 206.5f, 210.7f, 218.1f, 225.7f, 229.1f,
            233.6f, 241.8f, 250.3f, 254.1f,
        )
        for (hz in standardTones) {
            val decoded = SubAudio.Ctcss(hz).toRaw().toSubAudio() as SubAudio.Ctcss
            assertEquals("tone $hz", hz, decoded.hz, 0.001f)
        }
    }

    // -----------------------------------------------------------------------
    // RadioPosition
    // -----------------------------------------------------------------------

    @Test
    fun `RadioPosition decodes 24-bit lat lon and extended fields`() {
        val latRaw = (37.7749 * 60 * 500).toInt()
        val lonRaw = (-122.4194 * 60 * 500).toInt() and 0xFFFFFF
        val bytes = ByteArray(18)
        bytes[0] = (latRaw shr 16).toByte(); bytes[1] = (latRaw shr 8).toByte(); bytes[2] = latRaw.toByte()
        bytes[3] = (lonRaw shr 16).toByte(); bytes[4] = (lonRaw shr 8).toByte(); bytes[5] = lonRaw.toByte()
        putShort(bytes, 6, 52)     // altitude
        putShort(bytes, 8, 35)     // speed knots
        putShort(bytes, 10, 270)   // heading

        val pos = RadioPosition.decode(bytes)!!
        assertEquals(37.7749, pos.latitude, 0.001)
        assertEquals(-122.4194, pos.longitude, 0.001)
        assertEquals(52, pos.altitudeMeters)
        assertEquals(35, pos.speedKnots)
        assertEquals(270, pos.headingDegrees)
        assertEquals(true, pos.locked)
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
    // RadioSettings
    // -----------------------------------------------------------------------

    @Test
    fun `RadioSettings encode then decode round-trips including split-nibble channels`() {
        val s = RadioSettings(
            channelA = 18,        // > 15: exercises the split upper/lower nibble encoding
            channelB = 33,
            scan = true,
            doubleChannel = 2,
            squelchLevel = 6,
            micGain = 5,
            localSpeaker = 2,
            btMicGain = 3,
            vfoX = 1,
            vfo1TxPower = 2,
            vfo1ModFreqHz = 146_520_000L,
            vfo2ModFreqHz = 446_000_000L,
        )
        val d = RadioSettings.decode(s.patchRawData())!!
        assertEquals(18, d.channelA)
        assertEquals(33, d.channelB)
        assertEquals(true, d.scan)
        assertEquals(2, d.doubleChannel)
        assertEquals(6, d.squelchLevel)
        assertEquals(5, d.micGain)
        assertEquals(2, d.localSpeaker)
        assertEquals(3, d.btMicGain)
        assertEquals(1, d.vfoX)
        assertEquals(2, d.vfo1TxPower)
        assertEquals(146_520_000L, d.vfo1ModFreqHz)
        assertEquals(446_000_000L, d.vfo2ModFreqHz)
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
