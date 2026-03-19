package com.hamradio.aaos.di

import android.content.Context
import com.hamradio.aaos.BuildConfig
import com.hamradio.aaos.radio.transport.BleTransport
import com.hamradio.aaos.radio.transport.IRadioTransport
import com.hamradio.aaos.radio.transport.MockTransport
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

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
            // Device address is stored in prefs after scanning
            val addr = prefs.deviceAddress ?: FALLBACK_MOCK_WHEN_NO_ADDRESS
            if (addr == FALLBACK_MOCK_WHEN_NO_ADDRESS) MockTransport()
            else BleTransport(context, addr)
        }
    }

    private const val FALLBACK_MOCK_WHEN_NO_ADDRESS = "__mock__"
}
