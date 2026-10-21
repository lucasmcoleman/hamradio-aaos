package com.hamradio.aaos.radio.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import com.hamradio.aaos.radio.protocol.BenshiMessage
import com.hamradio.aaos.radio.protocol.GaiaFrame
import com.hamradio.aaos.radio.protocol.RadioCommands
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "RfcommTransport"
private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

/**
 * Classic Bluetooth RFCOMM transport for GAIA-framed Benshi messages.
 * Each connect attempt owns its resources before blocking in BluetoothSocket.connect.
 * The controller owns reconnection; this transport never schedules retries.
 */
@SuppressLint("MissingPermission")
class RfcommTransport(
    private val context: Context,
    val deviceAddress: String,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : IRadioTransport {
    // Identity is the generation token. Detached attempts may only close their own resources.
    private class Connection {
        var socket: BluetoothSocket? = null
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

    override suspend fun connect(): Unit = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            val attempt = Connection()
            val previous = synchronized(ownership) {
                val old = current
                current = attempt
                _state.value = ConnectionState.CONNECTING
                old
            }
            // Coroutine cancellation must close the socket even while connect() blocks.
            continuation.invokeOnCancellation { finish(attempt, ConnectionState.DISCONNECTED) }
            previous?.let { close(it) }
            try {
                if (!continuation.isActive || !owns(attempt)) return@suspendCancellableCoroutine
                val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                val adapter = manager?.adapter ?: throw IOException("BluetoothAdapter not available")
                adapter.cancelDiscovery()
                val device = adapter.getRemoteDevice(deviceAddress)
                val socket = try {
                    device.createRfcommSocketToServiceRecord(SPP_UUID)
                } catch (e: IOException) {
                    Log.w(TAG, "SPP socket creation failed, trying insecure fallback", e)
                    device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
                }
                val attached = synchronized(ownership) {
                    if (current === attempt && continuation.isActive) {
                        attempt.socket = socket
                        true
                    } else false
                }
                if (!attached) {
                    try { socket.close() } catch (_: IOException) {}
                } else {
                    socket.connect()
                    val input = socket.inputStream
                    val output = socket.outputStream
                    val reader = scope.launch(start = CoroutineStart.LAZY) { readLoop(attempt, input) }
                    val published = synchronized(ownership) {
                        if (current === attempt && continuation.isActive) {
                            attempt.output = output
                            attempt.reader = reader
                            _state.value = ConnectionState.CONNECTED
                            true
                        } else false
                    }
                    if (published) {
                        reader.invokeOnCompletion { finish(attempt, ConnectionState.ERROR) }
                        reader.start()
                        Log.i(TAG, "RFCOMM connected to $deviceAddress")
                    } else {
                        reader.cancel()
                        close(attempt)
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "RFCOMM connect failed: ${e.message}")
                finish(attempt, ConnectionState.ERROR)
            } catch (e: SecurityException) {
                Log.e(TAG, "Missing Bluetooth permission", e)
                finish(attempt, ConnectionState.ERROR)
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "Invalid Bluetooth device address", e)
                finish(attempt, ConnectionState.ERROR)
            } finally {
                if (continuation.isActive) continuation.resume(Unit)
            }
        }
    }

    override suspend fun send(message: BenshiMessage) {
        val connection = synchronized(ownership) {
            current?.takeIf { _state.value == ConnectionState.CONNECTED }
        } ?: return
        try {
            write(connection, GaiaFrame.encode(message))
        } catch (e: IOException) {
            Log.e(TAG, "RFCOMM write failed: ${e.message}")
            finish(connection, ConnectionState.ERROR)
        }
    }

    private suspend fun write(connection: Connection, bytes: ByteArray) = withContext(Dispatchers.IO) {
        // Ping and normal commands use the same mutex, including flush.
        connection.writes.withLock {
            val output = synchronized(ownership) {
                if (current === connection && _state.value == ConnectionState.CONNECTED) connection.output else null
            } ?: return@withLock
            suspendCancellableCoroutine<Unit> { continuation ->
                // A coroutine timeout alone cannot interrupt a blocking Bluetooth write.
                // Closing this session aborts its IO without touching a replacement session.
                continuation.invokeOnCancellation { finish(connection, ConnectionState.ERROR) }
                if (continuation.isActive) {
                    try {
                        output.write(bytes)
                        output.flush()
                        if (continuation.isActive) continuation.resume(Unit)
                    } catch (e: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            }
        }
    }

    override suspend fun disconnect() {
        val connection = synchronized(ownership) {
            val old = current
            current = null
            _state.value = ConnectionState.DISCONNECTED
            old
        }
        connection?.let { close(it) }
        Log.i(TAG, "RFCOMM disconnected")
    }

    private suspend fun readLoop(connection: Connection, input: InputStream) {
        val buf = ByteArray(1024)
        var idleCount = 0
        try {
            while (currentCoroutineContext().isActive && owns(connection)) {
                val available = withContext(Dispatchers.IO) { input.available() }
                if (available > 0) {
                    idleCount = 0
                    val bytesRead = withContext(Dispatchers.IO) {
                        input.read(buf, 0, minOf(available, buf.size))
                    }
                    if (bytesRead == -1) break
                    if (bytesRead > 0) {
                        connection.receiveBuffer.write(buf, 0, bytesRead)
                        processReceiveBuffer(connection)
                    }
                } else {
                    delay(100)
                    if (++idleCount >= 100) {
                        idleCount = 0
                        write(connection, GaiaFrame.encode(RadioCommands.getHtStatus()))
                    }
                }
            }
        } catch (e: IOException) {
            if (owns(connection)) Log.e(TAG, "RFCOMM read/ping error: ${e.message}")
        } finally {
            // A stale reader cannot tear down or change the state of a newer attempt.
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
                _state.value = state
            }
        }
        close(connection)
    }

    private fun close(connection: Connection) {
        val resources = synchronized(ownership) {
            val pair = connection.reader to connection.socket
            connection.reader = null
            connection.socket = null
            connection.output = null
            pair
        }
        resources.first?.cancel()
        // Closing BluetoothSocket also closes its streams and aborts connect/read/write.
        try { resources.second?.close() } catch (_: IOException) {}
    }
}
