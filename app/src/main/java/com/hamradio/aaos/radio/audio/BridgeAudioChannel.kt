package com.hamradio.aaos.radio.audio

import android.media.AudioTrack
import android.util.Log
import com.hamradio.aaos.radio.transport.BridgeTransport
import com.hamradio.aaos.radio.transport.describe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "BridgeAudio"
const val BRIDGE_AUDIO_PORT = 8702
private const val CONNECT_TIMEOUT_MS = 10_000
private const val STOP_TIMEOUT_MS = 1500L
/** A TX write blocked this long closes the socket, so the ESP32 sees the drop and unkeys. */
private const val WRITE_STALL_MS = 1500L

/**
 * Radio audio through the ESP32 bridge, which relays the radio's audio RFCOMM channel byte for
 * byte on TCP [BRIDGE_AUDIO_PORT]. Receive and transmit use the same pipelines as classic
 * Bluetooth ([AudioReceivePipeline], [AudioTransmitPipeline]).
 *
 * TX safety: only [startTransmit] writes to the socket. Every TX exit path writes END_AUDIO on the
 * same socket; a write blocked past [WRITE_STALL_MS] closes the socket; disconnect stops TX (and
 * sends END_AUDIO) before closing.
 */
class BridgeAudioChannel internal constructor(
    private val resolveHost: () -> String?,
    private val port: Int = BRIDGE_AUDIO_PORT,
    openTrack: () -> AudioTrack = ::openPlaybackTrack,
    openSource: () -> PcmSource = ::AndroidMicPcmSource,
) : RadioAudioControl {
    /** Audio for [transport], on the host its control link actually reached (scanned when auto). */
    constructor(transport: BridgeTransport) : this({
        transport.connectedHost ?: transport.host.takeUnless { transport.autoDiscover }
    })

    private val codec = SbcCodecSession()
    private val receiver = AudioReceivePipeline(codec, openTrack)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val transmitter = AudioTransmitPipeline(scope, codec, TAG, openSource, writeTimeoutMs = WRITE_STALL_MS)
    private val stateLock = Any()
    private val connectMutex = Mutex()
    private val txMutex = Mutex()
    private var generation = 0L
    private var pendingSocket: Socket? = null
    private class Session(val socket: Socket, val input: InputStream, val output: OutputStream)
    private var session: Session? = null
    private var receiveJob: Job? = null

    override val isTransmitting: StateFlow<Boolean> = transmitter.isTransmitting
    private val _isReceiving = MutableStateFlow(false)
    val isReceiving: StateFlow<Boolean> = _isReceiving.asStateFlow()
    private val _isConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    override suspend fun connect() = connectMutex.withLock {
        val socket = Socket()
        val attempt = synchronized(stateLock) {
            if (session != null) return@withLock
            pendingSocket = socket
            ++generation
        }
        var target: String? = null
        try {
            target = resolveHost() ?: throw IOException("bridge control link has no connected host")
            val address = InetSocketAddress(target, port)
            // Socket.connect blocks without observing cancellation; closing the socket aborts it.
            suspendCancellableCoroutine<Unit> { continuation ->
                continuation.invokeOnCancellation { close(socket) }
                scope.launch {
                    try {
                        socket.tcpNoDelay = true
                        socket.connect(address, CONNECT_TIMEOUT_MS)
                        continuation.resume(Unit)
                    } catch (e: Exception) {
                        continuation.resumeWithException(e)
                    }
                }
            }
            val connected = Session(socket, socket.getInputStream(), socket.getOutputStream())
            synchronized(stateLock) {
                if (generation != attempt) throw IOException("bridge audio connect superseded")
                pendingSocket = null
                session = connected
                _isConnected.value = true
                startReceive(connected)
            }
            Log.i(TAG, "Bridge audio connected at $target:$port")
        } catch (e: CancellationException) {
            close(socket)
            throw e
        } catch (e: Exception) {
            close(socket)
            Log.e(TAG, "Bridge audio connect to ${target ?: "?"}:$port failed: ${describe(e)}")
        } finally {
            synchronized(stateLock) { if (pendingSocket === socket) pendingSocket = null }
        }
    }

    override suspend fun disconnect() = withContext(NonCancellable + Dispatchers.IO) {
        txMutex.withLock {
            // Send END_AUDIO while the socket is healthy; a blocked writer is force-closed instead.
            transmitter.stopLocked()
            val rx = synchronized(stateLock) {
                ++generation
                close(pendingSocket)
                pendingSocket = null
                session?.let { close(it.socket) } // unblock the read before joining
                session = null
                _isConnected.value = false
                _isReceiving.value = false
                receiveJob.also { receiveJob = null }
            }
            rx?.cancel()
            if (rx != null && withTimeoutOrNull(STOP_TIMEOUT_MS) { rx.join(); true } != true) {
                Log.w(TAG, "Bridge RX teardown exceeded timeout")
            }
        }
    }

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
            forceClose = { drop(connected) },
        )
    }

    /** Join the old capture job (END_AUDIO sent) before accepting another PTT press. */
    override suspend fun stopTransmit() = withContext(NonCancellable + Dispatchers.IO) {
        txMutex.withLock { transmitter.stopLocked() }
    }

    /** Close a session whose TX write is stuck; the ESP32 sees the TCP drop. */
    private fun drop(connected: Session) {
        synchronized(stateLock) {
            close(connected.socket)
            if (session === connected) {
                session = null
                _isConnected.value = false
                _isReceiving.value = false
            }
        }
    }

    // Called with stateLock held.
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
                Log.e(TAG, "Bridge RX failed: ${describe(e)}")
            } finally {
                synchronized(stateLock) {
                    if (session === connected) {
                        close(connected.socket)
                        session = null
                        _isConnected.value = false
                        _isReceiving.value = false
                    }
                }
                // The link is gone: end TX on it (END_AUDIO is attempted on its own stream).
                scope.launch {
                    txMutex.withLock {
                        if (transmitter.isOwnedBy(connected)) transmitter.stopLocked()
                    }
                }
            }
        }
    }

    private fun close(socket: Socket?) {
        try { socket?.close() } catch (_: IOException) {}
    }
}
