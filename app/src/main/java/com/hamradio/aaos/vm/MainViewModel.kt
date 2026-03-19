package com.hamradio.aaos.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hamradio.aaos.di.RadioPrefs
import com.hamradio.aaos.radio.LogEntry
import com.hamradio.aaos.radio.RadioController
import com.hamradio.aaos.radio.protocol.BenshiMessage
import com.hamradio.aaos.radio.protocol.BssSettings
import com.hamradio.aaos.radio.protocol.DeviceInfo
import com.hamradio.aaos.radio.protocol.HtStatus
import com.hamradio.aaos.radio.protocol.RadioSettings
import com.hamradio.aaos.radio.protocol.RfChannel
import com.hamradio.aaos.radio.transport.ConnectionState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val radio: RadioController,
    private val prefs: RadioPrefs,
) : ViewModel() {

    // -----------------------------------------------------------------------
    // Exposed state (direct pass-throughs + derived)
    // -----------------------------------------------------------------------

    val connectionState: StateFlow<ConnectionState> = radio.connectionState
    val htStatus:        StateFlow<HtStatus>        = radio.htStatus
    val settings:        StateFlow<RadioSettings?>  = radio.settings
    val bssSettings:     StateFlow<BssSettings?>    = radio.bssSettings
    val channels:        StateFlow<List<RfChannel>> = radio.channels
    val volume:          StateFlow<Int>             = radio.volume
    val batteryPercent:  StateFlow<Int>             = radio.batteryPercent
    val deviceInfo:      StateFlow<DeviceInfo?>     = radio.deviceInfo
    val messageLog:      StateFlow<List<LogEntry>>  = radio.messageLog

    /** True when mock transport is active. */
    val isMockMode: Boolean get() = prefs.useMockRadio

    /** The currently active channel A object (null if channels not loaded). */
    val activeChannelA: StateFlow<RfChannel?> = combine(channels, settings) { chs, s ->
        s?.let { chs.firstOrNull { ch -> ch.channelId == it.channelA } }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The currently active channel B object. */
    val activeChannelB: StateFlow<RfChannel?> = combine(channels, settings) { chs, s ->
        s?.let { chs.firstOrNull { ch -> ch.channelId == it.channelB } }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // -----------------------------------------------------------------------
    // Actions
    // -----------------------------------------------------------------------

    fun connect()    = radio.connect()
    fun disconnect() = radio.disconnect()

    fun setVolume(level: Int) = radio.setVolume(level)

    fun setScan(enable: Boolean) = radio.setScan(enable)

    fun selectChannelA(channelId: Int) = radio.setChannelA(channelId)
    fun selectChannelB(channelId: Int) = radio.setChannelB(channelId)

    fun setPower(on: Boolean) = radio.setHtPower(on)

    fun saveChannel(channel: RfChannel) = radio.saveChannel(channel)

    fun refreshChannels() = radio.refreshChannels()

    fun sendRawCommand(msg: BenshiMessage) = radio.sendRaw(msg)

    /** Toggle between mock and real BLE transport (requires reconnect). */
    fun toggleMockMode() {
        radio.disconnect()
        prefs.useMockRadio = !prefs.useMockRadio
        // App needs restart to swap transport — signal via a dedicated flow if needed
    }

    fun setDeviceAddress(address: String) {
        prefs.deviceAddress = address
    }

    override fun onCleared() {
        super.onCleared()
        radio.disconnect()
    }
}
