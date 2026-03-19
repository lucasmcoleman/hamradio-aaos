package com.hamradio.aaos.di

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "RadioPrefs"

@Singleton
class RadioPrefs @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("radio_prefs", Context.MODE_PRIVATE)

    var useMockRadio: Boolean
        get() = prefs.getBoolean(KEY_MOCK, false)
        set(v) = prefs.edit().putBoolean(KEY_MOCK, v).apply()

    var deviceAddress: String?
        get() = prefs.getString(KEY_ADDR, null)
        set(v) {
            if (v != null && !BluetoothAdapter.checkBluetoothAddress(v)) {
                Log.w(TAG, "Invalid MAC address rejected: $v")
                return
            }
            if (v == null) prefs.edit().remove(KEY_ADDR).apply()
            else prefs.edit().putString(KEY_ADDR, v).apply()
        }

    /** RX audio routing: 0=Radio, 1=Vehicle, 2=Both */
    var rxAudioRoute: Int
        get() = prefs.getInt(KEY_RX_ROUTE, 2)  // default: Both
        set(v) = prefs.edit().putInt(KEY_RX_ROUTE, v.coerceIn(0, 2)).apply()

    /** TX mic routing: 0=Radio, 1=Vehicle, 2=Auto (based on PTT source) */
    var txMicRoute: Int
        get() = prefs.getInt(KEY_TX_ROUTE, 2)  // default: Auto
        set(v) = prefs.edit().putInt(KEY_TX_ROUTE, v.coerceIn(0, 2)).apply()

    /** Show debug tab in navigation (default off). */
    var showDebugTab: Boolean
        get() = prefs.getBoolean(KEY_DEBUG_TAB, false)
        set(v) = prefs.edit().putBoolean(KEY_DEBUG_TAB, v).apply()

    /** Auto-switch active slot to last channel that received audio (default off). */
    var autoSwitchOnRx: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SWITCH_RX, false)
        set(v) = prefs.edit().putBoolean(KEY_AUTO_SWITCH_RX, v).apply()

    companion object {
        private const val KEY_MOCK = "use_mock_radio"
        private const val KEY_ADDR = "device_address"
        private const val KEY_RX_ROUTE = "rx_audio_route"
        private const val KEY_TX_ROUTE = "tx_mic_route"
        private const val KEY_DEBUG_TAB = "show_debug_tab"
        private const val KEY_AUTO_SWITCH_RX = "auto_switch_on_rx"
    }
}
