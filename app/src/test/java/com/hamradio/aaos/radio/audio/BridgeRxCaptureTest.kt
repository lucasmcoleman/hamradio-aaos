package com.hamradio.aaos.radio.audio

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * bridge-rx-squelch-open.bin is about 4 s of real DB50-B receive audio (squelch forced open, so
 * band noise) captured from the ESP32 bridge's TCP 8702 on 2026-10-28. It must decode to
 * continuous, non-silent PCM through the same framing and SBC path the app plays.
 */
class BridgeRxCaptureTest {
    @Test fun realBridgeCaptureDecodesToAudio() = runBlocking {
        val bytes = javaClass.classLoader!!.getResourceAsStream("bridge-rx-squelch-open.bin")!!.readBytes()
        val codec = SbcCodecSession()
        val pipeline = AudioReceivePipeline(codec) { error("no playback in this test") }
        var samples = 0L
        var sumSquares = 0.0
        var frames = 0
        pipeline.readFrames(bytes.inputStream()) { frame ->
            if (frame.size < 2 || (frame[0] != 0.toByte() && frame[0] != 3.toByte())) return@readFrames
            frames++
            val payload = AudioFraming.unescape(frame, 1) ?: return@readFrames
            var offset = 0
            while (offset < payload.size) {
                val length = codec.frameLength(payload, offset)
                if (length == null) { offset++; continue }
                codec.decode(payload, offset)?.forEach { s ->
                    samples++
                    sumSquares += s.toDouble() * s
                }
                offset += length
            }
        }
        val seconds = samples / 32_000.0
        val rms = if (samples > 0) sqrt(sumSquares / samples) else 0.0
        assertTrue("expected RX frames, got $frames", frames > 100)
        assertTrue("expected about 4 s of audio, got $seconds s", seconds > 3.0)
        assertTrue("expected open-squelch noise, got RMS $rms", rms > 50.0)
        println("capture: $frames frames, ${"%.2f".format(seconds)} s, RMS ${"%.0f".format(rms)}")
    }
}
