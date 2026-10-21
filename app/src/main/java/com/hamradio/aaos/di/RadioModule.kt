package com.hamradio.aaos.di

import android.content.Context
import com.hamradio.aaos.BuildConfig
import com.hamradio.aaos.radio.transport.DisconnectedTransport
import com.hamradio.aaos.radio.transport.IRadioTransport
import com.hamradio.aaos.radio.transport.MockTransport
import com.hamradio.aaos.radio.transport.RfcommTransport
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import javax.inject.Inject

@Module
@InstallIn(SingletonComponent::class)
object RadioModule {

    /**
     * Provide the appropriate transport based on build variant and runtime flag.
     * The "mockRadio" build type always uses MockTransport.
     * The debug/release builds use RfcommTransport (real hardware).
     *
     * Override with [RadioPrefs] at runtime for debug builds.
     */
    @Provides
    @Singleton
    fun provideTransport(
        @ApplicationContext context: Context,
        prefs: RadioPrefs,
    ): IRadioTransport {
        return RadioTransportFactory(context).create(prefs.deviceAddress.orEmpty(), prefs.useMockRadio)
    }
}

/** Creates a fresh transport when the active device changes. */
@Singleton
class RadioTransportFactory @Inject constructor(@ApplicationContext private val context: Context) {
    fun create(deviceAddress: String, mockMode: Boolean): IRadioTransport = when {
        BuildConfig.MOCK_RADIO || mockMode -> MockTransport()
        deviceAddress.isNotBlank() -> RfcommTransport(context, deviceAddress)
        else -> DisconnectedTransport()
    }
}
