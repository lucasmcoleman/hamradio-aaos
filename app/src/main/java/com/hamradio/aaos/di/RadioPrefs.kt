package com.hamradio.aaos.di

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

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
        set(v) = if (v == null) prefs.edit().remove(KEY_ADDR).apply()
                 else prefs.edit().putString(KEY_ADDR, v).apply()

    companion object {
        private const val KEY_MOCK = "use_mock_radio"
        private const val KEY_ADDR = "device_address"
    }
}
