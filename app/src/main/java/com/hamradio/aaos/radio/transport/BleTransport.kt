package com.hamradio.aaos.radio.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.util.Log
import com.hamradio.aaos.radio.protocol.BenshiMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID

private const val TAG = "BleTransport"
// Android allows one GATT write in flight. A write that hasn't been acked keeps the stack
// busy, so wait for the ack well past the first silence and retry "busy" starts instead of
// dropping the message (the 10-23 truck test lost every command after one slow ack).
private const val WRITE_ACK_TIMEOUT_MS = 6_000L
private const val WRITE_BUSY_RETRY_MS = 6_000L
private const val WRITE_BUSY_POLL_MS = 50L

// Benshi BLE GATT UUIDs (same across all Benshi-family radios)
private val SERVICE_UUID  = UUID.fromString("00001100-d102-11e1-9b23-00025b00a5a5")
private val WRITE_UUID    = UUID.fromString("00001101-d102-11e1-9b23-00025b00a5a5")
private val INDICATE_UUID = UUID.fromString("00001102-d102-11e1-9b23-00025b00a5a5")
private val CCCD_UUID     = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

/**
 * BLE GATT transport for Benshi messages (raw, no GAIA framing).
 *
 * Control only: the radio sends voice over classic Bluetooth, so there is no audio
 * channel over BLE. Used when a head unit refuses classic connections to non-phones.
 *
 * Connection flow: connect -> discover services -> negotiate MTU -> enable indications
 * (CCCD write confirmed) -> CONNECTED. [connect] suspends until that finishes or fails.
 * Each attempt owns a [Session]; callbacks from a stale session are ignored.
 */
