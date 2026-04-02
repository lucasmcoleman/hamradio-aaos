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
import com.hamradio.aaos.radio.protocol.RadioCommands
import com.hamradio.aaos.radio.protocol.RfChannel
import com.hamradio.aaos.radio.protocol.SubAudio
import com.hamradio.aaos.radio.transport.ConnectionState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
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
    val errorEvent: kotlinx.coroutines.flow.Flow<String> = radio.errorEvent
    val htStatus:        StateFlow<HtStatus>        = radio.htStatus
    val settings:        StateFlow<RadioSettings?>  = radio.settings
    val bssSettings:     StateFlow<BssSettings?>    = radio.bssSettings
    val channels:        StateFlow<List<RfChannel>> = radio.channels
    val volume:          StateFlow<Int>             = radio.volume
    val batteryPercent:  StateFlow<Int>             = radio.batteryPercent
    val deviceInfo:      StateFlow<DeviceInfo?>     = radio.deviceInfo
    val messageLog:      StateFlow<List<LogEntry>>  = radio.messageLog
    val aprsPackets:     StateFlow<List<com.hamradio.aaos.radio.protocol.AprsPacket>> = radio.aprsPackets

    /** True when mock transport is active. */
    val isMockMode: Boolean get() = prefs.useMockRadio

    // Audio routing preferences — observable StateFlows for UI recomposition
    private val _rxAudioRoute = MutableStateFlow(prefs.rxAudioRoute)
    val rxAudioRoute: StateFlow<Int> = _rxAudioRoute.asStateFlow()
    private val _txMicRoute = MutableStateFlow(prefs.txMicRoute)
    val txMicRoute: StateFlow<Int> = _txMicRoute.asStateFlow()
    private val _showDebugTab = MutableStateFlow(prefs.showDebugTab)
    val showDebugTab: StateFlow<Boolean> = _showDebugTab.asStateFlow()
    fun setShowDebugTab(enabled: Boolean) {
        prefs.showDebugTab = enabled
        _showDebugTab.value = enabled
    }

    private val _autoSwitchOnRx = MutableStateFlow(prefs.autoSwitchOnRx)
    val autoSwitchOnRx: StateFlow<Boolean> = _autoSwitchOnRx.asStateFlow()

    fun setRxAudioRoute(route: Int) {
        prefs.rxAudioRoute = route
        _rxAudioRoute.value = route
        applyAudioRouting()
    }

    fun setTxMicRoute(route: Int) {
        prefs.txMicRoute = route
        _txMicRoute.value = route
    }

    fun setAutoSwitchOnRx(enabled: Boolean) {
        prefs.autoSwitchOnRx = enabled
        _autoSwitchOnRx.value = enabled
    }

    private val _pttToggleMode = MutableStateFlow(prefs.pttToggleMode)
    val pttToggleMode: StateFlow<Boolean> = _pttToggleMode.asStateFlow()
    fun setPttToggleMode(enabled: Boolean) {
        prefs.pttToggleMode = enabled
        _pttToggleMode.value = enabled
    }

    /** Apply RX audio routing to radio settings (localSpeaker based on route). */
    private fun applyAudioRouting() {
        val rxRoute = prefs.rxAudioRoute
        when (rxRoute) {
            0 -> radio.updateSettings { it.copy(localSpeaker = 2) }  // Radio only: speaker on
            1 -> radio.updateSettings { it.copy(localSpeaker = 0) }  // Vehicle only: speaker off (HFP)
            2 -> radio.updateSettings { it.copy(localSpeaker = 2) }  // Both: speaker on + HFP
        }
    }

    init {
        // Sync VFO frequencies from radio settings when they arrive
        viewModelScope.launch {
            settings.collect { s ->
                if (s != null) {
                    if (s.vfo1ModFreqHz > 0) _vfoA.value = _vfoA.value.copy(freqHz = s.vfo1ModFreqHz)
                    if (s.vfo2ModFreqHz > 0) _vfoB.value = _vfoB.value.copy(freqHz = s.vfo2ModFreqHz)
                }
            }
        }
    }

    /** The currently active channel A object (null if channels not loaded). */
    val activeChannelA: StateFlow<RfChannel?> = combine(channels, settings) { chs, s ->
        s?.let { chs.firstOrNull { ch -> ch.channelId == it.channelA } }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The currently active channel B object. */
    val activeChannelB: StateFlow<RfChannel?> = combine(channels, settings) { chs, s ->
        s?.let { chs.firstOrNull { ch -> ch.channelId == it.channelB } }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // -----------------------------------------------------------------------
    // VFO mode state
    // -----------------------------------------------------------------------

    data class VfoState(
        val freqHz: Long = 146_520_000L,
        val offsetHz: Long = 0L,           // + or - offset (0 = simplex)
        val txSubAudio: SubAudio = SubAudio.None,
        val rxSubAudio: SubAudio = SubAudio.None,
        val highPower: Boolean = true,
        val wideBandwidth: Boolean = true,
    ) {
        val txFreqHz: Long get() = freqHz + offsetHz
        val rxFreqHz: Long get() = freqHz
        val freqMhz: Double get() = freqHz / 1_000_000.0
    }

    private val _vfoA = MutableStateFlow(VfoState())
    val vfoA: StateFlow<VfoState> = _vfoA.asStateFlow()

    private val _vfoB = MutableStateFlow(VfoState(freqHz = 446_000_000L))
    val vfoB: StateFlow<VfoState> = _vfoB.asStateFlow()

    /** Per-slot mode: false = Channel, true = VFO */
    private val _isVfoModeA = MutableStateFlow(false)
    val isVfoModeA: StateFlow<Boolean> = _isVfoModeA.asStateFlow()

    private val _isVfoModeB = MutableStateFlow(false)
    val isVfoModeB: StateFlow<Boolean> = _isVfoModeB.asStateFlow()

    fun toggleVfoMode(slot: String) {
        if (slot == "A") _isVfoModeA.value = !_isVfoModeA.value
        else _isVfoModeB.value = !_isVfoModeB.value
    }

    fun updateVfo(slot: String, transform: (VfoState) -> VfoState) {
        val flow = if (slot == "A") _vfoA else _vfoB
        flow.value = transform(flow.value)
        // Write VFO frequency to radio settings
        val vfo = flow.value
        if (slot == "A") {
            radio.updateSettings { it.copy(vfo1ModFreqHz = vfo.freqHz) }
        } else {
            radio.updateSettings { it.copy(vfo2ModFreqHz = vfo.freqHz) }
        }
    }

    /** Save current VFO state as a new channel. */
    fun saveVfoAsChannel(slot: String, channelId: Int, name: String) {
        val vfo = if (slot == "A") _vfoA.value else _vfoB.value
        val ch = RfChannel(
            channelId = channelId,
            name = name.take(10),
            txFreqHz = vfo.txFreqHz,
            rxFreqHz = vfo.rxFreqHz,
            txSubAudio = vfo.txSubAudio,
            rxSubAudio = vfo.rxSubAudio,
            txAtMaxPower = vfo.highPower,
            txAtMedPower = !vfo.highPower,
            bandwidth = if (vfo.wideBandwidth) com.hamradio.aaos.radio.protocol.BandwidthType.WIDE
                        else com.hamradio.aaos.radio.protocol.BandwidthType.NARROW,
        )
        radio.saveChannel(ch)
    }

    /** Add a blank channel at the given ID. */
    fun addNewChannel(channelId: Int) {
        val ch = RfChannel(
            channelId = channelId,
            name = "CH%02d".format(channelId),
            txFreqHz = 146_520_000L,
            rxFreqHz = 146_520_000L,
        )
        radio.saveChannel(ch)
    }

    // -----------------------------------------------------------------------
    // Actions
    // -----------------------------------------------------------------------

    fun connect()    = radio.connect()
    fun disconnect() = radio.disconnect()

    fun setVolume(level: Int) = radio.setVolume(level)

    fun setScan(enable: Boolean) = radio.setScan(enable)

    fun selectChannelA(channelId: Int) = radio.setChannelA(channelId)
    fun selectChannelB(channelId: Int) = radio.setChannelB(channelId)

    /** Switch the radio's active A/B slot via DO_PROG_FUNC(TOGGLE_AB_CH). */
    fun setActiveSlot(slot: String) {
        // Determine current active slot from htStatus — channelId matches channelA or channelB
        val s = settings.value ?: return
        val currentIsA = radio.htStatus.value.channelId == s.channelA
        val wantA = slot == "A"
        if (wantA != currentIsA) {
            radio.sendRaw(RadioCommands.doProgFunc(RadioCommands.PF_TOGGLE_AB_CH))
        }
    }

    fun setPower(on: Boolean) = radio.setHtPower(on)

    fun pttDown() = radio.pttDown()
    fun pttUp()   = radio.pttUp()

    fun saveChannel(channel: RfChannel) = radio.saveChannel(channel)

    /** Set channel B's frequency for APRS use. */
    fun setChannelBFreq(freqHz: Long) {
        val chBId = settings.value?.channelB ?: return
        val ch = channels.value.firstOrNull { it.channelId == chBId } ?: return
        radio.saveChannel(ch.copy(txFreqHz = freqHz, rxFreqHz = freqHz, name = "APRS"))
    }

    /** Set channel B's TX power level. 0=High, 1=Med, 2=Low. */
    fun setChannelBPower(level: Int) {
        val chBId = settings.value?.channelB ?: return
        val ch = channels.value.firstOrNull { it.channelId == chBId } ?: return
        radio.saveChannel(ch.copy(
            txAtMaxPower = level == 0,
            txAtMedPower = level == 1,
        ))
    }

    fun toggleChannelMute(channelId: Int) {
        val ch = channels.value.firstOrNull { it.channelId == channelId } ?: return
        radio.saveChannel(ch.copy(mute = !ch.mute))
    }

    fun refreshChannels() = radio.refreshChannels()

    fun updateSettings(transform: (RadioSettings) -> RadioSettings) =
        radio.updateSettings(transform)

    fun updateBssSettings(transform: (BssSettings) -> BssSettings) =
        radio.updateBssSettings(transform)

    fun sendRawCommand(msg: BenshiMessage) = radio.sendRaw(msg)

    fun clearLog() = radio.clearLog()

    /** Toggle between mock and real BLE transport (requires reconnect). */
    /** Signals the Activity to recreate itself so Hilt re-injects the transport. */
    private val _restartEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val restartEvent: kotlinx.coroutines.flow.Flow<Unit> = _restartEvent

    fun toggleMockMode() {
        radio.disconnect()
        prefs.useMockRadio = !prefs.useMockRadio
        viewModelScope.launch { _restartEvent.emit(Unit) }
    }

    fun setDeviceAddress(address: String) {
        prefs.deviceAddress = address
    }

    /** Set device address and restart to reinject transport with new address. */
    fun connectToDevice(address: String) {
        radio.disconnect()
        prefs.deviceAddress = address
        prefs.useMockRadio = false
        _restartEvent.tryEmit(Unit)
    }

    override fun onCleared() {
        super.onCleared()
        radio.disconnect()
    }
}
