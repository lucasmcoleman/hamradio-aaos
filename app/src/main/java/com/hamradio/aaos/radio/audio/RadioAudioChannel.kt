package com.hamradio.aaos.radio.audio

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlin.coroutines.resume

private const val TAG = "RadioAudio"
private const val MAX_RX_FRAME_BYTES = 8192
private const val STOP_TIMEOUT_MS = 1500L
private const val CAPTURE_START_TIMEOUT_MS = 3000L
private val GENERIC_AUDIO_UUID = UUID.fromString("00001203-0000-1000-8000-00805F9B34FB")
private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

/** Separate RFCOMM audio transport. Radio keying/end-audio semantics require device validation. */
@SuppressLint("MissingPermission")
class RadioAudioChannel(private val context: Context, private val deviceAddress: String) : RadioAudioControl {
    private val codec = SbcCodecSession()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateLock = Any()
    private val captureLock = Any()
    private val txMutex = Mutex()
    private val connectMutex = Mutex()
    private var generation = 0L
    private var pendingSocket: BluetoothSocket? = null
    private data class Session(val socket: BluetoothSocket, val input: InputStream, val output: OutputStream)
    private var session: Session? = null
    private var transmitJob: Job? = null
    private var transmitSession: Session? = null
    private var receiveJob: Job? = null
    private var recorder: AudioRecord? = null

    private val _isTransmitting = MutableStateFlow(false)
    override val isTransmitting: StateFlow<Boolean> = _isTransmitting.asStateFlow()
    private val _isReceiving = MutableStateFlow(false)
    val isReceiving: StateFlow<Boolean> = _isReceiving.asStateFlow()
    private val _isConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    // Wire marker matches HTCommander audio_engine.dart at 09dfa3ea5ffaa835c20320dbaa3c20fc197a9b26; hardware behavior remains unverified.
    private val END_AUDIO = byteArrayOf(0x7E, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x7E)

