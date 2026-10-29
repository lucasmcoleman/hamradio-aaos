package com.hamradio.aaos.radio.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

private const val MAX_RX_FRAME_BYTES = 8192

/** 32 kHz mono speech playback, as the radio's SBC stream decodes to. */
internal fun openPlaybackTrack(): AudioTrack {
    val minSize = AudioTrack.getMinBufferSize(32000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
    check(minSize > 0) { "Unsupported playback PCM format: $minSize" }
    val playback = AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setAudioFormat(AudioFormat.Builder().setSampleRate(32000)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
        .setBufferSizeInBytes(maxOf(minSize, SbcCodec.PCM_BYTES_PER_FRAME * 4))
        .setTransferMode(AudioTrack.MODE_STREAM).build()
    if (playback.state != AudioTrack.STATE_INITIALIZED) {
        try { playback.release() } catch (_: Exception) {}
        throw IllegalStateException("AudioTrack initialization failed")
    }
    return playback
}

/**
 * Radio audio receive path over any byte stream (RFCOMM socket or the ESP32 bridge's TCP relay):
 * 0x7E-delimited frames, 0x7D unescaping, SBC decode, AudioTrack playback. Read-only by design.
 */
internal class AudioReceivePipeline(
    private val codec: SbcCodecSession,
    private val openTrack: () -> AudioTrack = ::openPlaybackTrack,
) {
    /**
     * Play [input] until EOF or cancellation. [onPlaying] runs once playback has started; returning
     * false stops before any read. The track is always released.
     */
    suspend fun play(input: InputStream, onPlaying: () -> Boolean) {
        val playback = openTrack()
        try {
            codec.resetDecoder()
            playback.play()
            if (!onPlaying()) return
            readFrames(input) { playFrame(it, playback) }
        } finally {
            try { playback.stop() } catch (_: Exception) {}
            try { playback.release() } catch (_: Exception) {}
        }
    }

    /** Split [input] on 0x7E delimiters; each still-escaped frame body goes to [onFrame]. */
    suspend fun readFrames(input: InputStream, onFrame: suspend (ByteArray) -> Unit) {
        val readBuffer = ByteArray(1024)
        val frame = ByteArrayOutputStream(MAX_RX_FRAME_BYTES)
        var collecting = false
        while (currentCoroutineContext().isActive) {
            val count = input.read(readBuffer)
            if (count < 0) break
            if (count == 0) throw IOException("Audio stream returned an empty read")
            for (index in 0 until count) {
                currentCoroutineContext().ensureActive()
                val value = readBuffer[index].toInt() and 0xff
                if (value == 0x7e) {
                    if (collecting && frame.size() > 0) onFrame(frame.toByteArray())
                    frame.reset()
                    collecting = true // also supports adjacent frames sharing one delimiter
                } else if (collecting) {
                    if (frame.size() == MAX_RX_FRAME_BYTES) {
                        frame.reset()
                        collecting = false // discard oversized frame until the next delimiter
                    } else frame.write(value)
                }
            }
        }
    }

    suspend fun playFrame(frame: ByteArray, track: AudioTrack) {
        if (frame.isEmpty()) return
        if (frame[0] == 1.toByte()) {
            codec.resetDecoder()
            return
        }
        // HTCommander 09dfa3e: 0x00 and 0x03 are RX; 0x09 is our TX echo.
        if (frame.size < 2 || (frame[0] != 0.toByte() && frame[0] != 3.toByte())) return
        val payload = AudioFraming.unescape(frame, 1) ?: return
        var offset = 0
        while (offset < payload.size) {
            currentCoroutineContext().ensureActive()
            val length = codec.frameLength(payload, offset)
            if (length == null) { offset++; continue }
            val pcm = codec.decode(payload, offset)
            if (pcm != null) {
                var written = 0
                while (written < pcm.size) {
                    currentCoroutineContext().ensureActive()
                    // Nonblocking writes keep disconnect cancellation observable if playback stalls.
                    val count = track.write(pcm, written, pcm.size - written, AudioTrack.WRITE_NON_BLOCKING)
                    if (count < 0) throw IOException("AudioTrack write failed: $count")
                    if (count == 0) delay(5) else written += count
                }
            }
            offset += length
        }
    }
}
