package com.hamradio.aaos.radio.audio

import android.media.AudioTrack
import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.Socket
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
        every { Log.e(any(), any(), any()) } returns 0
        every { Log.d(any(), any(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        server = ServerSocket(0)
    }

    @After fun teardown() {
        server.close()
        unmockkStatic(Log::class)
    }

    /** Fake bridge: records every byte the app sends until the app closes the socket. */
    private inner class FakeBridge(private val dropAfterBytes: Int = -1) {
        private val received = ByteArrayOutputStream()
        @Volatile var sawEof = false
        private val worker = thread {
            server.accept().use { s ->
                s.soTimeout = 10_000
                val buf = ByteArray(1024)
                try {
                    while (true) {
                        val n = s.getInputStream().read(buf)
                        if (n < 0) { sawEof = true; break }
                        synchronized(received) { received.write(buf, 0, n) }
                        if (dropAfterBytes in 0..bytes().size) break // peer drop: close mid-TX
                    }
                } catch (_: SocketTimeoutException) {}
            }
        }
        fun bytes(): ByteArray = synchronized(received) { received.toByteArray() }
        fun await(): ByteArray { worker.join(15_000); return bytes() }
        suspend fun awaitBytes(min: Int) = withTimeout(5_000) { while (bytes().size < min) delay(10) }
        suspend fun awaitEndAudio() = withTimeout(5_000) { while (!bytes().endsWith(END_AUDIO)) delay(10) }
    }

    /** Every cmd 0x00 frame must carry exactly one decodable SBC frame; END_AUDIO only once, last. */
    private fun assertTxStream(bytes: ByteArray, expectAudio: Boolean = true) {
        assertTrue("stream must end with END_AUDIO", bytes.endsWith(END_AUDIO))
        val frames = splitFrames(bytes)
        val audio = frames.filter { it[0] == 0.toByte() }
        if (expectAudio) assertTrue("expected cmd 0x00 audio frames", audio.isNotEmpty())
        val decoder = SbcCodecSession()
        for (frame in audio) {
            val payload = AudioFraming.unescape(frame, 1)!!
            assertEquals(payload.size, decoder.frameLength(payload, 0))
            assertNotNull(decoder.decode(payload, 0))
        }
        assertEquals(1, frames.count { it[0] == 1.toByte() })
        assertArrayEquals(END_AUDIO.copyOfRange(1, END_AUDIO.size - 1), frames.last())
        assertEquals(frames.size, audio.size + 1)
    }

    /** A healthy link must stop TX by joining the job, not by the write watchdog or a teardown timeout. */
    private fun assertNoForcedTeardown() = verify(exactly = 0) { Log.w(any<String>(), any<String>()) }

    private fun channel(source: () -> PcmSource = { FakePcmSource() }) =
        BridgeAudioChannel({ "127.0.0.1" }, server.localPort, { mockk(relaxed = true) }, source)

    private fun capturingTrack(played: MutableList<ShortArray>): AudioTrack = mockk<AudioTrack>(relaxed = true).also { track ->
        every { track.write(any<ShortArray>(), any(), any(), AudioTrack.WRITE_NON_BLOCKING) } answers {
            played.add(firstArg<ShortArray>().copyOfRange(secondArg(), secondArg<Int>() + thirdArg<Int>()))
            thirdArg<Int>()
        }
    }

    @Test fun escapedFramesSplitAcrossWritesArePlayedAndNothingIsSentWithoutPtt() = runBlocking {
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
        val channel = BridgeAudioChannel({ "127.0.0.1" }, server.localPort, { capturingTrack(played) }, { FakePcmSource() })
        try {
            channel.connect()
            assertTrue(channel.isConnected.value)
            withTimeout(5_000) { while (played.size < 2) delay(10) }
            assertFalse(channel.isTransmitting.value)
        } finally { channel.close() }
        fake.join(10_000)
        delay(100)
        assertEquals(2, played.size)
        val reference = SbcCodecSession().decode(encoded, 0)!!
        assertArrayEquals(reference, played[0])
        assertArrayEquals(reference, played[1])
        assertEquals("receive alone must never write to the bridge", 0, sentByApp)
    }

    /** Bytes the fake bridge reads from the app until the app closes the socket (-1 if it never did). */
    private fun bytesReadUntilClose(socket: Socket): Int {
        socket.soTimeout = 5_000
        var total = 0
        val buf = ByteArray(256)
        try {
            var n = socket.getInputStream().read(buf)
            while (n >= 0) { total += n; n = socket.getInputStream().read(buf) }
        } catch (_: SocketTimeoutException) { return -1 }
        return total
    }

    @Test fun startThenStopSendsDecodableSbcFramesThenEndAudio() = runBlocking {
        val bridge = FakeBridge()
        val channel = channel()
        try {
            channel.connect()
            assertTrue(channel.startTransmit())
            assertTrue(channel.isTransmitting.value)
            bridge.awaitBytes(500)
            channel.stopTransmit()
            assertFalse(channel.isTransmitting.value)
            bridge.awaitEndAudio()
            assertTrue("stop must not drop the link", channel.isConnected.value)
        } finally { channel.close() }
        val sent = bridge.await()
        assertTrue(bridge.sawEof)
        assertTxStream(sent)
        assertNoForcedTeardown()
    }

    @Test fun disconnectDuringTransmitSendsEndAudioBeforeClosing() = runBlocking {
        val bridge = FakeBridge()
        val channel = channel()
        try {
            channel.connect()
            assertTrue(channel.startTransmit())
            bridge.awaitBytes(500)
            channel.disconnect()
            assertFalse(channel.isTransmitting.value)
            assertFalse(channel.isConnected.value)
        } finally { channel.close() }
        val sent = bridge.await()
        assertTrue(bridge.sawEof)
        assertTxStream(sent)
        assertNoForcedTeardown()
    }

    @Test fun closeDuringTransmitSendsEndAudio() = runBlocking {
        val bridge = FakeBridge()
        val channel = channel()
        channel.connect()
        assertTrue(channel.startTransmit())
        bridge.awaitBytes(500)
        channel.close()
        assertFalse(channel.isTransmitting.value)
        assertTxStream(bridge.await())
        assertNoForcedTeardown()
        assertTrue(bridge.sawEof)
    }

    @Test fun cancelledStartStillSendsEndAudio() = runBlocking {
        val bridge = FakeBridge()
        // Microphone startup outlasts the caller; the cancelled start must still unkey.
        val channel = channel { FakePcmSource(startDelayMs = 400) }
        try {
            channel.connect()
            assertNull(withTimeoutOrNull(100) { channel.startTransmit() })
            assertFalse(channel.isTransmitting.value)
            bridge.awaitEndAudio()
            assertTrue(channel.isConnected.value)
        } finally { channel.close() }
        assertTxStream(bridge.await(), expectAudio = false)
    }

    @Test fun microphoneFailureMidTransmitSendsEndAudioAndClearsTransmitting() = runBlocking {
        val bridge = FakeBridge()
        val channel = channel { FakePcmSource(failAfterReads = 40) }
        try {
            channel.connect()
            assertTrue(channel.startTransmit())
            withTimeout(5_000) { channel.isTransmitting.first { !it } }
            bridge.awaitEndAudio()
            assertTrue(channel.isConnected.value)
            delay(100) // let the failed job finish exiting after its END_AUDIO write
            // A later PTT press can transmit again on the same link.
            assertTrue(channel.startTransmit())
            withTimeout(5_000) { channel.isTransmitting.first { !it } }
        } finally { channel.close() }
        val sent = bridge.await()
        assertTrue(sent.endsWith(END_AUDIO))
        assertEquals(2, splitFrames(sent).count { it[0] == 1.toByte() })
    }

    @Test fun bridgeDropDuringTransmitEndsTransmit() = runBlocking {
        val bridge = FakeBridge(dropAfterBytes = 500)
        val channel = channel()
        try {
            channel.connect()
            assertTrue(channel.startTransmit())
            withTimeout(5_000) { channel.isTransmitting.first { !it } }
            withTimeout(5_000) { channel.isConnected.first { !it } }
        } finally { channel.close() }
        assertTrue(bridge.await().size >= 500)
    }

    @Test fun missingHostOrRefusedPortLeavesChannelDisconnected() = runBlocking {
        val refusedPort = ServerSocket(0).use { it.localPort }
        for (channel in listOf(
            BridgeAudioChannel({ null }, refusedPort, { mockk(relaxed = true) }, { FakePcmSource() }),
            BridgeAudioChannel({ "127.0.0.1" }, refusedPort, { mockk(relaxed = true) }, { FakePcmSource() }),
        )) {
            try {
                channel.connect()
                assertFalse(channel.isConnected.value)
                assertFalse("no link, no transmit", channel.startTransmit())
                assertFalse(channel.isTransmitting.value)
            } finally { channel.close() }
        }
    }
}