    override suspend fun connect() = connectMutex.withLock {
        val attempt = synchronized(stateLock) {
            if (session != null) return@withLock
            ++generation
        }
        // Closing the published socket interrupts BluetoothSocket.connect(), which itself is
        // blocking and does not observe coroutine cancellation.
        suspendCancellableCoroutine<Unit> { continuation ->
            val worker = scope.launch(start = CoroutineStart.LAZY) {
                var candidate: BluetoothSocket? = null
                try {
                    val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                    val adapter = manager?.adapter ?: return@launch
                    adapter.cancelDiscovery()
                    val device = adapter.getRemoteDevice(deviceAddress)
                    val sock = try {
                        device.createRfcommSocketToServiceRecord(GENERIC_AUDIO_UUID)
                    } catch (_: IOException) {
                        device.createRfcommSocketToServiceRecord(SPP_UUID)
                    }
                    candidate = sock
                    synchronized(stateLock) {
                        if (generation != attempt || !continuation.isActive) return@launch
                        pendingSocket = sock
                    }
                    sock.connect()
                    val connected = Session(sock, sock.inputStream, sock.outputStream)
                    synchronized(stateLock) {
                        if (generation != attempt || !continuation.isActive) return@launch
                        pendingSocket = null
                        session = connected
                        _isConnected.value = true
                        startReceive(connected)
                        candidate = null // ownership transferred to session
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Audio channel connect failed", e)
                } finally {
                    candidate?.let { close(it) }
                    synchronized(stateLock) {
                        if (generation == attempt) pendingSocket = null
                    }
                    if (continuation.isActive) continuation.resume(Unit)
                }
            }
            continuation.invokeOnCancellation {
                synchronized(stateLock) {
                    if (generation == attempt) {
                        ++generation
                        close(pendingSocket)
                        pendingSocket = null
                        session?.let { close(it.socket) }
                        session = null
                        _isConnected.value = false
                        _isReceiving.value = false
                    }
                }
                worker.cancel()
            }
            worker.start()
        }
    }

    override suspend fun disconnect() = withContext(NonCancellable + Dispatchers.IO) {
        txMutex.withLock {
            // Send END_AUDIO while a healthy socket is open; blocked writers are force-closed below.
            stopTransmitLocked()
            val rx = synchronized(stateLock) {
                ++generation
                close(pendingSocket)
                pendingSocket = null
                session?.let { close(it.socket) } // unblock socket IO before joining
                session = null
                _isConnected.value = false
                _isReceiving.value = false
                receiveJob.also { receiveJob = null }
            }
            rx?.cancel()
            if (rx != null && withTimeoutOrNull(STOP_TIMEOUT_MS) { rx.join(); true } != true) {
                Log.w(TAG, "RX teardown exceeded timeout")
            }
        }
    }

    /** Permanently release this channel and its coroutine scope. */
    override suspend fun close() {
        try { disconnect() } finally { scope.cancel() }
    }

    /** Wait for microphone startup; true means capture started, while isTransmitting tracks its ongoing state. */
    override suspend fun startTransmit(): Boolean = txMutex.withLock {
        if (transmitJob?.isCompleted == false) return@withLock false
        val connected = synchronized(stateLock) { session } ?: return@withLock false
        val started = CompletableDeferred<Boolean>()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var record: AudioRecord? = null
            try {
                val minSize = AudioRecord.getMinBufferSize(32000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                check(minSize > 0) { "Unsupported microphone PCM format: $minSize" }
                val capture = AudioRecord(MediaRecorder.AudioSource.MIC, 32000,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minSize, SbcCodec.PCM_BYTES_PER_FRAME * 4))
                record = capture
                check(capture.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord initialization failed" }
                val captureContext = currentCoroutineContext()
                synchronized(captureLock) {
                    captureContext.ensureActive()
                    recorder = capture
                    capture.startRecording()
                    check(capture.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone did not start" }
                    _isTransmitting.value = true
                    started.complete(true)
                }
                codec.resetEncoder()
                val pcm = ShortArray(SbcCodec.SAMPLES_PER_FRAME)
                var filled = 0
                while (isActive && synchronized(stateLock) { session === connected }) {
                    val count = capture.read(pcm, filled, pcm.size - filled)
                    if (count <= 0) throw IOException("Microphone read failed: $count")
                    filled += count
                    if (filled < pcm.size) continue
                    currentCoroutineContext().ensureActive()
                    val encoded = codec.encode(pcm) ?: throw IOException("SBC encoding failed")
                    connected.output.write(escapeFrame(0x00, encoded))
                    connected.output.flush()
                    filled = 0
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "TX failed", e)
            } finally {
                started.complete(false)
                synchronized(captureLock) {
                    if (recorder === record) recorder = null
                    try { record?.stop() } catch (_: Exception) {}
                    try { record?.release() } catch (_: Exception) {}
                    _isTransmitting.value = false
                }
                // Always use this job's stream; an old job must never write to a new session.
                try {
                    connected.output.write(END_AUDIO)
                    connected.output.flush()
                } catch (e: Exception) {
                    Log.d(TAG, "End-audio marker could not be sent", e)
                }

            }
        }
        transmitJob = job
        transmitSession = connected
        job.start()
        try {
            val ready = withTimeoutOrNull(CAPTURE_START_TIMEOUT_MS) { started.await() } == true
            if (!ready) {
                withContext(NonCancellable + Dispatchers.IO) { stopTransmitLocked() }
            }
            ready
        } catch (e: CancellationException) {
            withContext(NonCancellable + Dispatchers.IO) { stopTransmitLocked() }
            throw e
        }
    }

    /** Join the old capture job before accepting another PTT press. */
    override suspend fun stopTransmit() = withContext(NonCancellable + Dispatchers.IO) {
        txMutex.withLock { stopTransmitLocked() }
    }

    private suspend fun stopTransmitLocked() {
        val job = transmitJob ?: return
        job.cancel()
        synchronized(captureLock) {
            _isTransmitting.value = false
            try { recorder?.stop() } catch (_: Exception) {}
        }
        if (withTimeoutOrNull(STOP_TIMEOUT_MS) { job.join(); true } != true) {
            // A blocked output write cannot be cancelled by Job.cancel(). Close the transport.
            synchronized(stateLock) {
                transmitSession?.let { close(it.socket) }
                if (session === transmitSession) {
                    session = null
                    _isConnected.value = false
                    _isReceiving.value = false
                }
            }
            if (withTimeoutOrNull(STOP_TIMEOUT_MS) { job.join(); true } != true) {
                Log.w(TAG, "TX teardown exceeded timeout; new TX remains disabled until it exits")
                return // retain job so a new TX cannot race its finally block
            }
        }
        transmitJob = null
        transmitSession = null
    }

    // Called with stateLock held; each RX job owns its stream and playback track.
    private fun startReceive(connected: Session) {
        receiveJob = scope.launch {
            var track: AudioTrack? = null
            try {
                val minSize = AudioTrack.getMinBufferSize(32000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                check(minSize > 0) { "Unsupported playback PCM format: $minSize" }
                val playback = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(32000)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(maxOf(minSize, SbcCodec.PCM_BYTES_PER_FRAME * 4))
                    .setTransferMode(AudioTrack.MODE_STREAM).build()
                track = playback
                check(playback.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack initialization failed" }
                codec.resetDecoder()
                playback.play()
                synchronized(stateLock) {
                    if (session !== connected) return@launch
                    _isReceiving.value = true
                }
                val readBuffer = ByteArray(1024)
                val frame = ByteArrayOutputStream(MAX_RX_FRAME_BYTES)
                var collecting = false
                while (isActive) {
                    val count = connected.input.read(readBuffer)
                    if (count < 0) break
                    if (count == 0) throw IOException("Audio stream returned an empty read")
                    for (index in 0 until count) {
                        currentCoroutineContext().ensureActive()
                        val value = readBuffer[index].toInt() and 0xff
                        if (value == 0x7e) {
                            if (collecting && frame.size() > 0) playFrame(frame.toByteArray(), playback)
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "RX failed", e)
            } finally {
                try { track?.stop() } catch (_: Exception) {}
                try { track?.release() } catch (_: Exception) {}
                synchronized(stateLock) {
                    if (session === connected) {
                        close(connected.socket)
                        session = null
                        _isConnected.value = false
                        _isReceiving.value = false
                    }
                }
                scope.launch {
                    txMutex.withLock {
                        if (transmitSession === connected) stopTransmitLocked()
                    }
                }
            }
        }
    }

    internal suspend fun playFrame(frame: ByteArray, track: AudioTrack) {
        if (frame.isEmpty()) return
        if (frame[0] == 1.toByte()) {
            codec.resetDecoder()
            return
        }
        // HTCommander 09dfa3e: 0x00 and 0x03 are RX; 0x09 is our TX echo.
        if (frame.size < 2 || (frame[0] != 0.toByte() && frame[0] != 3.toByte())) return
        val payload = unescapeFrame(frame, 1) ?: return
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
                    if (count == 0) kotlinx.coroutines.delay(5) else written += count
                }
            }
            offset += length
        }
    }

    private fun unescapeFrame(data: ByteArray, start: Int): ByteArray? =
        AudioFraming.unescape(data, start)

    private fun escapeFrame(cmd: Byte, data: ByteArray): ByteArray =
        AudioFraming.escape(cmd, data)

    private fun close(socket: BluetoothSocket?) {
        try { socket?.close() } catch (_: IOException) {}
    }
}
