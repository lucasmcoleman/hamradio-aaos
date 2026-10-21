package com.hamradio.aaos.radio.audio

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MockRadioAudioChannelTest {
    private class FakeIo(
        val input: ShortArray = shortArrayOf(),
        val startError: Exception? = null,
        val waitOnStart: Boolean = false,
        val stallPlayback: Boolean = false,
    ) : LoopbackPcmIo {
        var position = 0
        val played = mutableListOf<Short>()
        var stopped = 0
        var released = 0
        override suspend fun start() {
            startError?.let { throw it }
            if (waitOnStart) awaitCancellation()
        }
        override suspend fun read(samples: ShortArray, offset: Int, size: Int): Int {
            val count = minOf(size, 19, input.size - position)
            input.copyInto(samples, offset, position, position + count)
            position += count
            return count
        }
        override suspend fun write(samples: ShortArray, offset: Int, size: Int): Int {
            if (stallPlayback) return 0
            val count = minOf(size, 23)
            repeat(count) { played.add(samples[offset + it]) }
            return count
        }
        override fun stop() { stopped++ }
        override fun close() { released++ }
    }

    @Test fun partialPcmReadsTraverseRealCodecAndFramingThenPartialPlayback() = runTest {
        val pcm = ShortArray(256) { ((it % 64) * 500 - 16000).toShort() }
        val io = FakeIo(pcm)
        val logs = mutableListOf<String>()
        val channel = MockRadioAudioChannel({ io }, StandardTestDispatcher(testScheduler), logs::add)
        channel.connect()
        val start = async { channel.startTransmit() }
        runCurrent()
        assertTrue(start.await())
        assertEquals(2L, channel.telemetry.value.encodedFrames)
        assertEquals(2L, channel.telemetry.value.receivedFrames)
        assertEquals(2L, channel.telemetry.value.decodedFrames)
        assertEquals(2L, channel.telemetry.value.playedFrames)
        assertEquals(256L, channel.telemetry.value.playbackSamples)
        val encoder = SbcCodecSession()
        val decoder = SbcCodecSession()
        val expected = (0..1).flatMap { index ->
            decoder.decode(encoder.encode(pcm.copyOfRange(index * 128, (index + 1) * 128))!!, 0)!!.toList()
        }.toShortArray()
        assertArrayEquals(expected, io.played.toShortArray())
        channel.stopTransmit()
        assertFalse(channel.isTransmitting.value)
        assertEquals(1, io.released)
        assertTrue(logs.any { "SIMULATED loopback evidence" in it && "played=2" in it })
        channel.close()
    }

    @Test fun deniedStartupReturnsFalseAndReleasesThenAllowsFreshCapture() = runTest {
        val denied = FakeIo(startError = SecurityException("permission denied"))
        val working = FakeIo()
        val sources = ArrayDeque(listOf(denied, working))
        val channel = MockRadioAudioChannel({ sources.removeFirst() }, StandardTestDispatcher(testScheduler), {})
        channel.connect()
        assertFalse(channel.startTransmit())
        assertEquals(1, denied.released)
        assertFalse(channel.isTransmitting.value)
        assertTrue(channel.telemetry.value.lastError!!.contains("permission denied"))
        assertTrue(channel.startTransmit())
        channel.stopTransmit()
        assertEquals(1, working.released)
        channel.close()
    }

    @Test fun callerCancellationDuringStartupStopsAndReleases() = runTest {
        val io = FakeIo(waitOnStart = true)
        val channel = MockRadioAudioChannel({ io }, StandardTestDispatcher(testScheduler), {})
        channel.connect()
        val start = async { channel.startTransmit() }
        runCurrent()
        start.cancel()
        runCurrent()
        start.join()
        assertFalse(channel.isTransmitting.value)
        assertTrue(io.stopped > 0)
        assertEquals(1, io.released)
        channel.close()
    }

    @Test fun stalledPlaybackNeverAccumulatesMoreThanOneFrameAndDisconnectReleases() = runTest {
        val io = FakeIo(ShortArray(128 * 100), stallPlayback = true)
        val channel = MockRadioAudioChannel({ io }, StandardTestDispatcher(testScheduler), {})
        channel.connect()
        assertTrue(channel.startTransmit())
        advanceTimeBy(1000)
        runCurrent()
        assertEquals(128, io.position)
        assertEquals(1L, channel.telemetry.value.encodedFrames)
        assertEquals(0L, channel.telemetry.value.playedFrames)
        channel.disconnect()
        assertFalse(channel.isConnected.value)
        assertFalse(channel.isTransmitting.value)
        assertEquals(1, io.released)
        channel.close()
    }

    @Test fun startupTimeoutReleasesAndCloseBeforeConnectDoesNotOpenPcm() = runTest {
        val io = FakeIo(waitOnStart = true)
        val channel = MockRadioAudioChannel({ io }, StandardTestDispatcher(testScheduler), {})
        channel.connect()
        assertFalse(channel.startTransmit()) // scheduler advances through three-second startup timeout
        assertEquals(1, io.released)
        channel.close()
        var opened = false
        val unopened = MockRadioAudioChannel({ opened = true; FakeIo() }, StandardTestDispatcher(testScheduler), {})
        unopened.close()
        assertFalse(opened)
        assertFalse(unopened.startTransmit())
    }

    @Test fun frameEscapingRoundTripsReservedBytesAndRejectsWrongCommandOrDanglingEscape() {
        val payload = byteArrayOf(0x7e, 0x7d, 0x00, 0x01)
        assertArrayEquals(payload, AudioFraming.loopbackPayload(AudioFraming.escape(0, payload)))
        assertNull(AudioFraming.loopbackPayload(AudioFraming.escape(9, payload)))
        assertNull(AudioFraming.loopbackPayload(byteArrayOf(0x7e, 0, 0x7d, 0x7e)))
    }
}