@SuppressLint("MissingPermission")
class BleTransport(
    private val context: Context,
    val deviceAddress: String,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : IRadioTransport {

    private inner class Session {
        var gatt: BluetoothGatt? = null
        var writeCharacteristic: BluetoothGattCharacteristic? = null
        var writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        val ready = CompletableDeferred<Unit>()
        val sendChannel = Channel<ByteArray>(64)
        val writeAcks = Channel<Int>(Channel.CONFLATED)
        var sendJob: Job? = null
        val receiveBuffer = ByteArrayOutputStream()

        fun fail(reason: String) {
            Log.e(TAG, reason)
            if (!ready.isCompleted) ready.completeExceptionally(IOException(reason))
            else if (current === this) _connectionState.value = ConnectionState.ERROR
        }
    }

    @Volatile private var current: Session? = null

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState = _connectionState.asStateFlow()

    private val _inbound = MutableSharedFlow<BenshiMessage>(extraBufferCapacity = 64)
    override val inboundMessages: Flow<BenshiMessage> = _inbound.asSharedFlow()

    override val isConnected: Boolean
        get() = _connectionState.value == ConnectionState.CONNECTED

    private fun callbackFor(session: Session) = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (current !== session) return
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "GATT connected, discovering services")
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    session.writeCharacteristic = null
                    session.fail("BLE link dropped (status $status)")
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (current !== session) return
            if (status != BluetoothGatt.GATT_SUCCESS) return session.fail("BLE service discovery failed (status $status)")
            val service = gatt.getService(SERVICE_UUID)
                ?: return session.fail("radio has no Benshi BLE service")
            val writeChar = service.getCharacteristic(WRITE_UUID)
            if (service.getCharacteristic(INDICATE_UUID) == null || writeChar == null) {
                return session.fail("radio BLE characteristics missing")
            }
            session.writeCharacteristic = writeChar
            session.writeType = writeTypeFor(writeChar.properties)
            Log.i(TAG, "write characteristic props=0x%02X type=%d bond=%d".format(
                writeChar.properties, session.writeType, gatt.device.bondState))
            // Negotiate larger MTU before enabling indications
            if (!gatt.requestMtu(512)) enableIndications(gatt, session)
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (current !== session) return
            Log.d(TAG, "MTU negotiated: $mtu (status=$status)")
            enableIndications(gatt, session)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (current !== session) return
            if (status != BluetoothGatt.GATT_SUCCESS) return session.fail("BLE indication setup failed (status $status)")
            Log.i(TAG, "BLE connected to $deviceAddress")
            session.sendJob = scope.launch { sendWorker(session) }
            _connectionState.value = ConnectionState.CONNECTED
            session.ready.complete(Unit)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (current === session) session.writeAcks.trySend(status)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (current !== session || characteristic.uuid != INDICATE_UUID) return
            val buffer = session.receiveBuffer
            synchronized(buffer) {
                buffer.write(value)
                val buf = buffer.toByteArray()
                if (buf.size >= 4) {
                    BenshiMessage.decode(buf)?.let { msg ->
                        _inbound.tryEmit(msg)
                        buffer.reset()
                        return
                    }
                }
                // Prevent unbounded growth from unparseable data
                if (buffer.size() > 1024) {
                    Log.w(TAG, "Receive buffer overflow (${buffer.size()} bytes), clearing")
                    buffer.reset()
                }
            }
        }

        // Android < 13 callback
        @Suppress("OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            onCharacteristicChanged(gatt, characteristic, characteristic.value ?: return)
        }
    }

    private fun enableIndications(gatt: BluetoothGatt, session: Session) {
        val indicateChar = gatt.getService(SERVICE_UUID)?.getCharacteristic(INDICATE_UUID)
            ?: return session.fail("radio BLE characteristics missing")
        gatt.setCharacteristicNotification(indicateChar, true)
        val descriptor = indicateChar.getDescriptor(CCCD_UUID)
            ?: return session.fail("radio BLE indication descriptor missing")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
    }

    override suspend fun connect() {
        val session = Session()
        val previous = current
        current = session
        previous?.let { close(it) }
        _connectionState.value = ConnectionState.CONNECTING
        try {
            withContext(Dispatchers.IO) {
                val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                val adapter = manager?.adapter ?: throw IOException("BluetoothAdapter not available")
                val device = adapter.getRemoteDevice(deviceAddress)
                Log.d(TAG, "Connecting to $deviceAddress over BLE")
                session.gatt = device.connectGatt(context, false, callbackFor(session), BluetoothDevice.TRANSPORT_LE)
                    ?: throw IOException("BLE connect could not start")
            }
            session.ready.await()
        } catch (e: Exception) {
            if (current === session) {
                current = null
                _connectionState.value = if (e is IOException) ConnectionState.ERROR else ConnectionState.DISCONNECTED
            }
            close(session)
            throw e
        }
    }

    override suspend fun send(message: BenshiMessage) {
        val session = current?.takeIf { isConnected } ?: return
        session.sendChannel.trySend(message.encode())
    }

    override suspend fun disconnect() {
        val session = current
        current = null
        session?.let { close(it) }
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    private fun close(session: Session) {
        session.sendJob?.cancel()
        session.sendChannel.close()
        try {
            session.gatt?.disconnect()
            session.gatt?.close()
        } catch (e: SecurityException) {
            Log.w(TAG, "Missing Bluetooth permission on close", e)
        }
        session.gatt = null
        session.writeCharacteristic = null
        if (!session.ready.isCompleted) session.ready.completeExceptionally(IOException("BLE connect cancelled"))
    }

    /** GATT allows one outstanding write, so wait for each write's callback before the next. */
    private suspend fun sendWorker(session: Session) {
        for (bytes in session.sendChannel) {
            val char = session.writeCharacteristic
            val g = session.gatt
            if (char == null || g == null) {
                Log.w(TAG, "GATT or characteristic null, dropping message")
                continue
            }
            while (session.writeAcks.tryReceive().isSuccess) Unit   // a late ack from an earlier write
            val result = deliverWrite(
                tryStart = { startWrite(g, char, bytes, session.writeType) },
                awaitAck = { withTimeoutOrNull(WRITE_ACK_TIMEOUT_MS) { session.writeAcks.receive() } },
            )
            when (result) {
                WriteResult.Acked -> Unit
                WriteResult.NeverStarted -> Log.w(TAG, "BLE write could not start after ${WRITE_BUSY_RETRY_MS} ms, dropped (${bytes.toHexString()})")
                is WriteResult.Failed -> Log.w(TAG, "BLE write failed or timed out (status ${result.status}) (${bytes.toHexString()})")
            }
        }
    }

    private fun startWrite(g: BluetoothGatt, char: BluetoothGattCharacteristic, bytes: ByteArray, type: Int): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(char, bytes, type) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            char.writeType = type
            @Suppress("DEPRECATION")
            char.value = bytes
            @Suppress("DEPRECATION")
            g.writeCharacteristic(char)
        }
}

internal sealed class WriteResult {
    object Acked : WriteResult()
    object NeverStarted : WriteResult()
    data class Failed(val status: Int?) : WriteResult()
}

/** Write with a response when the radio offers it, else without. */
internal fun writeTypeFor(properties: Int): Int =
    if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE == 0 &&
        properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
    ) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
    else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

/**
 * One GATT write: retry the start while the stack reports busy (an earlier write is still in
 * flight), then wait for its ack. Status 0 is GATT_SUCCESS.
 */
internal suspend fun deliverWrite(
    tryStart: () -> Boolean,
    awaitAck: suspend () -> Int?,
    busyRetryMs: Long = WRITE_BUSY_RETRY_MS,
    pollMs: Long = WRITE_BUSY_POLL_MS,
): WriteResult {
    var waited = 0L
    while (!tryStart()) {
        if (waited >= busyRetryMs) return WriteResult.NeverStarted
        delay(pollMs)
        waited += pollMs
    }
    val status = awaitAck()
    return if (status == 0) WriteResult.Acked else WriteResult.Failed(status)
}

private fun ByteArray.toHexString() = joinToString(" ") { "%02x".format(it) }
