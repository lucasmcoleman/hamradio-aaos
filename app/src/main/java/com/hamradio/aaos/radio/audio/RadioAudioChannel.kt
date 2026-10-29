package com.hamradio.aaos.radio.audio

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.media.AudioTrack
import android.util.Log
import com.hamradio.aaos.radio.transport.describe
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlin.coroutines.resume

private const val TAG = "RadioAudio"
private const val STOP_TIMEOUT_MS = 1500L
private val GENERIC_AUDIO_UUID = UUID.fromString("00001203-0000-1000-8000-00805F9B34FB")
private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

/** Separate RFCOMM audio transport. Radio keying/end-audio semantics require device validation. */
@SuppressLint("MissingPermission")
class RadioAudioChannel(private val context: Context, private val deviceAddress: String) : RadioAudioControl {
    private val codec = SbcCodecSession()
    private val receiver = AudioReceivePipeline(codec)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val transmitter = AudioTransmitPipeline(scope, codec, TAG)
    private val stateLock = Any()
    private val txMutex = Mutex()
    private val connectMutex = Mutex()
    private var generation = 0L
    private var pendingSocket: BluetoothSocket? = null
    private data class Session(val socket: BluetoothSocket, val input: InputStream, val output: OutputStream)
    private var session: Session? = null
    private var receiveJob: Job? = null

    override val isTransmitting: StateFlow<Boolean> = transmitter.isTransmitting
    private val _isReceiving = MutableStateFlow(false)
    val isReceiving: StateFlow<Boolean> = _isReceiving.asStateFlow()
    private val _isConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

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
                    // Fall back on connect failures too, not just socket creation (codex #940).
                    val attempts = listOf<Pair<String, () -> BluetoothSocket>>(
                        "audio" to { device.createRfcommSocketToServiceRecord(GENERIC_AUDIO_UUID) },
                        "audio insecure" to { device.createInsecureRfcommSocketToServiceRecord(GENERIC_AUDIO_UUID) },
                        "spp" to { device.createRfcommSocketToServiceRecord(SPP_UUID) },
                    )
                    val errors = mutableListOf<String>()
                    var sock: BluetoothSocket? = null
                    for ((label, open) in attempts) {
                        val next = try {
                            open()
                        } catch (e: IOException) {
                            errors += "$label: ${describe(e)}"
                            continue
                        }
                        candidate = next
                        synchronized(stateLock) {
                            if (generation != attempt || !continuation.isActive) return@launch
                            pendingSocket = next
                        }
                        try {
                            next.connect()
                            sock = next
                            break
                        } catch (e: IOException) {
                            errors += "$label: ${describe(e)}"
                            close(next)
                            candidate = null
                            synchronized(stateLock) {
                                if (generation != attempt || !continuation.isActive) return@launch
                                pendingSocket = null
                            }
                        }
                    }
                    if (sock == null) throw IOException("audio link failed (${errors.joinToString("; ")})")
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
        val connected = synchronized(stateLock) { session } ?: return@withLock false
        transmitter.startLocked(
            owner = connected,
            output = connected.output,
            stillCurrent = { synchronized(stateLock) { session === connected } },
            forceClose = {
                synchronized(stateLock) {
                    close(connected.socket)
                    if (session === connected) {
                        session = null
                        _isConnected.value = false
                        _isReceiving.value = false
                    }
                }
            },
        )
    }

    /** Join the old capture job before accepting another PTT press. */
    override suspend fun stopTransmit() = withContext(NonCancellable + Dispatchers.IO) {
        txMutex.withLock { stopTransmitLocked() }
    }

    private suspend fun stopTransmitLocked() = transmitter.stopLocked()

    // Called with stateLock held; each RX job owns its stream and playback track.
    private fun startReceive(connected: Session) {
        receiveJob = scope.launch {
            try {
                receiver.play(connected.input) {
                    synchronized(stateLock) {
                        if (session !== connected) return@play false
                        _isReceiving.value = true
                        true
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "RX failed", e)
            } finally {
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
                        if (transmitter.isOwnedBy(connected)) stopTransmitLocked()
                    }
                }
            }
        }
    }

    internal suspend fun playFrame(frame: ByteArray, track: AudioTrack) = receiver.playFrame(frame, track)

    private fun close(socket: BluetoothSocket?) {
        try { socket?.close() } catch (_: IOException) {}
    }
}
