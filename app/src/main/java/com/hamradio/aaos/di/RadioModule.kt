package com.hamradio.aaos.di

import com.hamradio.aaos.radio.transport.IRadioTransport
import com.hamradio.aaos.radio.transport.TransportManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RadioModule {

    /**
     * The transport singleton is the [TransportManager], which owns a
     * swappable inner transport (mock / RFCOMM / disconnected) selected from
     * [RadioPrefs]. Switching between them at runtime goes through
     * TransportManager.switchTransport() — no app restart needed.
     */
    @Provides
    @Singleton
    fun provideTransport(manager: TransportManager): IRadioTransport = manager
}
