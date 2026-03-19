package com.hamradio.aaos.radio

import android.util.Log
import com.hamradio.aaos.radio.protocol.BasicCommand
import com.hamradio.aaos.radio.protocol.BenshiMessage
import com.hamradio.aaos.radio.protocol.BssSettings
import com.hamradio.aaos.radio.protocol.CommandGroup
import com.hamradio.aaos.radio.protocol.DeviceInfo
import com.hamradio.aaos.radio.protocol.HtStatus
import com.hamradio.aaos.radio.protocol.RadioCommands
import com.hamradio.aaos.radio.protocol.RadioNotification
import com.hamradio.aaos.radio.protocol.RadioSettings
import com.hamradio.aaos.radio.protocol.ReplyStatus
import com.hamradio.aaos.radio.protocol.RfChannel
import com.hamradio.aaos.radio.transport.ConnectionState
import com.hamradio.aaos.radio.transport.IRadioTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "RadioController"
private const val MAX_CHANNELS = 64

@Singleton
class RadioController @Inject constructor(
    private val transport: IRadioTransport,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var collectJob: Job? = null

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

    /** Raw message log for the debug screen (newest first, capped at 200). */
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
                initializeRadio()
            } catch (e: Exception) {
                Log.e(TAG, "Connect failed", e)
            }
        }
    }

    fun disconnect() {
        scope.launch {
            collectJob?.cancel()
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

    fun saveChannel(channel: RfChannel) {
        sendCmd(RadioCommands.writeChannel(channel))
        sendCmd(RadioCommands.storeSettings())
    }

    fun sendRaw(message: BenshiMessage) = sendCmd(message)

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
            RadioNotification.HT_CH_CHANGED        -> HtStatus.decode(data)?.let { _htStatus.value = it }
            RadioNotification.HT_SETTINGS_CHANGED  -> sendCmd(RadioCommands.getSettings())
            RadioNotification.BSS_SETTINGS_CHANGED -> sendCmd(RadioCommands.getBssSettings())
            RadioNotification.RADIO_STATUS_CHANGED -> sendCmd(RadioCommands.getHtStatus())
            else -> Unit
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun sendCmd(msg: BenshiMessage) {
        scope.launch {
            try { transport.send(msg) }
            catch (e: Exception) { Log.e(TAG, "Send failed", e) }
        }
    }

    private fun applySettings(settings: RadioSettings) {
        if (settings.rawData.isNotEmpty()) {
            sendCmd(BenshiMessage(CommandGroup.BASIC, BasicCommand.WRITE_SETTINGS, false, settings.rawData))
        }
    }

    private fun appendLog(msg: BenshiMessage) {
        val entry = LogEntry(
            direction  = if (msg.isReply || msg.command == BasicCommand.EVENT_NOTIFICATION) LogDir.IN else LogDir.OUT,
            command    = msg.command,
            isReply    = msg.isReply,
            bodyHex    = msg.body.toHexString(),
            bodyLen    = msg.body.size,
        )
        val current = _messageLog.value
        _messageLog.value = (listOf(entry) + current).take(200)
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
