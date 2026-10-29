package com.hamradio.aaos.radio.audio

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sin

/** Synthetic microphone: a paced 440 Hz tone; optionally slow to start or failing after [failAfterReads]. */
internal class FakePcmSource(
    private val failAfterReads: Int = -1,
    private val startDelayMs: Long = 0,
) : PcmSource {
    @Volatile var stopped = false
    @Volatile var released = false
    val reads = AtomicInteger()
    private var phase = 0

    override fun start() { if (startDelayMs > 0) Thread.sleep(startDelayMs) }

    override fun read(samples: ShortArray, offset: Int, size: Int): Int {
        if (stopped) return -1 // like AudioRecord after stop()
        if (failAfterReads >= 0 && reads.get() >= failAfterReads) throw IOException("fake microphone failure")
        reads.incrementAndGet()
        Thread.sleep(2)
        for (i in 0 until size) samples[offset + i] = (sin(2 * Math.PI * 440 * phase++ / 32000) * 8000).toInt().toShort()
        return size
    }

    override fun stop() { stopped = true }
    override fun release() { released = true }
}

/** Split a captured byte stream into 0x7E-delimited frame bodies (still escaped). */
internal fun splitFrames(bytes: ByteArray): List<ByteArray> {
    val frames = mutableListOf<ByteArray>()
    val current = ByteArrayOutputStream()
    for (b in bytes) {
        if (b == 0x7e.toByte()) {
            if (current.size() > 0) frames += current.toByteArray()
            current.reset()
        } else current.write(b.toInt())
    }
    return frames
}

internal fun ByteArray.endsWith(suffix: ByteArray): Boolean =
    size >= suffix.size && copyOfRange(size - suffix.size, size).contentEquals(suffix)

class AudioTransmitPipelineTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Before fun setup() {
        mockkStatic(Log::class)
        every { Log.e(any(), any(), any()) } returns 0
        every { Log.d(any(), any(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
    }

    @After fun teardown() {
        scope.cancel()
        unmockkStatic(Log::class)
    }

    /** Output whose writes block until the transport is closed, then fail like a closed socket. */
    private class StalledOutput : OutputStream() {
        val closed = CountDownLatch(1)
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) {
            closed.await(10, TimeUnit.SECONDS)
            throw IOException("socket closed")
        }
    }

    @Test fun framesThenEndAudioOnStop() = runBlocking {
        val out = ByteArrayOutputStream()
        val sink = object : OutputStream() {
            override fun write(b: Int) = synchronized(out) { out.write(b) }
            override fun write(b: ByteArray, off: Int, len: Int) = synchronized(out) { out.write(b, off, len) }
        }
        val source = FakePcmSource()
        val tx = AudioTransmitPipeline(scope, SbcCodecSession(), "test", { source })
        assertTrue(tx.startLocked(Any(), sink, { true }) {})
        assertTrue(tx.isTransmitting.value)
        withTimeout(5_000) { while (source.reads.get() < 20) kotlinx.coroutines.delay(5) }
        tx.stopLocked()
        assertFalse(tx.isTransmitting.value)
        assertTrue(source.released)
        val bytes = synchronized(out) { out.toByteArray() }
        assertTrue(bytes.endsWith(END_AUDIO))
        val frames = splitFrames(bytes)
        assertTrue(frames.count { it[0] == 0.toByte() } > 0)
        assertEquals(1, frames.count { it[0] == 1.toByte() })
    }

    @Test fun writeBlockedPastLimitClosesTransportAndEndsTransmit() = runBlocking {
        val out = StalledOutput()
        val tx = AudioTransmitPipeline(scope, SbcCodecSession(), "test", { FakePcmSource() }, writeTimeoutMs = 300)
        val began = System.nanoTime()
        assertTrue(tx.startLocked(Any(), out, { true }) { out.closed.countDown() })
        assertTrue("stalled write must close the transport", out.closed.await(3, TimeUnit.SECONDS))
        assertTrue((System.nanoTime() - began) / 1_000_000 >= 300)
        withTimeout(3_000) { tx.isTransmitting.first { !it } }
        tx.stopLocked()
    }

    @Test fun withoutWatchdogStopForceClosesBlockedWrite() = runBlocking {
        // Classic Bluetooth path: no watchdog, but stop still closes a transport whose write is stuck.
        val out = StalledOutput()
        val tx = AudioTransmitPipeline(scope, SbcCodecSession(), "test", { FakePcmSource() }, stopTimeoutMs = 200)
        assertTrue(tx.startLocked(Any(), out, { true }) { out.closed.countDown() })
        Thread.sleep(300)
        assertEquals("no watchdog: nothing closes the transport before stop", 1L, out.closed.count)
        tx.stopLocked()
        assertEquals(0L, out.closed.count)
        assertFalse(tx.isTransmitting.value)
    }

    @Test fun endAudioMarkerIsTheProtocolConstant() {
        assertArrayEquals(byteArrayOf(0x7E, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x7E), END_AUDIO)
    }
}
