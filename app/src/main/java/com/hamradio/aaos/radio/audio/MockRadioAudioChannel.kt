package com.hamradio.aaos.radio.audio

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/** Counts actual software stages, not audible playback or RF transmission. Cumulative per instance. */
data class MockAudioTelemetry(
    val capturedSamples: Long = 0,
    val encodedFrames: Long = 0,
    val receivedFrames: Long = 0,
    val decodedFrames: Long = 0,
    val playbackSamples: Long = 0,
    val playedFrames: Long = 0,
    val lastError: String? = null,
)

/** Local microphone -> SBC -> cmd 0x00 -> decoded PCM -> speaker. Never opens Bluetooth or keys RF. */
class MockRadioAudioChannel internal constructor(
    private val ioFactory: () -> LoopbackPcmIo,
    private val dispatcher: CoroutineDispatcher,
    private val report: (String) -> Unit,
) : RadioAudioControl {
    constructor(@Suppress("UNUSED_PARAMETER") context: Context) : this(
        { AndroidLoopbackPcmIo() }, Dispatchers.IO, { Log.i("MockRadioAudio", it) },
    )

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val lifecycle = Mutex()
    private var closed = false
    private var worker: Job? = null
    @Volatile private var activeIo: LoopbackPcmIo? = null
    private val _isConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()
    private val _isTransmitting = MutableStateFlow(false)
    override val isTransmitting: StateFlow<Boolean> = _isTransmitting.asStateFlow()
    private val _telemetry = MutableStateFlow(MockAudioTelemetry())
    val telemetry: StateFlow<MockAudioTelemetry> = _telemetry.asStateFlow()

    override suspend fun connect() = lifecycle.withLock {
        check(!closed) { "Mock audio channel is closed" }
        _isConnected.value = true
        report("SIMULATED audio connected; local PCM loopback only, no Bluetooth/RF")
    }

    override suspend fun startTransmit(): Boolean = lifecycle.withLock {
        if (closed || !_isConnected.value || worker?.isCompleted == false) return@withLock false
        val started = CompletableDeferred<Boolean>()
        _telemetry.update { it.copy(lastError = null) }
        val task = scope.launch(start = CoroutineStart.LAZY) {
            var io: LoopbackPcmIo? = null
            try {
                val pcmIo = ioFactory()
                io = pcmIo
                activeIo = pcmIo
                currentCoroutineContext().ensureActive()
                pcmIo.start()
                currentCoroutineContext().ensureActive()
                val codec = SbcCodecSession()
                _isTransmitting.value = true
                started.complete(true)
                report("SIMULATED capture started; live local SBC loopback, playback volume 0.15")
                // Exactly one PCM frame and one SBC packet in flight. No queue or accumulating echo.
                val pcm = ShortArray(SbcCodec.SAMPLES_PER_FRAME)
                var filled = 0
                while (isActive) {
                    val read = pcmIo.read(pcm, filled, pcm.size - filled)
                    if (read < 0) throw IOException("Microphone read failed: $read")
                    if (read == 0) { delay(4); continue }
                    check(read <= pcm.size - filled) { "Microphone returned invalid sample count" }
                    _telemetry.update { it.copy(capturedSamples = it.capturedSamples + read) }
                    filled += read
                    if (filled < pcm.size) continue
                    currentCoroutineContext().ensureActive()
                    val encoded = codec.encode(pcm) ?: throw IOException("SBC encoding failed")
                    _telemetry.update { it.copy(encodedFrames = it.encodedFrames + 1) }
                    val packet = AudioFraming.escape(0x00, encoded)
                    val payload = AudioFraming.loopbackPayload(packet) ?: throw IOException("Local cmd 0x00 framing failed")
                    _telemetry.update { it.copy(receivedFrames = it.receivedFrames + 1) }
                    check(codec.frameLength(payload, 0) == payload.size) { "Invalid local SBC frame length" }
                    val decoded = codec.decode(payload, 0) ?: throw IOException("SBC decoding failed")
                    _telemetry.update { it.copy(decodedFrames = it.decodedFrames + 1) }
                    var written = 0
                    while (written < decoded.size) {
                        currentCoroutineContext().ensureActive()
                        val count = pcmIo.write(decoded, written, decoded.size - written)
                        if (count < 0) throw IOException("Playback write failed: $count")
                        check(count <= decoded.size - written) { "Playback returned invalid sample count" }
                        if (count == 0) { delay(4); continue }
                        written += count
                        _telemetry.update { it.copy(playbackSamples = it.playbackSamples + count) }
                    }
                    _telemetry.update { it.copy(playedFrames = it.playedFrames + 1) }
                    if (_telemetry.value.playedFrames % 250L == 0L) reportEvidence()
                    filled = 0
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _telemetry.update { it.copy(lastError = "${e.javaClass.simpleName}: ${e.message}") }
                report("SIMULATED loopback failed: ${_telemetry.value.lastError}")
            } finally {
                started.complete(false)
                _isTransmitting.value = false
                try { io?.stop() } catch (_: Exception) {}
                try { io?.close() } catch (_: Exception) {}
                activeIo = null
                reportEvidence()
            }
        }
        worker = task
        task.start()
        try {
            val ready = withTimeoutOrNull(3000) { started.await() } == true
            if (!ready) withContext(NonCancellable + dispatcher) { stopLocked() }
            ready && _isTransmitting.value
        } catch (e: CancellationException) {
            withContext(NonCancellable + dispatcher) { stopLocked() }
            throw e
        }
    }

    override suspend fun stopTransmit() = withContext(NonCancellable + dispatcher) {
        lifecycle.withLock { stopLocked() }
    }

    override suspend fun disconnect() = withContext(NonCancellable + dispatcher) {
        lifecycle.withLock {
            _isConnected.value = false
            stopLocked()
        }
    }

    override suspend fun close() = withContext(NonCancellable + dispatcher) {
        lifecycle.withLock {
            closed = true
            _isConnected.value = false
            stopLocked()
            scope.cancel()
        }
    }

    private suspend fun stopLocked() {
        val job = worker ?: return
        job.cancel()
        _isTransmitting.value = false
        try { activeIo?.stop() } catch (_: Exception) {}
        if (withTimeoutOrNull(1500) { job.join(); true } != true) {
            _telemetry.update { it.copy(lastError = "Loopback shutdown timed out; restart blocked until worker exits") }
            report("SIMULATED ${_telemetry.value.lastError}")
            return // do not replace a worker whose finally has not released its resources
        }
        worker = null
    }

    private fun reportEvidence() {
        val t = _telemetry.value
        report("SIMULATED loopback evidence capturedSamples=${t.capturedSamples} encoded=${t.encodedFrames} " +
            "cmd00Rx=${t.receivedFrames} decoded=${t.decodedFrames} played=${t.playedFrames} " +
            "playbackSamples=${t.playbackSamples}; software acceptance only, audible output unverified")
    }
}

/** Reads/writes may be partial or zero (temporarily unavailable); negative values mean failure. */
internal interface LoopbackPcmIo {
    suspend fun start()
    suspend fun read(samples: ShortArray, offset: Int, size: Int): Int
    suspend fun write(samples: ShortArray, offset: Int, size: Int): Int
    fun stop()
    fun close()
}
