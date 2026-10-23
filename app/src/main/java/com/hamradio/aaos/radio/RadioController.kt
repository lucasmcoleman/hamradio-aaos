package com.hamradio.aaos.radio

import android.content.Context
import android.util.Log
import com.hamradio.aaos.di.RadioTransportFactory
import com.hamradio.aaos.radio.transport.DisconnectedTransport
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.hamradio.aaos.radio.audio.RadioAudioChannel
import com.hamradio.aaos.radio.audio.RadioAudioControl
import com.hamradio.aaos.radio.audio.MockRadioAudioChannel
import com.hamradio.aaos.radio.transport.MockTransport
import com.hamradio.aaos.radio.transport.RfcommTransport
import com.hamradio.aaos.radio.audio.AudioFocusControl
import com.hamradio.aaos.radio.audio.AndroidRadioAudioFocus
import com.hamradio.aaos.radio.protocol.AprsPacket
import com.hamradio.aaos.radio.protocol.BasicCommand
import com.hamradio.aaos.radio.protocol.BenshiMessage
import com.hamradio.aaos.radio.protocol.BssSettings
import com.hamradio.aaos.radio.protocol.CommandGroup
import com.hamradio.aaos.radio.protocol.DeviceInfo
import com.hamradio.aaos.radio.protocol.HtStatus
import com.hamradio.aaos.radio.protocol.RadioCommands
import com.hamradio.aaos.radio.protocol.RadioNotification
import com.hamradio.aaos.radio.protocol.RadioPosition
import com.hamradio.aaos.radio.protocol.RadioSettings
import com.hamradio.aaos.radio.protocol.TncDataFragment
import com.hamradio.aaos.radio.protocol.ReplyStatus
import com.hamradio.aaos.radio.protocol.RfChannel
import com.hamradio.aaos.radio.transport.ConnectionState
import com.hamradio.aaos.radio.transport.IRadioTransport
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "RadioController"
private const val MAX_CHANNELS = 64

/** Select from the actual transport, never from a stale mock preference. */
internal fun selectRadioAudio(context: Context, target: IRadioTransport): RadioAudioControl? =
    when (target) {
        is MockTransport -> MockRadioAudioChannel(context)
        is RfcommTransport -> RadioAudioChannel(context, target.deviceAddress)
        else -> null
    }

