package com.hamradio.aaos.radio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log
import com.hamradio.aaos.radio.protocol.AprsPacket
import com.hamradio.aaos.radio.protocol.BasicCommand
import com.hamradio.aaos.radio.protocol.BenshiMessage
import com.hamradio.aaos.radio.protocol.BssSettings
import com.hamradio.aaos.radio.protocol.CommandGroup
import com.hamradio.aaos.radio.protocol.DeviceInfo
import com.hamradio.aaos.radio.protocol.HtStatus
import com.hamradio.aaos.radio.protocol.RadioCommands
import com.hamradio.aaos.radio.protocol.RadioNotification
import com.hamradio.aaos.radio.protocol.RadioSettings
import com.hamradio.aaos.radio.protocol.TncDataFragment
import com.hamradio.aaos.radio.protocol.ReplyStatus
import com.hamradio.aaos.radio.protocol.RfChannel
import com.hamradio.aaos.radio.transport.ConnectionState
import com.hamradio.aaos.radio.transport.IRadioTransport
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "RadioController"
private const val MAX_CHANNELS = 64

@Singleton
class RadioController @Inject constructor(
    private val transport: IRadioTransport,
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var collectJob: Job? = null
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    // -----------------------------------------------------------------------
    // Public state
    // -----------------------------------------------------------------------

    val connectionState: StateFlow<ConnectionState> = transport.connectionState

    private val _deviceInfo      = MutableStateFlow<DeviceInfo?>(null)
    val deviceInfo: StateFlow<DeviceInfo?> = _deviceInfo.asStateFlow()

    private val _htStatus        = MutableStateFlow(HtStatus.DISCONNECTED)
    val htStatus: StateFlow<HtStatus> = _htStatus.asStateFlow()

    private val _settings        = MutableStateFlow<RadioSettings?>(null)
    val settings: StateFlow<RadioSettings?> = _settings.asStateFlow()

    private val _bssSettings     = MutableStateFlow<BssSettings?>(null)
    val bssSettings: StateFlow<BssSettings?> = _bssSettings.asStateFlow()

    private val _channels        = MutableStateFlow<List<RfChannel>>(emptyList())
    val channels: StateFlow<List<RfChannel>> = _channels.asStateFlow()

    private val _volume          = MutableStateFlow(8)
    val volume: StateFlow<Int> = _volume.asStateFlow()

    private val _batteryPercent  = MutableStateFlow(-1)
    val batteryPercent: StateFlow<Int> = _batteryPercent.asStateFlow()

    /** One-shot error events for UI snackbar display. */
    private val _errorEvent = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val errorEvent: kotlinx.coroutines.flow.Flow<String> = _errorEvent

    /** Received APRS packets (newest first, capped at 50). */
    private val _aprsPackets = MutableStateFlow<List<AprsPacket>>(emptyList())
    val aprsPackets: StateFlow<List<AprsPacket>> = _aprsPackets.asStateFlow()

    /** TNC fragment reassembly buffer. */
    private val fragmentBuffer = java.io.ByteArrayOutputStream()
    private var currentFragmentId = -1

    /** Debounce counters for settings writes — avoids flooding BLE on slider drags. */
    private val settingsWriteGen = AtomicInteger(0)
    private val bssWriteGen = AtomicInteger(0)
    private companion object { const val SETTINGS_DEBOUNCE_MS = 500L }

    /** Raw message log for the debug screen (newest first, capped at 200). */
    private val logBuffer = ArrayDeque<LogEntry>(200)
    private val _messageLog      = MutableStateFlow<List<LogEntry>>(emptyList())
    val messageLog: StateFlow<List<LogEntry>> = _messageLog.asStateFlow()

    // -----------------------------------------------------------------------
    // Connection lifecycle
    // -----------------------------------------------------------------------

    fun connect() {
        scope.launch {
            try {
                transport.connect()
                startCollecting()
                withTimeoutOrNull(10_000L) { initializeRadio() }
                    ?: Log.w(TAG, "Radio init sequence timed out")
            } catch (e: Exception) {
                Log.e(TAG, "Connect failed", e)
            }
        }
    }

    fun disconnect() {
        scope.launch {
            collectJob?.cancelAndJoin()
            transport.disconnect()
            _htStatus.value = HtStatus.DISCONNECTED
            _deviceInfo.value = null
            _channels.value = emptyList()
        }
    }

    // -----------------------------------------------------------------------
    // Commands
    // -----------------------------------------------------------------------

    fun setVolume(level: Int) = sendCmd(RadioCommands.setVolume(level))

    fun setScan(enable: Boolean) = sendCmd(RadioCommands.setScan(enable))

    fun setChannelA(channelId: Int) {
        val current = _settings.value ?: return
        val updated = current.copy(channelA = channelId)
        applySettings(updated)
    }

    fun setChannelB(channelId: Int) {
        val current = _settings.value ?: return
        val updated = current.copy(channelB = channelId)
        applySettings(updated)
    }

    fun setHtPower(on: Boolean) = sendCmd(RadioCommands.setHtOnOff(on))

    fun pttDown() {
        requestAudioFocus()
        sendCmd(RadioCommands.setHtOnOff(true))
    }

    fun pttUp() {
        sendCmd(RadioCommands.setHtOnOff(false))
        abandonAudioFocus()
    }

    private fun requestAudioFocus() {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .build()
        audioFocusRequest = request
        audioManager.requestAudioFocus(request)
    }

    private fun abandonAudioFocus() {
        audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        audioFocusRequest = null
    }

    fun saveChannel(channel: RfChannel) {
        // Optimistic UI update
        val list = _channels.value.toMutableList()
        val idx = list.indexOfFirst { it.channelId == channel.channelId }
        if (idx >= 0) list[idx] = channel else list.add(channel)
        list.sortBy { it.channelId }
        _channels.value = list
        // Write to radio
        sendCmd(RadioCommands.writeChannel(channel))
        sendCmd(RadioCommands.storeSettings())
    }

    fun updateSettings(transform: (RadioSettings) -> RadioSettings) {
        val current = _settings.value ?: return
        val updated = transform(current)
        _settings.value = updated  // optimistic UI update
        val gen = settingsWriteGen.incrementAndGet()
        scope.launch {
            delay(SETTINGS_DEBOUNCE_MS)
            if (settingsWriteGen.get() == gen) {
                applySettings(_settings.value ?: return@launch)
            }
        }
    }

    fun updateBssSettings(transform: (BssSettings) -> BssSettings) {
        val current = _bssSettings.value ?: return
        val updated = transform(current)
        _bssSettings.value = updated  // optimistic UI update
        val gen = bssWriteGen.incrementAndGet()
        scope.launch {
            delay(SETTINGS_DEBOUNCE_MS)
            if (bssWriteGen.get() == gen) {
                val data = (_bssSettings.value ?: return@launch).patchRawData()
                sendCmd(RadioCommands.writeBssSettings(data))
                sendCmd(RadioCommands.storeSettings())
            }
        }
    }

    fun sendRaw(message: BenshiMessage) = sendCmd(message)

    fun clearLog() {
        synchronized(logBuffer) {
            logBuffer.clear()
            _messageLog.value = emptyList()
        }
    }

    fun refreshChannels() {
        val count = _deviceInfo.value?.channelCount ?: 16
        scope.launch {
            for (i in 0 until count) {
                transport.send(RadioCommands.readChannel(i))
            }
        }
    }

    // -----------------------------------------------------------------------
    // Initialization sequence
    // -----------------------------------------------------------------------

    private suspend fun initializeRadio() {
        // 1. Request device info
        transport.send(RadioCommands.getDevInfo())
        // 2. Register for all notifications
        RadioCommands.registerAllNotifications().forEach { transport.send(it) }
        // 3. Request current status, settings, volume, battery
        transport.send(RadioCommands.getHtStatus())
        transport.send(RadioCommands.getSettings())
        transport.send(RadioCommands.getVolume())
        transport.send(RadioCommands.readBatteryStatus())
        transport.send(RadioCommands.getBssSettings())
        transport.send(RadioCommands.getPosition())
    }

    // -----------------------------------------------------------------------
    // Inbound message dispatch
    // -----------------------------------------------------------------------

    private fun startCollecting() {
        collectJob?.cancel()
        collectJob = transport.inboundMessages
            .onEach { msg -> handleInbound(msg) }
            .launchIn(scope)
    }

    private fun handleInbound(msg: BenshiMessage) {
        appendLog(msg)
        if (msg.commandGroup != CommandGroup.BASIC) return

        when {
            msg.isReply -> handleReply(msg)
            msg.command == BasicCommand.EVENT_NOTIFICATION -> handleNotification(msg)
        }
    }

    private fun handleReply(msg: BenshiMessage) {
        val body = msg.body
        if (body.isEmpty()) return
        val status = ReplyStatus.fromCode(body[0].toInt() and 0xFF)
        if (status != ReplyStatus.SUCCESS) {
            Log.w(TAG, "Command ${msg.command} replied with status $status")
            // Emit user-visible error for write commands
            val cmdLabel = when (msg.command) {
                BasicCommand.WRITE_SETTINGS     -> "Settings"
                BasicCommand.WRITE_BSS_SETTINGS -> "APRS/BSS settings"
                BasicCommand.WRITE_RF_CH        -> "Channel"
                BasicCommand.SET_VOLUME         -> "Volume"
                BasicCommand.SET_IN_SCAN        -> "Scan"
                BasicCommand.STORE_SETTINGS     -> "Save"
                else -> null
            }
            if (cmdLabel != null) {
                _errorEvent.tryEmit("$cmdLabel rejected: $status")
            }
        }
        val payload = if (body.size > 1) body.copyOfRange(1, body.size) else ByteArray(0)

        when (msg.command) {
            BasicCommand.GET_DEV_INFO -> {
                DeviceInfo.decode(payload)?.let { info ->
                    _deviceInfo.value = info
                    Log.i(TAG, "Device: ch=${info.channelCount} dmr=${info.supportDmr} vfo=${info.supportVfo}")
                    // Now fetch all channels
                    scope.launch {
                        for (i in 0 until info.channelCount.coerceAtMost(MAX_CHANNELS)) {
                            transport.send(RadioCommands.readChannel(i))
                        }
                    }
                }
            }
            BasicCommand.GET_HT_STATUS -> {
                HtStatus.decode(payload)?.let { _htStatus.value = it }
            }
            BasicCommand.READ_SETTINGS -> {
                RadioSettings.decode(payload)?.let { _settings.value = it }
            }
            BasicCommand.READ_BSS_SETTINGS -> {
                BssSettings.decode(payload)?.let { _bssSettings.value = it }
            }
            BasicCommand.READ_RF_CH -> {
                RfChannel.decode(payload)?.let { ch ->
                    val list = _channels.value.toMutableList()
                    val idx = list.indexOfFirst { it.channelId == ch.channelId }
                    if (idx >= 0) list[idx] = ch else list.add(ch)
                    list.sortBy { it.channelId }
                    _channels.value = list
                }
            }
            BasicCommand.GET_VOLUME -> {
                if (payload.isNotEmpty()) _volume.value = payload[0].toInt() and 0xFF
            }
            BasicCommand.READ_STATUS -> {
                if (payload.size >= 3) {
                    val value = ((payload[1].toInt() and 0xFF) shl 8) or (payload[2].toInt() and 0xFF)
                    _batteryPercent.value = value.coerceIn(0, 100)
                }
            }
        }
    }

    private fun handleNotification(msg: BenshiMessage) {
        val body = msg.body
        if (body.isEmpty()) return
        val notif = RadioNotification.fromCode(body[0].toInt() and 0xFF)
        val data  = if (body.size > 1) body.copyOfRange(1, body.size) else ByteArray(0)
        Log.d(TAG, "Notification: $notif")

        when (notif) {
            RadioNotification.HT_STATUS_CHANGED    -> HtStatus.decode(data)?.let { _htStatus.value = it }
            RadioNotification.HT_CH_CHANGED        -> {
                // Channel changed — re-read the channel and update status
                RfChannel.decode(data)?.let { ch ->
                    val list = _channels.value.toMutableList()
                    val idx = list.indexOfFirst { it.channelId == ch.channelId }
                    if (idx >= 0) list[idx] = ch else list.add(ch)
                    list.sortBy { it.channelId }
                    _channels.value = list
                }
                sendCmd(RadioCommands.getHtStatus())
            }
            RadioNotification.HT_SETTINGS_CHANGED  -> sendCmd(RadioCommands.getSettings())
            RadioNotification.BSS_SETTINGS_CHANGED -> sendCmd(RadioCommands.getBssSettings())
            RadioNotification.RADIO_STATUS_CHANGED -> sendCmd(RadioCommands.getHtStatus())
            RadioNotification.DATA_RXD             -> handleDataRxd(data)
            else -> Unit
        }
    }

    // -----------------------------------------------------------------------
    // APRS / TNC data reception
    // -----------------------------------------------------------------------

    private fun handleDataRxd(data: ByteArray) {
        val fragment = TncDataFragment.decode(data) ?: return
        // New fragment ID → reset buffer
        if (fragment.fragmentId != currentFragmentId) {
            fragmentBuffer.reset()
            currentFragmentId = fragment.fragmentId
        }
        fragmentBuffer.write(fragment.payload)

        if (fragment.isFinal) {
            val assembled = fragmentBuffer.toByteArray()
            fragmentBuffer.reset()
            currentFragmentId = -1

            // Try to decode as AX.25 / APRS
            val packet = AprsPacket.decode(assembled)
            if (packet != null) {
                Log.i(TAG, "APRS: ${packet.source}-${packet.sourceSsid} → ${packet.info.take(60)}")
                val list = _aprsPackets.value.toMutableList()
                list.add(0, packet)
                if (list.size > 50) list.removeAt(list.size - 1)
                _aprsPackets.value = list
            } else {
                Log.d(TAG, "DATA_RXD: ${assembled.size} bytes, not valid AX.25")
            }
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun sendCmd(msg: BenshiMessage) {
        scope.launch {
            try {
                withTimeoutOrNull(5_000L) { transport.send(msg) }
                    ?: Log.w(TAG, "Send timed out for command ${msg.command}")
            } catch (e: Exception) { Log.e(TAG, "Send failed", e) }
        }
    }

    private fun applySettings(settings: RadioSettings) {
        val data = settings.patchRawData()
        sendCmd(RadioCommands.writeSettings(data))
        sendCmd(RadioCommands.storeSettings())
    }

    private fun appendLog(msg: BenshiMessage) {
        val entry = LogEntry(
            direction  = if (msg.isReply || msg.command == BasicCommand.EVENT_NOTIFICATION) LogDir.IN else LogDir.OUT,
            command    = msg.command,
            isReply    = msg.isReply,
            bodyHex    = msg.body.toHexString(),
            bodyLen    = msg.body.size,
        )
        synchronized(logBuffer) {
            logBuffer.addFirst(entry)
            if (logBuffer.size > 200) logBuffer.removeLast()
            _messageLog.value = logBuffer.toList()
        }
    }
}

data class LogEntry(
    val direction: LogDir,
    val command: Int,
    val isReply: Boolean,
    val bodyHex: String,
    val bodyLen: Int,
    val timestamp: Long = System.currentTimeMillis(),
)

enum class LogDir { IN, OUT }

private fun ByteArray.toHexString() = take(16).joinToString(" ") { "%02x".format(it) } +
    if (size > 16) "…" else ""
