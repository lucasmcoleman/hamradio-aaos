package com.hamradio.aaos.radio.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GaiaFrameTest {

    private val testMsg = BenshiMessage(
        commandGroup = CommandGroup.BASIC,
        command      = BasicCommand.GET_DEV_INFO,
        isReply      = false,
        body         = byteArrayOf(),
    )

    @Test
    fun `encode produces correct sync bytes`() {
        val frame = GaiaFrame.encode(testMsg)
        assertEquals(0xFF.toByte(), frame[0])
        assertEquals(0x01.toByte(), frame[1])
    }

    @Test
    fun `encode without checksum has zero flag byte`() {
        val frame = GaiaFrame.encode(testMsg, withChecksum = false)
        assertEquals(0x00.toByte(), frame[2])
    }

    @Test
    fun `encode then decode round-trips`() {
        val frame = GaiaFrame.encode(testMsg)
        val (consumed, decoded) = GaiaFrame.decode(frame)
        assertNotNull(decoded)
        assertEquals(frame.size, consumed)
        assertEquals(testMsg, decoded!!)
    }

    @Test
    fun `encode with checksum then decode round-trips`() {
        val frame = GaiaFrame.encode(testMsg, withChecksum = true)
        val (consumed, decoded) = GaiaFrame.decode(frame)
        assertNotNull(decoded)
        assertEquals(frame.size, consumed)
        assertEquals(testMsg, decoded!!)
    }

    @Test
    fun `decode returns 0 consumed for incomplete frame`() {
        val frame = GaiaFrame.encode(testMsg)
        val partial = frame.copyOfRange(0, frame.size - 2)
        val (consumed, decoded) = GaiaFrame.decode(partial)
        assertEquals(0, consumed)
        assertNull(decoded)
    }

    @Test
    fun `decode with payload body`() {
        val msgWithBody = BenshiMessage(
            commandGroup = CommandGroup.BASIC,
            command      = BasicCommand.READ_RF_CH,
            isReply      = false,
            body         = byteArrayOf(0x05),
        )
        val frame = GaiaFrame.encode(msgWithBody)
        val (_, decoded) = GaiaFrame.decode(frame)
        assertNotNull(decoded)
        assertArrayEquals(byteArrayOf(0x05), decoded!!.body)
    }

    @Test
    fun `decode skips non-sync bytes and returns negative consumed`() {
        val garbage = byteArrayOf(0x00, 0x12, 0xFF.toByte(), 0x01, 0x00, 0x00,
                                   0x00, 0x02, 0x00, BasicCommand.GET_DEV_INFO.toByte())
        val (consumed, _) = GaiaFrame.decode(garbage)
        // Should return negative = skip 2 bytes to reach 0xFF sync
        assert(consumed < 0)
    }
}
