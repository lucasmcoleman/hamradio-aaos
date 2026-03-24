package com.hamradio.aaos.radio.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "BtScanner"

data class ScannedDevice(
    val name: String,
    val address: String,
    val rssi: Int,
    val type: String,  // "BLE", "Classic", or "Dual"
)

/**
 * Bluetooth scanner that discovers both Classic and BLE devices.
 * Classic discovery uses BluetoothAdapter.startDiscovery() + BroadcastReceiver.
 * BLE discovery uses BluetoothLeScanner.startScan().
 * Results from both are merged into a single [devices] StateFlow.
 */
class BleScanner(private val context: Context) {

    private val _devices = MutableStateFlow<List<ScannedDevice>>(emptyList())
    val devices: StateFlow<List<ScannedDevice>> = _devices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter get() = btManager?.adapter
    private val bleScanner get() = adapter?.bluetoothLeScanner

    private val seen = mutableMapOf<String, ScannedDevice>()

    // -----------------------------------------------------------------------
    // BLE scan callback
    // -----------------------------------------------------------------------

    private val bleCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val name = device.name ?: "Unknown"
            val addr = device.address ?: return
            val existing = seen[addr]
            val type = if (existing?.type == "Classic") "Dual" else "BLE"
            seen[addr] = ScannedDevice(name, addr, result.rssi, type)
            _devices.value = seen.values.sortedByDescending { it.rssi }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE scan failed: $errorCode")
        }
    }

    // -----------------------------------------------------------------------
    // Classic Bluetooth discovery receiver
    // -----------------------------------------------------------------------

    private val classicReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                        ?: return
                    val name = device.name ?: "Unknown"
                    val addr = device.address ?: return
                    val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                    val existing = seen[addr]
                    val type = if (existing?.type == "BLE") "Dual" else "Classic"
                    seen[addr] = ScannedDevice(name, addr, rssi, type)
                    _devices.value = seen.values.sortedByDescending { it.rssi }
                    Log.d(TAG, "Classic found: $name ($addr) rssi=$rssi")
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    Log.d(TAG, "Classic discovery finished")
                }
            }
        }
    }

    private var receiverRegistered = false

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    fun startScan() {
        seen.clear()
        _devices.value = emptyList()
        _isScanning.value = true

        // Also add already-bonded (paired) devices immediately
        try {
            adapter?.bondedDevices?.forEach { device ->
                val name = device.name ?: "Unknown"
                val addr = device.address ?: return@forEach
                seen[addr] = ScannedDevice(name, addr, 0, "Paired")
            }
            _devices.value = seen.values.sortedByDescending { it.rssi }
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot read bonded devices", e)
        }

        // Start Classic Bluetooth discovery
        try {
            if (!receiverRegistered) {
                val filter = IntentFilter().apply {
                    addAction(BluetoothDevice.ACTION_FOUND)
                    addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                }
                context.registerReceiver(classicReceiver, filter)
                receiverRegistered = true
            }
            adapter?.startDiscovery()
            Log.d(TAG, "Classic discovery started")
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing permission for Classic discovery", e)
        }

        // Start BLE scan
        try {
            bleScanner?.startScan(bleCallback) ?: Log.w(TAG, "BLE scanner not available")
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing BLE scan permission", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        try {
            bleScanner?.stopScan(bleCallback)
        } catch (_: Exception) {}
        try {
            adapter?.cancelDiscovery()
        } catch (_: Exception) {}
        try {
            if (receiverRegistered) {
                context.unregisterReceiver(classicReceiver)
                receiverRegistered = false
            }
        } catch (_: Exception) {}
        _isScanning.value = false
    }
}