@Singleton
class RadioController internal constructor(
    @Volatile private var transport: IRadioTransport,
    private val transportFactory: RadioTransportFactory,
    private val context: Context,
    private val scope: CoroutineScope,
    private val focus: AudioFocusControl = AndroidRadioAudioFocus(context),
    private val createAudio: (IRadioTransport) -> RadioAudioControl? = { target -> selectRadioAudio(context, target) },
) {
    @Inject constructor(transport: IRadioTransport, transportFactory: RadioTransportFactory,
                        @ApplicationContext context: Context) :
        this(transport, transportFactory, context, CoroutineScope(SupervisorJob() + Dispatchers.IO))
    private var collectJob: Job? = null
    private var sessionJob: Job? = null
    private val epoch = AtomicInteger(0)
    private val lifecycle = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private data class PttRequest(val epoch: Int, val down: Boolean, val txToken: Int? = null)
    private val pttRequests = Channel<PttRequest>(Channel.UNLIMITED)
    private var txToken = 0
    private val pttMutex = Mutex()
    private var txTimer: Job? = null
    private val scanLock = Any()
    private var hasStatus = false
    private var scanPending = false
    private val _isPttActive = MutableStateFlow(false)
    val isPttActive = _isPttActive.asStateFlow()
    private data class CommandBatch(val epoch: Int, val target: IRadioTransport, val messages: List<BenshiMessage>)
    private val commands = Channel<CommandBatch>(256)

    // Audio channel for PTT — separate RFCOMM connection for SBC voice
    @Volatile private var audioChannel: RadioAudioControl? = null

    /** Expose audio transmit state for UI. */
    val isAudioTransmitting: StateFlow<Boolean> get() = isPttActive

    // -----------------------------------------------------------------------
    // Public state
    // -----------------------------------------------------------------------

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

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

    private val _position = MutableStateFlow(RadioPosition())
    val position: StateFlow<RadioPosition> = _position.asStateFlow()

    /** One-shot error events for UI snackbar display. */
    private val _errorEvent = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val errorEvent: kotlinx.coroutines.flow.Flow<String> = _errorEvent

    /** Received APRS packets (newest first, capped at 50). */
    private val _aprsPackets = MutableStateFlow<List<AprsPacket>>(emptyList())
    val aprsPackets: StateFlow<List<AprsPacket>> = _aprsPackets.asStateFlow()

    /** TNC fragment reassembly buffer. */
    private val fragmentBuffer = java.io.ByteArrayOutputStream()
    private var currentFragmentId = -1

    /** Debounce counters for settings writes — avoids flooding the radio on slider drags. */
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

    init {
        scope.launch {
            for (action in lifecycle) {
                try { action() }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { _errorEvent.tryEmit("Connection: ${e.message}") }
            }
        }
        scope.launch {
            for (batch in commands) {
                try {
                    for (message in batch.messages) {
                        if (batch.epoch != epoch.get() || !batch.target.isConnected) break
                        withTimeout(5_000L) { batch.target.send(message) }
                    }
                } catch (e: TimeoutCancellationException) { _errorEvent.tryEmit("Radio command timed out") }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { _errorEvent.tryEmit("Radio command: ${e.message}") }
            }
        }
        scope.launch {
            for ((generation, down, token) in pttRequests) {
                pttMutex.withLock {
                    if (generation != epoch.get() || (token != null && token != txToken)) return@withLock
                    try { if (down) startPtt() else stopPtt() }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        _isPttActive.value = false
                        abandonAudioFocus()
                        _errorEvent.tryEmit("Microphone transmit: ${e.message}")
                    }
                }
            }
        }
    }

    fun connect() { lifecycle.trySend { startSession() } }

    /** Ordered with connect/disconnect; no Activity or process restart is required. */
    suspend fun switchTransport(deviceAddress: String, mockMode: Boolean) {
        val done = CompletableDeferred<Unit>()
        lifecycle.send {
            try {
                stopSession()
                transport = transportFactory.create(deviceAddress, mockMode)
                startSession()
                done.complete(Unit)
            } catch (e: Exception) { done.completeExceptionally(e) }
        }
        done.await()
    }

    private fun startSession() {
        if (sessionJob?.isActive == true || transport is DisconnectedTransport) return
        val target = transport
        sessionJob = scope.launch {
            while (isActive) {
                val generation = epoch.incrementAndGet()
                val stateJob = launch { target.connectionState.collect { _connectionState.value = it } }
                collectJob = launch {
                    target.inboundMessages.collect { if (generation == epoch.get()) handleInbound(it) }
                }
                try {
                    withTimeout(60_000L) { target.connect() }  // room for the pairing prompt
                    if (target.isConnected) {
                        coroutineScope {
                            val init = launch { initializeRadio() }
                            val audio = launch { connectAudioChannel(target) }
                            target.connectionState.first { it != ConnectionState.CONNECTED }
                            init.cancelAndJoin()
                            audio.cancelAndJoin()
                        }
                    }
                } catch (e: TimeoutCancellationException) { _errorEvent.tryEmit("Radio connection timed out") }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { _errorEvent.tryEmit("Radio connection: ${e.message}") }
                finally {
                    withContext(NonCancellable) {
                        epoch.incrementAndGet()
                        settingsWriteGen.incrementAndGet()
                        bssWriteGen.incrementAndGet()
                        pttMutex.withLock { stopPtt() }
                        audioChannel?.disconnect()
                        audioChannel = null
                        target.disconnect()
                        collectJob?.cancelAndJoin()
                        stateJob.cancelAndJoin()
                        resetRadioState()
                    }
                }
                _connectionState.value = ConnectionState.ERROR
                delay(5_000L)
            }
        }
    }

    private suspend fun connectAudioChannel(target: IRadioTransport) {
        while (currentCoroutineContext().isActive) {
            val channel = createAudio(target) ?: return
            audioChannel = channel
            try {
                delay(1_000L)
                withTimeout(15_000L) { channel.connect() }
                if (channel.isConnected.value) {
                    coroutineScope {
                        val observer = launch {
                            channel.isTransmitting.collect {
                                pttMutex.withLock {
                                    if (audioChannel === channel) {
                                        _isPttActive.value = channel.isTransmitting.value
                                        if (!_isPttActive.value) {
                                            txTimer?.cancel()
                                            txTimer = null
                                            abandonAudioFocus()
                                        }
                                    }
                                }
                            }
                        }
                        channel.isConnected.first { !it }
                        observer.cancelAndJoin()
                    }
                }
            } catch (e: TimeoutCancellationException) { _errorEvent.tryEmit("Radio audio connection timed out") }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { _errorEvent.tryEmit("Radio audio: ${e.message}") }
            finally {
                withContext(NonCancellable) {
                    pttMutex.withLock { if (audioChannel === channel) stopPtt() }
                    channel.close()
                    if (audioChannel === channel) audioChannel = null
                }
            }
            delay(5_000L)
        }
    }

    fun disconnect() { lifecycle.trySend { stopSession() } }

    private suspend fun stopSession() {
        epoch.incrementAndGet()
        // Best-effort END_AUDIO must be sent while the audio socket is still open.
        pttMutex.withLock { stopPtt() }
        sessionJob?.cancel()
        // Close pending transports before joining jobs potentially blocked in Bluetooth IO.
        transport.disconnect()
        audioChannel?.disconnect()
        sessionJob?.join()
        sessionJob = null
        pttMutex.withLock { stopPtt() }
        resetRadioState()
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    private fun resetRadioState() {
        synchronized(scanLock) { hasStatus = false; scanPending = false }
        _htStatus.value = HtStatus.DISCONNECTED
        _deviceInfo.value = null
        _channels.value = emptyList()
        _settings.value = null
        _bssSettings.value = null
        _batteryPercent.value = -1
        _position.value = RadioPosition()
        fragmentBuffer.reset()
        currentFragmentId = -1
    }

    // -----------------------------------------------------------------------
    // Commands
    // -----------------------------------------------------------------------

    fun setVolume(level: Int) = sendCmd(RadioCommands.setVolume(level))

    fun setScan(enable: Boolean) {
        // Preserve the DB50-B toggle workaround, but never blindly toggle an unknown state.
        synchronized(scanLock) {
            if (_connectionState.value != ConnectionState.CONNECTED || !hasStatus) {
                sendCmd(RadioCommands.getHtStatus())
                return
            }
            if (scanPending || _htStatus.value.isScan == enable) return
            scanPending = true
            sendBatch(RadioCommands.doProgFunc(RadioCommands.PF_TOGGLE_CH_SCAN), RadioCommands.getHtStatus())
        }
    }

    fun setChannelA(channelId: Int) = updateSettings { it.copy(channelA = channelId) }
    fun setChannelB(channelId: Int) = updateSettings { it.copy(channelB = channelId) }

    fun setHtPower(on: Boolean) = sendCmd(RadioCommands.setHtOnOff(on))

    fun pttDown() { pttRequests.trySend(PttRequest(epoch.get(), true)) }
    fun pttUp() { pttRequests.trySend(PttRequest(epoch.get(), false)) }

    private suspend fun startPtt() {
        if (_isPttActive.value) return
        val channel = audioChannel
        if (channel == null || !channel.isConnected.value) {
            _errorEvent.tryEmit("Audio channel is not ready for microphone transmit")
            return
        }
        if (!requestAudioFocus()) {
            abandonAudioFocus()
            _errorEvent.tryEmit("Microphone transmit could not obtain audio focus")
            return
        }
        // Streaming audio starts TX; END_AUDIO ends it. Do not send the ambiguous PF_MAIN_PTT toggle.
        if (!channel.startTransmit() || !channel.isTransmitting.value) {
            abandonAudioFocus()
            _errorEvent.tryEmit("Microphone capture could not start")
            return
        }
        _isPttActive.value = true
        txTimer?.cancel()
        val generation = epoch.get()
        val token = ++txToken
        txTimer = scope.launch {
            delay(120_000L)
            pttRequests.send(PttRequest(generation, false, token))
            _errorEvent.tryEmit("120-second app transmit limit reached; stopping capture")
        }
    }

    private suspend fun stopPtt() {
        ++txToken
        txTimer?.cancel()
        txTimer = null
        try { audioChannel?.stopTransmit() }
        finally { _isPttActive.value = false; abandonAudioFocus() }
    }

    private fun requestAudioFocus(): Boolean = focus.request { pttUp() }
    private fun abandonAudioFocus() = focus.abandon()

    fun saveChannel(channel: RfChannel) {
        // Optimistic UI update
        val list = _channels.value.toMutableList()
        val idx = list.indexOfFirst { it.channelId == channel.channelId }
        if (idx >= 0) list[idx] = channel else list.add(channel)
        list.sortBy { it.channelId }
        _channels.value = list
        // Write to radio
        sendBatch(RadioCommands.writeChannel(channel), RadioCommands.storeSettings())
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
                sendBatch(RadioCommands.writeBssSettings(data), RadioCommands.storeSettings())
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
        for (i in 0 until count.coerceAtMost(MAX_CHANNELS)) {
            sendCmd(RadioCommands.readChannel(i))
        }
    }

    // -----------------------------------------------------------------------
    // Initialization sequence
    // -----------------------------------------------------------------------

    private suspend fun initializeRadio() {
        // Small delay to let RFCOMM connection stabilize
        delay(500)

        // 1. Register for all notifications first
        RadioCommands.registerAllNotifications().forEach {
            sendCmd(it)
            delay(50)  // space out commands for real hardware
        }

        // 2. Request device info
        sendCmd(RadioCommands.getDevInfo())
        delay(200)

        // 3. Request current status, settings, volume, battery
        sendCmd(RadioCommands.getHtStatus())
        delay(50)
        sendCmd(RadioCommands.getSettings())
        delay(50)
        sendCmd(RadioCommands.getVolume())
        delay(50)
        sendCmd(RadioCommands.readBatteryStatus())
        delay(50)
        sendCmd(RadioCommands.getBssSettings())
        delay(50)
        sendCmd(RadioCommands.getPosition())

        // 4. Wait for DevInfo reply, then load channels if not already loaded
        delay(2000)
        if (_channels.value.isEmpty()) {
            val count = _deviceInfo.value?.channelCount?.coerceAtMost(MAX_CHANNELS) ?: 16
            Log.i(TAG, "Channels not loaded from DevInfo reply, fetching $count channels")
            for (i in 0 until count) {
                sendCmd(RadioCommands.readChannel(i))
                delay(50)
            }
        }
    }

    // -----------------------------------------------------------------------
    // Inbound message dispatch
    // -----------------------------------------------------------------------

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
            return
        }
        val payload = if (body.size > 1) body.copyOfRange(1, body.size) else ByteArray(0)

        when (msg.command) {
            BasicCommand.GET_DEV_INFO -> {
                DeviceInfo.decode(payload)?.let { info ->
                    _deviceInfo.value = info
                    Log.i(TAG, "Device: ch=${info.channelCount} dmr=${info.supportDmr} vfo=${info.supportVfo}")
                    // Now fetch all channels
                    for (i in 0 until info.channelCount.coerceAtMost(MAX_CHANNELS)) {
                        sendCmd(RadioCommands.readChannel(i))
                    }
                }
            }
            BasicCommand.GET_HT_STATUS -> {
                HtStatus.decode(payload)?.let { acceptStatus(it) }
            }
            BasicCommand.READ_SETTINGS -> {
                Log.i(TAG, "Settings payload: ${payload.size} bytes, hex=${payload.take(24).joinToString(" ") { "%02x".format(it) }}")
                RadioSettings.decode(payload)?.let {
                    Log.i(TAG, "Settings decoded: chA=${it.channelA} chB=${it.channelB} vfo1=${it.vfo1ModFreqHz} vfo2=${it.vfo2ModFreqHz}")
                    _settings.value = it
                }
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
            BasicCommand.GET_POSITION -> {
                RadioPosition.decode(payload)?.let { _position.value = it }
            }
        }
    }

    private fun acceptStatus(status: HtStatus) {
        synchronized(scanLock) {
            _htStatus.value = status
            hasStatus = true
            scanPending = false
        }
    }

    private fun handleNotification(msg: BenshiMessage) {
        val body = msg.body
        if (body.isEmpty()) return
        val notif = RadioNotification.fromCode(body[0].toInt() and 0xFF)
        val data  = if (body.size > 1) body.copyOfRange(1, body.size) else ByteArray(0)
        Log.d(TAG, "Notification: $notif")

        when (notif) {
            RadioNotification.HT_STATUS_CHANGED    -> HtStatus.decode(data)?.let { acceptStatus(it) }
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
            RadioNotification.POSITION_CHANGE      -> RadioPosition.decode(data)?.let { _position.value = it }
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

    private fun sendCmd(msg: BenshiMessage) = sendBatch(msg)

    private fun sendBatch(vararg messages: BenshiMessage) {
        if (!transport.isConnected) return
        if (commands.trySend(CommandBatch(epoch.get(), transport, messages.toList())).isFailure) {
            _errorEvent.tryEmit("Radio command queue is full; please retry")
        }
    }

    private fun applySettings(settings: RadioSettings) {
        sendBatch(RadioCommands.writeSettings(settings.patchRawData()), RadioCommands.storeSettings())
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
