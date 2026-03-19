package com.hamradio.aaos.radio.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BenshiMessageTest {

    @Test
    fun `encode then decode round-trips cleanly`() {
        val original = BenshiMessage(
            commandGroup = CommandGroup.BASIC,
            command      = BasicCommand.GET_DEV_INFO,
            isReply      = false,
            body         = byteArrayOf(),
        )
        val decoded = BenshiMessage.decode(original.encode())
        assertNotNull(decoded)
        assertEquals(CommandGroup.BASIC, decoded!!.commandGroup)
        assertEquals(BasicCommand.GET_DEV_INFO, decoded.command)
        assertEquals(false, decoded.isReply)
        assertArrayEquals(byteArrayOf(), decoded.body)
    }

    @Test
    fun `reply flag round-trips`() {
        val msg = BenshiMessage(CommandGroup.BASIC, BasicCommand.GET_HT_STATUS, true, byteArrayOf(0x00, 0xAB.toByte()))
        val decoded = BenshiMessage.decode(msg.encode())!!
        assertTrue(decoded.isReply)
        assertEquals(BasicCommand.GET_HT_STATUS, decoded.command)
        assertArrayEquals(byteArrayOf(0x00, 0xAB.toByte()), decoded.body)
    }

    @Test
    fun `body bytes are preserved exactly`() {
        val body = (0..31).map { it.toByte() }.toByteArray()
        val msg  = BenshiMessage(CommandGroup.BASIC, 42, false, body)
        val decoded = BenshiMessage.decode(msg.encode())!!
        assertArrayEquals(body, decoded.body)
    }

    @Test
    fun `command group EXTENDED encodes correctly`() {
        val msg  = BenshiMessage(CommandGroup.EXTENDED, 769, false, byteArrayOf())
        val raw  = msg.encode()
        assertEquals(CommandGroup.EXTENDED.toByte(), raw[1])  // low byte of group=10
        val decoded = BenshiMessage.decode(raw)!!
        assertEquals(CommandGroup.EXTENDED, decoded.commandGroup)
        assertEquals(769, decoded.command)
    }

    @Test
    fun `decode returns null for short input`() {
        assertEquals(null, BenshiMessage.decode(byteArrayOf(0x00, 0x02)))
    }

    @Test
    fun `RadioCommands setVolume encodes level correctly`() {
        val msg = RadioCommands.setVolume(10)
        val decoded = BenshiMessage.decode(msg.encode())!!
        assertEquals(BasicCommand.SET_VOLUME, decoded.command)
        assertEquals(10, decoded.body[0].toInt() and 0xFF)
    }

    @Test
    fun `RadioCommands readChannel encodes id correctly`() {
        val msg = RadioCommands.readChannel(7)
        val decoded = BenshiMessage.decode(msg.encode())!!
        assertEquals(BasicCommand.READ_RF_CH, decoded.command)
        assertEquals(7, decoded.body[0].toInt() and 0xFF)
    }
}
