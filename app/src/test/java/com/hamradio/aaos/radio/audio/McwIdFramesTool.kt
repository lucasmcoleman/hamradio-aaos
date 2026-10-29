package com.hamradio.aaos.radio.audio

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.DataOutputStream
import java.io.File
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Bench tool, not a regular test: renders a Morse ID (MCW, 800 Hz) as the exact audio-channel
 * frames the app transmits (SBC, then AudioFraming cmd 0). Runs only when HAMRADIO_MCW_OUT is set:
 *   HAMRADIO_MCW_OUT=path HAMRADIO_MCW_TEXT=KK4FVC gradlew testDebugUnitTest --tests '*McwIdFramesTool*'
 * Output records: 2-byte big-endian length + one escaped frame (4 ms of audio each), so a
 * sender can pace them in real time. Used for Lucas's approved on-air bridge test (#18773).
 */
class McwIdFramesTool {
    private val morse = mapOf(
        'K' to "-.-", 'F' to "..-.", 'V' to "...-", 'C' to "-.-.", '4' to "....-",
    )

    @Test fun renderMcwId() {
        val out = System.getenv("HAMRADIO_MCW_OUT")
        assumeTrue("set HAMRADIO_MCW_OUT to render", out != null)
        val text = System.getenv("HAMRADIO_MCW_TEXT") ?: "KK4FVC"
        val wpm = 18
        val unit = (1.2 / wpm * 32_000).toInt()  // samples per dot
        val pcm = ArrayList<Short>()
        fun silence(units: Int) = repeat(units * unit) { pcm.add(0) }
        fun tone(units: Int) {
            val n = units * unit
            val ramp = 160  // 5 ms rise/fall to avoid key clicks
            for (i in 0 until n) {
                val env = min(1.0, min(i, n - 1 - i) / ramp.toDouble())
                pcm.add((sin(2 * PI * 800 * (pcm.size) / 32_000) * 12_000 * env).toInt().toShort())
            }
        }
        silence(8)  // let the radio key up before the first element
        text.uppercase().forEachIndexed { ci, ch ->
            if (ci > 0) silence(3)
            val code = morse[ch] ?: error("no Morse for '$ch'")
            code.forEachIndexed { ei, e ->
                if (ei > 0) silence(1)
                tone(if (e == '.') 1 else 3)
            }
        }
        silence(4)
        while (pcm.size % SbcCodecSession.SAMPLES_PER_FRAME != 0) pcm.add(0)

        val codec = SbcCodecSession()
        val check = SbcCodecSession()
        var frames = 0
        var decodedSamples = 0
        DataOutputStream(File(out!!).outputStream().buffered()).use { dos ->
            for (start in pcm.indices step SbcCodecSession.SAMPLES_PER_FRAME) {
                val block = ShortArray(SbcCodecSession.SAMPLES_PER_FRAME) { pcm[start + it] }
                val sbc = codec.encode(block) ?: error("SBC encode failed")
                decodedSamples += check.decode(sbc)?.size ?: 0
                val framed = AudioFraming.escape(0x00, sbc)
                dos.writeShort(framed.size)
                dos.write(framed)
                frames++
            }
        }
        val seconds = pcm.size / 32_000.0
        assertTrue("decode round trip", decodedSamples == pcm.size)
        println("MCW '$text': $frames frames, ${"%.2f".format(seconds)} s -> $out")
    }
}
