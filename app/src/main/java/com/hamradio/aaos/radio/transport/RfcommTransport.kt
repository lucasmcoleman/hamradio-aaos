package com.hamradio.aaos.radio.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import com.hamradio.aaos.radio.protocol.BenshiMessage
import com.hamradio.aaos.radio.protocol.GaiaFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

private const val TAG = "RfcommTransport"

// Standard SPP (Serial Port Profile) UUID
private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

/**
 * Classic Bluetooth RFCOMM transport for Benshi-protocol radios.
 *
 * Messages are wrapped in GAIA frames (sync + version + flags + length + payload).
 * The radio exposes an SPP serial port that accepts GAIA-framed Benshi commands.
 *
 * Connection flow:
 * 1. Get BluetoothDevice by MAC address
 * 2. Create RFCOMM socket via SPP UUID
 * 3. Connect socket (blocks until connected or fails)
 * 4. Start read loop on input stream
 * 5. Send commands by writing GAIA-framed bytes to output stream
 */
@SuppressLint("MissingPermission")
class RfcommTransport(
    private val context: Context,
    private val deviceAddress: String,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : IRadioTransport {

    private var socket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var readJob: Job? = null

    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState = _state.asStateFlow()

    private val _inbound = MutableSharedFlow<BenshiMessage>(extraBufferCapacity = 64)
    override val inboundMessages: Flow<BenshiMessage> = _inbound.asSharedFlow()

    override val isConnected: Boolean get() = _state.value == ConnectionState.CONNECTED

    // Receive buffer for GAIA frame reassembly
    private val receiveBuffer = java.io.ByteArrayOutputStream(1024)

    override suspend fun connect() = withContext(Dispatchers.IO) {
        try {
            _state.value = ConnectionState.CONNECTING

            val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = btManager?.adapter
            if (adapter == null) {
                Log.e(TAG, "BluetoothAdapter not available")
                _state.value = ConnectionState.ERROR
                return@withContext
            }

            // Cancel any ongoing discovery (it slows down connection)
            adapter.cancelDiscovery()

            val device = adapter.getRemoteDevice(deviceAddress)
            Log.i(TAG, "Connecting to ${device.name ?: "unknown"} ($deviceAddress) via RFCOMM")

            // Try SPP UUID first, fall back to insecure connection
            val sock = try {
                device.createRfcommSocketToServiceRecord(SPP_UUID)
            } catch (e: IOException) {
                Log.w(TAG, "SPP UUID failed, trying insecure fallback", e)
                device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
            }

            sock.connect()  // Blocking call — waits for radio to accept
            socket = sock
            inputStream = sock.inputStream
            outputStream = sock.outputStream

            _state.value = ConnectionState.CONNECTED
            Log.i(TAG, "RFCOMM connected to $deviceAddress")

            // Start reading incoming GAIA frames
            readJob = scope.launch { readLoop() }

        } catch (e: IOException) {
            Log.e(TAG, "RFCOMM connect failed: ${e.message}")
            closeSocket()
            _state.value = ConnectionState.ERROR
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing Bluetooth permission", e)
            _state.value = ConnectionState.ERROR
        }
    }

    override suspend fun send(message: BenshiMessage) {
        if (!isConnected) return
        val frame = GaiaFrame.encode(message)
        withContext(Dispatchers.IO) {
            try {
                outputStream?.write(frame)
                outputStream?.flush()
            } catch (e: IOException) {
                Log.e(TAG, "RFCOMM write failed: ${e.message}")
                handleDisconnect()
            }
        }
    }

    override suspend fun disconnect() {
        readJob?.cancel()
        readJob = null
        closeSocket()
        _state.value = ConnectionState.DISCONNECTED
        Log.i(TAG, "RFCOMM disconnected")
    }

    // -----------------------------------------------------------------------
    // Read loop — parses GAIA frames from the RFCOMM input stream
    // -----------------------------------------------------------------------

    private suspend fun readLoop() {
        val buf = ByteArray(1024)
        try {
            while (scope.isActive) {
                val stream = inputStream ?: break
                val bytesRead = withContext(Dispatchers.IO) {
                    stream.read(buf)
                }
                if (bytesRead == -1) {
                    Log.w(TAG, "RFCOMM stream ended")
                    break
                }
                if (bytesRead > 0) {
                    receiveBuffer.write(buf, 0, bytesRead)
                    processReceiveBuffer()
                }
            }
        } catch (e: IOException) {
            if (scope.isActive) {
                Log.e(TAG, "RFCOMM read error: ${e.message}")
            }
        }
        handleDisconnect()
    }

    private fun processReceiveBuffer() {
        val data = receiveBuffer.toByteArray()
        var offset = 0

        while (offset < data.size) {
            val (consumed, message) = GaiaFrame.decode(data, offset)

            when {
                consumed > 0 && message != null -> {
                    // Successfully decoded a frame
                    _inbound.tryEmit(message)
                    offset += consumed
                }
                consumed > 0 -> {
                    // Consumed bytes but no valid message (corrupted frame)
                    offset += consumed
                }
                consumed == 0 -> {
                    // Incomplete frame — keep remaining bytes for next read
                    break
                }
                else -> {
                    // Sync error — skip bytes to next sync byte
                    offset += -consumed
                }
            }
        }

        // Keep unconsumed bytes in the buffer
        receiveBuffer.reset()
        if (offset < data.size) {
            receiveBuffer.write(data, offset, data.size - offset)
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun handleDisconnect() {
        if (_state.value == ConnectionState.CONNECTED) {
            _state.value = ConnectionState.ERROR
            closeSocket()
        }
    }

    private fun closeSocket() {
        try { inputStream?.close() } catch (_: IOException) {}
        try { outputStream?.close() } catch (_: IOException) {}
        try { socket?.close() } catch (_: IOException) {}
        inputStream = null
        outputStream = null
        socket = null
    }
}
