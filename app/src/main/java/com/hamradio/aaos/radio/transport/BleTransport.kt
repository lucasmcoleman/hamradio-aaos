package com.hamradio.aaos.radio.transport

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.UUID

private const val TAG = "BleTransport"

// Benshi BLE GATT UUIDs (same across all Benshi-family radios)
private val SERVICE_UUID  = UUID.fromString("00001100-d102-11e1-9b23-00025b00a5a5")
private val WRITE_UUID    = UUID.fromString("00001101-d102-11e1-9b23-00025b00a5a5")
private val INDICATE_UUID = UUID.fromString("00001102-d102-11e1-9b23-00025b00a5a5")
private val CCCD_UUID     = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

/**
 * BLE transport implementation using Android's BluetoothGatt API.
 *
 * Connection flow: discover services -> negotiate MTU -> enable indications
 * (CCCD write confirmed) -> CONNECTED. Incoming data is buffered for
 * reassembly in case BLE fragments large payloads across multiple indications.
 */
class BleTransport(
    private val context: Context,
    private val deviceAddress: String,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : IRadioTransport {

    private var gatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private val sendChannel = Channel<ByteArray>(Channel.BUFFERED)
    private var sendJob: Job? = null
    private val receiveBuffer = ByteArrayOutputStream()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState = _connectionState.asStateFlow()

    private val _inbound = MutableSharedFlow<BenshiMessage>(extraBufferCapacity = 64)
    override val inboundMessages: Flow<BenshiMessage> = _inbound.asSharedFlow()

    override val isConnected: Boolean
        get() = _connectionState.value == ConnectionState.CONNECTED

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "GATT connected, discovering services…")
                    _connectionState.value = ConnectionState.CONNECTING
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "GATT disconnected (status=$status)")
                    _connectionState.value = ConnectionState.DISCONNECTED
                    writeCharacteristic = null
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed: $status")
                _connectionState.value = ConnectionState.ERROR
                return
            }
            val service = gatt.getService(SERVICE_UUID)
            if (service == null) {
                Log.e(TAG, "Benshi service not found — is this a Benshi radio?")
                _connectionState.value = ConnectionState.ERROR
                return
            }
            writeCharacteristic = service.getCharacteristic(WRITE_UUID)
            val indicateChar = service.getCharacteristic(INDICATE_UUID)
            if (indicateChar == null || writeCharacteristic == null) {
                Log.e(TAG, "Required characteristics missing")
                _connectionState.value = ConnectionState.ERROR
                return
            }
            // Negotiate larger MTU before enabling indications
            gatt.requestMtu(512)
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.d(TAG, "MTU negotiated: $mtu (status=$status)")
            // Enable indications now that MTU is set
            val service = gatt.getService(SERVICE_UUID) ?: return
            val indicateChar = service.getCharacteristic(INDICATE_UUID) ?: return
            gatt.setCharacteristicNotification(indicateChar, true)
            val descriptor = indicateChar.getDescriptor(CCCD_UUID)
            if (descriptor != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    descriptor.value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(descriptor)
                }
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "CCCD write success — radio connected")
                _connectionState.value = ConnectionState.CONNECTED
                sendJob = scope.launch { sendWorker() }
            } else {
                Log.e(TAG, "CCCD write failed: $status")
                _connectionState.value = ConnectionState.ERROR
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (characteristic.uuid == INDICATE_UUID) {
                synchronized(receiveBuffer) {
                    receiveBuffer.write(value)
                    val buf = receiveBuffer.toByteArray()
                    if (buf.size >= 4) {
                        BenshiMessage.decode(buf)?.let { msg ->
                            scope.launch { _inbound.emit(msg) }
                            receiveBuffer.reset()
                            return
                        }
                    }
                    // Prevent unbounded growth from unparseable data
                    if (receiveBuffer.size() > 1024) {
                        Log.w(TAG, "Receive buffer overflow (${receiveBuffer.size()} bytes), clearing")
                        receiveBuffer.reset()
                    }
                }
            }
        }

        // Android < 13 callback
        @Suppress("OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            @Suppress("DEPRECATION")
            onCharacteristicChanged(gatt, characteristic, characteristic.value ?: return)
        }
    }

    override suspend fun connect() = withContext(Dispatchers.IO) {
        _connectionState.value = ConnectionState.SCANNING
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val device = adapter.getRemoteDevice(deviceAddress)
        Log.d(TAG, "Connecting to $deviceAddress…")
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    override suspend fun send(message: BenshiMessage) {
        sendChannel.send(message.encode())
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        sendJob?.cancel()
        sendChannel.close()
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        writeCharacteristic = null
        synchronized(receiveBuffer) { receiveBuffer.reset() }
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    private suspend fun sendWorker() = withContext(Dispatchers.IO) {
        for (bytes in sendChannel) {
            val char = writeCharacteristic
            val g = gatt
            if (char == null || g == null) {
                Log.w(TAG, "GATT or characteristic null, dropping message")
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeCharacteristic(char, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                } else {
                    @Suppress("DEPRECATION")
                    char.value = bytes
                    @Suppress("DEPRECATION")
                    g.writeCharacteristic(char)
                }
            }
        }
    }
}

private fun ByteArray.toHexString() = joinToString(" ") { "%02x".format(it) }
