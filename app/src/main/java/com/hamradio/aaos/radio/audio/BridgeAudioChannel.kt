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
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "BridgeAudio"
const val BRIDGE_AUDIO_PORT = 8702
private const val CONNECT_TIMEOUT_MS = 10_000
private const val STOP_TIMEOUT_MS = 1500L

/**
 * Receive-only radio audio through the ESP32 bridge, which relays the radio's audio RFCOMM
 * channel byte for byte on TCP [BRIDGE_AUDIO_PORT].
 *
 * SAFETY: this channel must never key the radio. It has no transmit path: [startTransmit]
 * always returns false, and the socket's write side is never obtained or used anywhere here.
 */
class BridgeAudioChannel internal constructor(
    private val resolveHost: () -> String?,
    private val port: Int = BRIDGE_AUDIO_PORT,
    openTrack: () -> AudioTrack = ::openPlaybackTrack,
) : RadioAudioControl {
    /** Audio for [transport], on the host its control link actually reached (scanned when auto). */
    constructor(transport: BridgeTransport) : this({
        transport.connectedHost ?: transport.host.takeUnless { transport.autoDiscover }
    })

    private val receiver = AudioReceivePipeline(SbcCodecSession(), openTrack)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateLock = Any()
    private val connectMutex = Mutex()
    private var generation = 0L
    private var pendingSocket: Socket? = null
    private class Session(val socket: Socket, val input: InputStream)
    private var session: Session? = null
    private var receiveJob: Job? = null

    override val isTransmitting: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()
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
            val connected = Session(socket, socket.getInputStream())
            synchronized(stateLock) {
                if (generation != attempt) throw IOException("bridge audio connect superseded")
                pendingSocket = null
                session = connected
                _isConnected.value = true
                startReceive(connected)
            }
            Log.i(TAG, "Bridge audio connected at $target:$port (receive only)")
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

    override suspend fun close() {
        try { disconnect() } finally { scope.cancel() }
    }

    /** Never transmits through the bridge: the real radio must not be keyed from this path. */
    override suspend fun startTransmit(): Boolean = false

    override suspend fun stopTransmit() {}

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
            }
        }
    }

    private fun close(socket: Socket?) {
        try { socket?.close() } catch (_: IOException) {}
    }
}
