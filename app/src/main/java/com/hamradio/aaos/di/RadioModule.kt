package com.hamradio.aaos.di

import android.content.Context
import android.util.Log
import com.hamradio.aaos.BuildConfig
import com.hamradio.aaos.radio.transport.BleTransport
import com.hamradio.aaos.radio.transport.ConnectionState
import com.hamradio.aaos.radio.transport.DisconnectedTransport
import com.hamradio.aaos.radio.transport.IRadioTransport
import com.hamradio.aaos.radio.transport.MockTransport
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

private const val TAG = "RadioModule"

@Module
@InstallIn(SingletonComponent::class)
object RadioModule {

    /**
     * Provide the appropriate transport based on build variant and runtime flag.
     * The "mockRadio" build type always uses MockTransport.
     * The debug/release builds use BleTransport (real hardware).
     *
     * Override with [RadioPrefs] at runtime for debug builds.
     */
    @Provides
    @Singleton
    fun provideTransport(
        @ApplicationContext context: Context,
        prefs: RadioPrefs,
    ): IRadioTransport {
        return if (BuildConfig.MOCK_RADIO || prefs.useMockRadio) {
            MockTransport()
        } else {
            val addr = prefs.deviceAddress
            if (addr != null) {
                BleTransport(context, addr)
            } else {
                Log.i(TAG, "No device address — staying disconnected until configured")
                DisconnectedTransport()
            }
        }
    }
}
