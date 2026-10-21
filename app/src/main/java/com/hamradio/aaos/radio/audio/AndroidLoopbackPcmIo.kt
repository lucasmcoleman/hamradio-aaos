package com.hamradio.aaos.radio.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Local-only PCM endpoints. Nonblocking IO keeps cancellation observable during silence/stalls. */
@SuppressLint("MissingPermission")
internal class AndroidLoopbackPcmIo : LoopbackPcmIo {
    private val lock = Any()
    private var capture: AudioRecord? = null
    private var playback: AudioTrack? = null

    override suspend fun start() {
        val coroutine = currentCoroutineContext()
        synchronized(lock) {
            coroutine.ensureActive()
            val captureSize = AudioRecord.getMinBufferSize(32000,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val playbackSize = AudioTrack.getMinBufferSize(32000,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(captureSize > 0 && playbackSize > 0) { "32 kHz mono PCM is unavailable" }
            val record = AudioRecord(MediaRecorder.AudioSource.MIC, 32000,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(captureSize, SbcCodec.PCM_BYTES_PER_FRAME * 4))
            capture = record
            check(record.state == AudioRecord.STATE_INITIALIZED) { "Microphone initialization failed" }
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(32000)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(playbackSize, SbcCodec.PCM_BYTES_PER_FRAME * 4))
                .setTransferMode(AudioTrack.MODE_STREAM).build()
            playback = track
            check(track.state == AudioTrack.STATE_INITIALIZED) { "Playback initialization failed" }
            track.setVolume(0.15f)
            coroutine.ensureActive()
            track.play()
            record.startRecording()
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone did not start" }
            check(track.playState == AudioTrack.PLAYSTATE_PLAYING) { "Playback did not start" }
        }
    }

    override suspend fun read(samples: ShortArray, offset: Int, size: Int): Int =
        synchronized(lock) { capture?.read(samples, offset, size, AudioRecord.READ_NON_BLOCKING) ?: -1 }

    override suspend fun write(samples: ShortArray, offset: Int, size: Int): Int =
        synchronized(lock) { playback?.write(samples, offset, size, AudioTrack.WRITE_NON_BLOCKING) ?: -1 }

    override fun stop() {
        synchronized(lock) {
            try { capture?.stop() } catch (_: Exception) {}
            try { playback?.pause() } catch (_: Exception) {}
            try { playback?.flush() } catch (_: Exception) {}
        }
    }

    override fun close() {
        synchronized(lock) {
            stop()
            try { capture?.release() } catch (_: Exception) {}
            capture = null
            try { playback?.release() } catch (_: Exception) {}
            playback = null
        }
    }
}
