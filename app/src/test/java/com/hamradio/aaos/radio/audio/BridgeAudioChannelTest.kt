package com.hamradio.aaos.radio.audio

import android.media.AudioTrack
import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.Collections
import kotlin.concurrent.thread

/** A local ServerSocket stands in for the ESP32 bridge's audio relay port. */
class BridgeAudioChannelTest {
    private lateinit var server: ServerSocket

    @Before fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        server = ServerSocket(0)
    }

    @After fun teardown() {
        server.close()
        unmockkStatic(Log::class)
    }

    /** Bytes the fake bridge reads from the app until the app closes the socket (-1 if it never did). */
    private fun bytesReadUntilClose(socket: java.net.Socket): Int {
        socket.soTimeout = 5_000
        var total = 0
        val buf = ByteArray(256)
        try {
            var n = socket.getInputStream().read(buf)
            while (n >= 0) { total += n; n = socket.getInputStream().read(buf) }
        } catch (_: SocketTimeoutException) { return -1 }
        return total
    }

    private fun capturingTrack(played: MutableList<ShortArray>): AudioTrack = mockk<AudioTrack>(relaxed = true).also { track ->
        every { track.write(any<ShortArray>(), any(), any(), AudioTrack.WRITE_NON_BLOCKING) } answers {
            played.add(firstArg<ShortArray>().copyOfRange(secondArg(), secondArg<Int>() + thirdArg<Int>()))
            thirdArg<Int>()
        }
    }

    @Test fun escapedFramesSplitAcrossWritesArePlayedAndNothingIsSent() = runBlocking {
        // Pick an SBC frame whose bytes need escaping, so the 0x7D path crosses a write boundary.
        val encoded = (0 until 500).asSequence()
            .map { seed -> SbcCodecSession().encode(ShortArray(128) { (it * 120 - 7000 + seed * 37).toShort() })!! }
            .first { frame -> frame.any { it == 0x7e.toByte() || it == 0x7d.toByte() } }
        val rx = AudioFraming.escape(0x00, encoded)
        val stream = AudioFraming.escape(0x09, encoded) + // TX echo: must not play
            rx + AudioFraming.escape(0x01, ByteArray(0)) + AudioFraming.escape(0x03, encoded)
        val escapeAt = AudioFraming.escape(0x09, encoded).size + rx.indexOf(0x7d.toByte())
        val cuts = listOf(0, 1, escapeAt + 1, stream.size - 2, stream.size).distinct().sorted()

        var sentByApp = -2
        val fake = thread {
            server.accept().use { s ->
                for ((from, to) in cuts.zipWithNext()) {
                    s.getOutputStream().write(stream, from, to - from); s.getOutputStream().flush()
                    Thread.sleep(30)
                }
                sentByApp = bytesReadUntilClose(s)
            }
        }
        val played = Collections.synchronizedList(mutableListOf<ShortArray>())
        val channel = BridgeAudioChannel({ "127.0.0.1" }, server.localPort, { capturingTrack(played) })
        try {
            channel.connect()
            assertTrue(channel.isConnected.value)
            withTimeout(5_000) { while (played.size < 2) delay(10) }
            assertFalse(channel.startTransmit())
            assertFalse(channel.isTransmitting.value)
        } finally { channel.close() }
        fake.join(10_000)
        delay(100)
        assertEquals(2, played.size)
        val reference = SbcCodecSession().decode(encoded, 0)!!
        assertArrayEquals(reference, played[0])
        assertArrayEquals(reference, played[1])
        assertEquals("bridge audio must never write to the socket", 0, sentByApp)
    }

    @Test fun startTransmitIsRefusedAndWritesNothing() = runBlocking {
        var sentByApp = -2
        val fake = thread { server.accept().use { sentByApp = bytesReadUntilClose(it) } }
        val channel = BridgeAudioChannel({ "127.0.0.1" }, server.localPort, { mockk(relaxed = true) })
        try {
            channel.connect()
            assertTrue(channel.isConnected.value)
            assertFalse(channel.startTransmit())
            assertFalse(channel.isTransmitting.value)
            channel.stopTransmit()
            assertTrue(channel.isConnected.value)
        } finally { channel.close() }
        fake.join(10_000)
        assertFalse(channel.isConnected.value)
        assertEquals("bridge audio must never write to the socket", 0, sentByApp)
    }

    @Test fun missingHostOrRefusedPortLeavesChannelDisconnected() = runBlocking {
        val refusedPort = ServerSocket(0).use { it.localPort }
        for (channel in listOf(
            BridgeAudioChannel({ null }, refusedPort, { mockk(relaxed = true) }),
            BridgeAudioChannel({ "127.0.0.1" }, refusedPort, { mockk(relaxed = true) }),
        )) {
            try {
                channel.connect()
                assertFalse(channel.isConnected.value)
            } finally { channel.close() }
        }
    }

    /** Guard for the no-transmit rule: the bridge audio source never obtains a write stream. */
    @Test fun sourceHasNoSocketWritePath() {
        val source = File("src/main/java/com/hamradio/aaos/radio/audio/BridgeAudioChannel.kt").readText()
        assertFalse(source.contains("outputstream", ignoreCase = true))
        assertFalse(source.contains(".write("))
    }
}
