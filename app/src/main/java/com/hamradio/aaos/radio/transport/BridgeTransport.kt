package com.hamradio.aaos.radio.transport

import android.util.Log
import com.hamradio.aaos.radio.protocol.BenshiMessage
import com.hamradio.aaos.radio.protocol.GaiaFrame
import com.hamradio.aaos.radio.protocol.RadioCommands
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

private const val TAG = "BridgeTransport"
const val BRIDGE_PREFIX = "bridge:"

/** True for any ESP32 bridge address ("BRIDGE:", "BRIDGE:AUTO", "BRIDGE:host[:port]"). */
fun isBridgeAddress(address: String?): Boolean = address?.startsWith(BRIDGE_PREFIX, ignoreCase = true) == true
const val BRIDGE_CONTROL_PORT = 8701
private const val CONNECT_TIMEOUT_MS = 10_000
private const val IDLE_PING_MS = 10_000

/**
 * The ESP32 bridge (tools/esp32-bridge) holds the classic Bluetooth link to the radio and
 * passes its control channel through TCP untouched, so this speaks the same GAIA framing
 * as [RfcommTransport]. Used where the head unit can't pair with the radio itself (GM).
 *
 * Address form: "bridge:host" or "bridge:host:port". "bridge:" alone (or "bridge:auto") scans
 * the head unit's networks for the bridge, since its hotspot address isn't visible in the car.
 * The controller owns reconnection.
 */
class BridgeTransport(
    val address: String,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : IRadioTransport {
    private class Connection(val socket: Socket) {
        var output: OutputStream? = null
        var reader: Job? = null
        val writes = Mutex()
        val receiveBuffer = ByteArrayOutputStream(1024)
    }

    private val ownership = Any()
    private var current: Connection? = null
    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState = _state.asStateFlow()
    private val _inbound = MutableSharedFlow<BenshiMessage>(extraBufferCapacity = 64)
    override val inboundMessages: Flow<BenshiMessage> = _inbound.asSharedFlow()
    override val isConnected: Boolean get() = _state.value == ConnectionState.CONNECTED

    val host: String
    val port: Int

    init {
        // The manual address field upper-cases what is typed, so accept "BRIDGE:" too.
        val rest = if (address.startsWith(BRIDGE_PREFIX, ignoreCase = true)) address.substring(BRIDGE_PREFIX.length) else address
        val colon = rest.lastIndexOf(':')
        val parsedPort = if (colon > 0) rest.substring(colon + 1).toIntOrNull() else null
        host = if (parsedPort != null) rest.substring(0, colon) else rest
        port = parsedPort ?: BRIDGE_CONTROL_PORT
    }

    val autoDiscover: Boolean get() = host.isBlank() || host.equals("auto", ignoreCase = true)

    /** Last address the scan found, tried first on reconnect so a brief drop doesn't rescan. */
    @Volatile private var discoveredHost: String? = null

    /** Host of the live control connection (the scanned address when auto-discovering), else null. */
    @Volatile var connectedHost: String? = null
        private set

    private suspend fun resolveHost(): String {
        if (!autoDiscover) return host
        discoveredHost?.let { return it }
        return BridgeDiscovery.find(BridgeDiscovery.localCandidates(), port)
            ?.also { discoveredHost = it }
            ?: throw IOException("no bridge answering on port $port on this device's networks")
    }

    override suspend fun connect(): Unit = withContext(Dispatchers.IO) {
        val old = synchronized(ownership) { current.also { current = null; connectedHost = null } }
        old?.let { close(it) }
        _state.value = ConnectionState.CONNECTING
        val connection = Connection(Socket())
        synchronized(ownership) { current = connection }
        var target = host
        try {
            target = resolveHost()
            connection.socket.tcpNoDelay = true
            connection.socket.connect(InetSocketAddress(target, port), CONNECT_TIMEOUT_MS)
            connection.socket.soTimeout = IDLE_PING_MS
            connection.output = connection.socket.getOutputStream()
            val started = synchronized(ownership) {
                if (current !== connection) return@synchronized false
                connectedHost = target
                _state.value = ConnectionState.CONNECTED
                connection.reader = scope.launch { readLoop(connection) }
                true
            }
            if (!started) close(connection)
            else Log.i(TAG, "Bridge connected at $target:$port")
        } catch (e: IOException) {
            // A remembered address may have changed with a new DHCP lease; rescan next time.
            discoveredHost = null
            Log.e(TAG, "Bridge connect to ${target.ifBlank { "auto" }}:$port failed: ${describe(e)}")
            finish(connection, ConnectionState.ERROR)
            throw e
        }
    }

    override suspend fun send(message: BenshiMessage) {
        val connection = synchronized(ownership) {
            current?.takeIf { _state.value == ConnectionState.CONNECTED }
        } ?: return
        try {
            write(connection, GaiaFrame.encode(message))
        } catch (e: IOException) {
            Log.e(TAG, "Bridge write failed: ${e.message}")
            finish(connection, ConnectionState.ERROR)
        }
    }

    private suspend fun write(connection: Connection, bytes: ByteArray) = withContext(Dispatchers.IO) {
        connection.writes.withLock {
            val output = synchronized(ownership) { if (current === connection) connection.output else null } ?: return@withLock
            output.write(bytes)
            output.flush()
        }
    }

    override suspend fun disconnect() {
        val connection = synchronized(ownership) {
            val old = current
            current = null
            connectedHost = null
            _state.value = ConnectionState.DISCONNECTED
            old
        }
        connection?.let { close(it) }
        Log.i(TAG, "Bridge disconnected")
    }

    private suspend fun readLoop(connection: Connection) {
        val buf = ByteArray(1024)
        try {
            val input = connection.socket.getInputStream()
            while (owns(connection)) {
                val n = try {
                    input.read(buf)
                } catch (_: SocketTimeoutException) {
                    // Quiet link: a status poll proves the bridge and the radio are both still there.
                    write(connection, GaiaFrame.encode(RadioCommands.getHtStatus()))
                    continue
                }
                if (n == -1) break
                connection.receiveBuffer.write(buf, 0, n)
                processReceiveBuffer(connection)
            }
        } catch (e: IOException) {
            if (owns(connection)) Log.e(TAG, "Bridge read/ping error: ${e.message}")
        } finally {
            finish(connection, ConnectionState.ERROR)
        }
    }

    private fun processReceiveBuffer(connection: Connection) {
        val buffer = connection.receiveBuffer
        val data = buffer.toByteArray()
        var offset = 0
        while (offset < data.size) {
            val (consumed, message) = GaiaFrame.decode(data, offset)
            when {
                consumed > 0 -> {
                    if (message != null) synchronized(ownership) {
                        if (current === connection) _inbound.tryEmit(message)
                    }
                    offset += consumed
                }
                consumed == 0 -> break
                else -> offset += -consumed
            }
        }
        buffer.reset()
        if (offset < data.size) buffer.write(data, offset, data.size - offset)
    }

    private fun owns(connection: Connection): Boolean = synchronized(ownership) { current === connection }

    private fun finish(connection: Connection, state: ConnectionState) {
        synchronized(ownership) {
            if (current === connection) {
                current = null
                connectedHost = null
                _state.value = state
            }
        }
        close(connection)
    }

    private fun close(connection: Connection) {
        val reader = synchronized(ownership) { connection.reader.also { connection.reader = null; connection.output = null } }
        reader?.cancel()
        // Closing the socket aborts a blocked connect or read.
        try { connection.socket.close() } catch (_: IOException) {}
    }
}
