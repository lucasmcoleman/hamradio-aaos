package com.hamradio.aaos.radio.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicLong

// Wire marker matches HTCommander audio_engine.dart at 09dfa3ea5ffaa835c20320dbaa3c20fc197a9b26; hardware behavior remains unverified.
internal val END_AUDIO = byteArrayOf(0x7E, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x7E)

/** Microphone seam: 32 kHz mono 16-bit PCM. Tests substitute synthetic sources. */
internal interface PcmSource {
    /** Begin capture; throws if capture cannot start. */
    fun start()
    /** Blocking read of up to [size] samples; a count <= 0 is treated as a microphone failure. */
    fun read(samples: ShortArray, offset: Int, size: Int): Int
    /** Stop capture and unblock [read]; may be called from another thread and more than once. */
    fun stop()
    fun release()
}

/** The real microphone. Construction allocates the AudioRecord; [start] begins recording. */
@SuppressLint("MissingPermission")
internal class AndroidMicPcmSource : PcmSource {
    private val record: AudioRecord

    init {
        val minSize = AudioRecord.getMinBufferSize(32000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minSize > 0) { "Unsupported microphone PCM format: $minSize" }
        val capture = AudioRecord(MediaRecorder.AudioSource.MIC, 32000,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minSize, SbcCodec.PCM_BYTES_PER_FRAME * 4))
        if (capture.state != AudioRecord.STATE_INITIALIZED) {
            try { capture.release() } catch (_: Exception) {}
            throw IllegalStateException("AudioRecord initialization failed")
        }
        record = capture
    }

    override fun start() {
        record.startRecording()
        check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone did not start" }
    }

    override fun read(samples: ShortArray, offset: Int, size: Int): Int = record.read(samples, offset, size)
    override fun stop() = record.stop()
    override fun release() = record.release()
}

/**
 * Radio audio transmit path over any byte stream (RFCOMM socket or the ESP32 bridge's TCP relay):
 * microphone PCM -> SBC -> cmd 0x00 escaped frames. Radio keying = audio frames flowing; the job's
 * finally always writes [END_AUDIO] to the same stream it transmitted on, on every exit path.
 *
 * Not internally synchronized: callers serialize [startLocked] and [stopLocked] with one mutex.
 */
internal class AudioTransmitPipeline(
    private val scope: CoroutineScope,
    private val codec: SbcCodecSession,
    private val tag: String,
    private val openSource: () -> PcmSource = ::AndroidMicPcmSource,
    /** Force-close the transport when one write blocks this long; null disables the watchdog. */
    private val writeTimeoutMs: Long? = null,
    private val stopTimeoutMs: Long = 1500L,
    private val startTimeoutMs: Long = 3000L,
) {
    private val captureLock = Any()
    private var source: PcmSource? = null
    private var job: Job? = null
    private var owner: Any? = null
    private var forceClose: (() -> Unit)? = null

    private val _isTransmitting = MutableStateFlow(false)
    val isTransmitting: StateFlow<Boolean> = _isTransmitting.asStateFlow()

    /** True when the current (or retained, not yet exited) TX job belongs to [candidate]. */
    fun isOwnedBy(candidate: Any): Boolean = owner === candidate

    /**
     * Wait for microphone startup; true means capture started, while [isTransmitting] tracks its
     * ongoing state. TX ends when [stillCurrent] turns false. [forceClose] must close the transport
     * under [output] so a blocked write fails.
     */
    suspend fun startLocked(
        owner: Any,
        output: OutputStream,
        stillCurrent: () -> Boolean,
        forceClose: () -> Unit,
    ): Boolean {
        if (job?.isCompleted == false) return false
        val started = CompletableDeferred<Boolean>()
        val txJob = scope.launch(start = CoroutineStart.LAZY) {
            var capture: PcmSource? = null
            val writeStartedAt = AtomicLong(0L)
            val watchdog = writeTimeoutMs?.let { launchWatchdog(it, writeStartedAt, forceClose) }
            try {
                val pcmSource = openSource()
                capture = pcmSource
                val captureContext = currentCoroutineContext()
                synchronized(captureLock) {
                    captureContext.ensureActive()
                    source = pcmSource
                    pcmSource.start()
                    _isTransmitting.value = true
                    started.complete(true)
                }
                codec.resetEncoder()
                val pcm = ShortArray(SbcCodec.SAMPLES_PER_FRAME)
                var filled = 0
                while (isActive && stillCurrent()) {
                    val count = pcmSource.read(pcm, filled, pcm.size - filled)
                    if (count <= 0) throw IOException("Microphone read failed: $count")
                    filled += count
                    if (filled < pcm.size) continue
                    currentCoroutineContext().ensureActive()
                    val encoded = codec.encode(pcm) ?: throw IOException("SBC encoding failed")
                    write(output, AudioFraming.escape(0x00, encoded), writeStartedAt)
                    filled = 0
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(tag, "TX failed", e)
            } finally {
                started.complete(false)
                synchronized(captureLock) {
                    if (source === capture) source = null
                    try { capture?.stop() } catch (_: Exception) {}
                    try { capture?.release() } catch (_: Exception) {}
                    _isTransmitting.value = false
                }
                // Always use this job's stream; an old job must never write to a new session.
                try {
                    write(output, END_AUDIO, writeStartedAt)
                } catch (e: Exception) {
                    Log.d(tag, "End-audio marker could not be sent", e)
                }
                watchdog?.cancel()
            }
        }
        job = txJob
        this.owner = owner
        this.forceClose = forceClose
        txJob.start()
        return try {
            val ready = withTimeoutOrNull(startTimeoutMs) { started.await() } == true
            if (!ready) {
                withContext(NonCancellable + Dispatchers.IO) { stopLocked() }
            }
            ready
        } catch (e: CancellationException) {
            withContext(NonCancellable + Dispatchers.IO) { stopLocked() }
            throw e
        }
    }

    /** Stop and join the capture job; a write still blocked after [stopTimeoutMs] closes the transport. */
    suspend fun stopLocked() {
        val running = job ?: return
        running.cancel()
        synchronized(captureLock) {
            _isTransmitting.value = false
            try { source?.stop() } catch (_: Exception) {}
        }
        if (withTimeoutOrNull(stopTimeoutMs) { running.join(); true } != true) {
            // A blocked output write cannot be cancelled by Job.cancel(). Close the transport.
            forceClose?.invoke()
            if (withTimeoutOrNull(stopTimeoutMs) { running.join(); true } != true) {
                Log.w(tag, "TX teardown exceeded timeout; new TX remains disabled until it exits")
                return // retain job so a new TX cannot race its finally block
            }
        }
        job = null
        owner = null
        forceClose = null
    }

    private fun write(output: OutputStream, bytes: ByteArray, writeStartedAt: AtomicLong) {
        writeStartedAt.set(System.nanoTime() or 1L) // never 0 while a write is in progress
        try {
            output.write(bytes)
            output.flush()
        } finally {
            writeStartedAt.set(0L)
        }
    }

    // Separate from the TX job so it keeps running while the job's finally writes END_AUDIO.
    private fun launchWatchdog(limitMs: Long, writeStartedAt: AtomicLong, forceClose: () -> Unit): Job =
        scope.launch {
            val limitNanos = limitMs * 1_000_000L
            val tick = (limitMs / 6).coerceIn(10L, 250L)
            while (isActive) {
                delay(tick)
                val since = writeStartedAt.get()
                if (since != 0L && System.nanoTime() - since > limitNanos) {
                    Log.w(tag, "TX write blocked over ${limitMs} ms; closing the audio link")
                    forceClose()
                    return@launch
                }
            }
        }
}
