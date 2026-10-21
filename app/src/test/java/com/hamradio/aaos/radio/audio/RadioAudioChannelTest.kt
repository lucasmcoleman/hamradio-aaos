package com.hamradio.aaos.radio.audio

import android.content.Context
import android.media.AudioTrack
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.io.ByteArrayOutputStream

class RadioAudioChannelTest {
    @Test fun receivedCommandsPlayIdenticalPcmButTransmitEchoDoesNotPlay() = runBlocking {
        val channel = RadioAudioChannel(mockk<Context>(), "unused")
        val track = mockk<AudioTrack>()
        val played = mutableListOf<ShortArray>()
        every { track.write(any<ShortArray>(), any(), any(), AudioTrack.WRITE_NON_BLOCKING) } answers {
            played.add(firstArg<ShortArray>().copyOfRange(secondArg(), secondArg<Int>() + thirdArg<Int>()))
            thirdArg<Int>()
        }
        val encoded = SbcCodecSession().encode(ShortArray(128) { (it * 120 - 7000).toShort() })!!
        val escaped = ByteArrayOutputStream().apply {
            for (byte in encoded) {
                val value = byte.toInt() and 255
                if (value == 0x7d || value == 0x7e) { write(0x7d); write(value xor 0x20) }
                else write(value)
            }
        }.toByteArray()
        try {
            channel.playFrame(byteArrayOf(0) + escaped, track)
            channel.playFrame(byteArrayOf(1), track) // independent RX burst
            channel.playFrame(byteArrayOf(3) + escaped, track)
            channel.playFrame(byteArrayOf(9) + escaped, track)
            channel.playFrame(byteArrayOf(2) + escaped, track)
            verify(exactly = 2) { track.write(any<ShortArray>(), any(), any(), AudioTrack.WRITE_NON_BLOCKING) }
            assertArrayEquals(played[0], played[1])
        } finally { channel.close() }
    }
}
