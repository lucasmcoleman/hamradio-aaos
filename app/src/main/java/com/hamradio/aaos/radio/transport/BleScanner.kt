package com.hamradio.aaos.radio.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "BleScanner"

data class ScannedDevice(
    val name: String,
    val address: String,
    val rssi: Int,
)

/**
 * Simple BLE scanner that discovers nearby devices and exposes them as a StateFlow.
 * Call [startScan] to begin and [stopScan] to end. Results accumulate in [devices].
 */
class BleScanner(private val context: Context) {

    private val _devices = MutableStateFlow<List<ScannedDevice>>(emptyList())
    val devices: StateFlow<List<ScannedDevice>> = _devices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val scanner get() = btManager?.adapter?.bluetoothLeScanner

    private val seen = mutableMapOf<String, ScannedDevice>()

    private val callback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val name = device.name ?: "Unknown"
            val addr = device.address ?: return
            seen[addr] = ScannedDevice(name, addr, result.rssi)
            _devices.value = seen.values.sortedByDescending { it.rssi }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed: $errorCode")
            _isScanning.value = false
        }
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        seen.clear()
        _devices.value = emptyList()
        _isScanning.value = true
        try {
            scanner?.startScan(callback) ?: run {
                Log.w(TAG, "BLE scanner not available")
                _isScanning.value = false
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing BLE permission", e)
            _isScanning.value = false
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        try {
            scanner?.stopScan(callback)
        } catch (_: Exception) { }
        _isScanning.value = false
    }
}
