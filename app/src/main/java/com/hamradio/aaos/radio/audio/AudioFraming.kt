package com.hamradio.aaos.radio.audio

import java.io.ByteArrayOutputStream

/** Wire escaping shared by real RFCOMM audio and the explicitly local mock loopback. */
internal object AudioFraming {
    fun escape(cmd: Byte, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size * 2 + 4)
        out.write(0x7e)
        out.write(cmd.toInt())
        for (byte in data) {
            val value = byte.toInt() and 0xff
            if (value == 0x7e || value == 0x7d) {
                out.write(0x7d)
                out.write(value xor 0x20)
            } else out.write(value)
        }
        out.write(0x7e)
        return out.toByteArray()
    }

    fun unescape(data: ByteArray, start: Int): ByteArray? {
        if (start !in 0..data.size) return null
        val out = ByteArrayOutputStream(data.size - start)
        var index = start
        while (index < data.size) {
            val value = data[index++].toInt() and 0xff
            if (value == 0x7d) {
                if (index == data.size) return null
                out.write((data[index++].toInt() and 0xff) xor 0x20)
            } else out.write(value)
        }
        return out.toByteArray()
    }

    /** Complete local packet, including delimiters, routed as received command 0x00. */
    fun loopbackPayload(packet: ByteArray): ByteArray? {
        if (packet.size < 4 || packet.first() != 0x7e.toByte() ||
            packet.last() != 0x7e.toByte() || packet[1] != 0.toByte()) return null
        return unescape(packet.copyOfRange(1, packet.lastIndex), 1)
    }
}
