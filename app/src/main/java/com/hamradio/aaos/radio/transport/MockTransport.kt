package com.hamradio.aaos.radio.transport

import com.hamradio.aaos.radio.protocol.BasicCommand
import com.hamradio.aaos.radio.protocol.BenshiMessage
import com.hamradio.aaos.radio.protocol.BssSettings
import com.hamradio.aaos.radio.protocol.CommandGroup
import com.hamradio.aaos.radio.protocol.DeviceInfo
import com.hamradio.aaos.radio.protocol.HtStatus
import com.hamradio.aaos.radio.protocol.ModulationType
import com.hamradio.aaos.radio.protocol.PowerStatusType
import com.hamradio.aaos.radio.protocol.RadioCommands
import com.hamradio.aaos.radio.protocol.RadioNotification
import com.hamradio.aaos.radio.protocol.RadioSettings
import com.hamradio.aaos.radio.protocol.RfChannel
import com.hamradio.aaos.radio.protocol.SubAudio
import com.hamradio.aaos.radio.protocol.putInt
import com.hamradio.aaos.radio.protocol.putShort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Mock Benshi radio transport — simulates a Radioddity DB50-B for development
 * and testing when no physical radio is present.
 *
 * Responds to commands with realistic simulated data and emits periodic
 * status notifications (RX activity, GPS lock changes, etc.).
 */
class MockTransport(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
) : IRadioTransport {

    // -----------------------------------------------------------------------
    // Simulated radio state
    // -----------------------------------------------------------------------

    private var currentChannelA = 0
    private var currentChannelB = 7
    private var volumeLevel = 8
    private var isScanActive = false
    private var batteryPercent = 87
    private var gpsLocked = true
    private var isInRx = false
    private var isInTx = false
    private var squelchOpen = false
    private val subscribedNotifications = mutableSetOf<Int>()

    /** Stored settings bytes — persisted across read/write cycles. */
    private var storedSettingsData: ByteArray? = null
    private var storedBssData: ByteArray? = null

    /** Simulated channel list — representative ham channels */
    private val channelList: MutableList<RfChannel> = mutableListOf(
        rfCh(0,  146_520_000, 146_520_000, SubAudio.None,          SubAudio.None,          "SIMPLEX"),
        rfCh(1,  146_460_000, 146_460_000, SubAudio.None,          SubAudio.None,          "LOCAL"),
        rfCh(2,  146_940_000, 147_540_000, SubAudio.Ctcss(100.0f), SubAudio.Ctcss(100.0f), "COUNTY1"),
        rfCh(3,  147_000_000, 146_400_000, SubAudio.Ctcss(103.5f), SubAudio.Ctcss(103.5f), "COUNTY2"),
        rfCh(4,  147_180_000, 146_580_000, SubAudio.Ctcss(127.3f), SubAudio.Ctcss(127.3f), "HILLTOP"),
        rfCh(5,  443_000_000, 448_000_000, SubAudio.Ctcss(88.5f),  SubAudio.Ctcss(88.5f),  "UHF-RPT"),
        rfCh(6,  446_000_000, 446_000_000, SubAudio.None,          SubAudio.None,          "UHF-SIM"),
        rfCh(7,  144_390_000, 144_390_000, SubAudio.None,          SubAudio.None,          "APRS"),
        rfCh(8,  147_435_000, 146_835_000, SubAudio.Ctcss(110.9f), SubAudio.Ctcss(110.9f), "EMER-1"),
        rfCh(9,  155_340_000, 155_340_000, SubAudio.None,          SubAudio.None,          "SKYWRN"),
        rfCh(10, 162_400_000, 162_400_000, SubAudio.None,          SubAudio.None,          "WX-1"),
        rfCh(11, 162_425_000, 162_425_000, SubAudio.None,          SubAudio.None,          "WX-2"),
        rfCh(12, 162_450_000, 162_450_000, SubAudio.None,          SubAudio.None,          "WX-3"),
        rfCh(13, 446_025_000, 446_025_000, SubAudio.Dcs(23),       SubAudio.Dcs(23),       "GMRS-1"),
        rfCh(14, 446_050_000, 446_050_000, SubAudio.Dcs(71),       SubAudio.Dcs(71),       "GMRS-2"),
        rfCh(15, 446_075_000, 446_075_000, SubAudio.None,          SubAudio.None,          "GMRS-3"),
    )

    // -----------------------------------------------------------------------
    // Transport interface
    // -----------------------------------------------------------------------

    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState = _state.asStateFlow()

    private val _inbound = MutableSharedFlow<BenshiMessage>(extraBufferCapacity = 64)
    override val inboundMessages: Flow<BenshiMessage> = _inbound.asSharedFlow()

    override val isConnected: Boolean get() = _state.value == ConnectionState.CONNECTED

    private var statusJob: Job? = null

    override suspend fun connect() {
        _state.value = ConnectionState.SCANNING
        delay(400)
        _state.value = ConnectionState.CONNECTING
        delay(600)
        _state.value = ConnectionState.CONNECTED

        // Emit periodic status updates and simulated events
        statusJob = scope.launch { statusLoop() }
    }

    override suspend fun send(message: BenshiMessage) {
        if (!isConnected) return
        // Small propagation delay
        delay(30)
        handleCommand(message)
    }

    override suspend fun disconnect() {
        statusJob?.cancel()
        statusJob = null
        _state.value = ConnectionState.DISCONNECTED
    }

    // -----------------------------------------------------------------------
    // Command handling
    // -----------------------------------------------------------------------

    private suspend fun handleCommand(msg: BenshiMessage) {
        if (msg.isReply) return
        when {
            msg.commandGroup == CommandGroup.BASIC -> handleBasicCommand(msg)
        }
    }

    private suspend fun handleBasicCommand(msg: BenshiMessage) {
        when (msg.command) {
            BasicCommand.GET_DEV_INFO       -> replyDevInfo()
            BasicCommand.GET_HT_STATUS      -> replyHtStatus()
            BasicCommand.READ_SETTINGS      -> replySettings()
            BasicCommand.READ_BSS_SETTINGS  -> replyBssSettings()
            BasicCommand.READ_RF_CH         -> replyChannel(msg.body.firstOrNull()?.toInt()?.and(0xFF) ?: 0)
            BasicCommand.GET_VOLUME         -> replyVolume()
            BasicCommand.SET_VOLUME         -> { volumeLevel = msg.body.firstOrNull()?.toInt()?.and(0xFF) ?: volumeLevel; replyOk(msg.command) }
            BasicCommand.SET_IN_SCAN        -> handleScan(msg.body.firstOrNull()?.toInt() != 0)
            BasicCommand.WRITE_SETTINGS     -> applySettings(msg.body)
            BasicCommand.WRITE_BSS_SETTINGS -> applyBssSettings(msg.body)
            BasicCommand.WRITE_RF_CH        -> applyChannel(msg.body)
            BasicCommand.STORE_SETTINGS     -> replyOk(msg.command)
            BasicCommand.READ_STATUS        -> replyBattery(msg.body.getOrNull(1)?.toInt()?.and(0xFF) ?: PowerStatusType.BATTERY_PERCENTAGE)
            BasicCommand.REGISTER_NOTIFICATION -> { subscribedNotifications.add(msg.body.firstOrNull()?.toInt() ?: 0); replyOk(msg.command) }
            BasicCommand.CANCEL_NOTIFICATION   -> { subscribedNotifications.remove(msg.body.firstOrNull()?.toInt() ?: 0); replyOk(msg.command) }
            BasicCommand.GET_POSITION       -> replyPosition()
            BasicCommand.SET_HT_ON_OFF      -> replyOk(msg.command)  // power on/off only
            BasicCommand.DO_PROG_FUNC       -> handleProgFunc(msg.body)
            BasicCommand.STOP_RINGING       -> replyOk(msg.command)
            BasicCommand.GET_APRS_PATH      -> replyAprsPath()
            else                            -> replyOk(msg.command)
        }
    }

    // -----------------------------------------------------------------------
    // Reply builders
    // -----------------------------------------------------------------------

    private suspend fun emit(msg: BenshiMessage) = _inbound.emit(msg)

    private suspend fun replyDevInfo() {
        // vendor=0x42 product=0x0001 hw=1 sw=0x0115 caps...
        val body = ByteArray(10)
        body[0] = 0x42               // vendor: Benshi
        putShort(body, 1, 0x0001)    // product ID
        body[3] = 0x01               // HW ver
        putShort(body, 4, 0x0115)    // SW ver (v1.21)
        body[6] = 0xC2.toByte()      // support_radio, support_med_power, region_count upper
        body[7] = 0x07               // noaa, gmrs, vfo, dmr flags + region lower
        body[8] = 16                 // 16 channels
        body[9] = 0x10               // 1 freq range
        emit(BenshiMessage(CommandGroup.BASIC, BasicCommand.GET_DEV_INFO, true, byteArrayOf(0x00) + body))
    }

    private suspend fun replyHtStatus() {
        val status = buildHtStatusBytes()
        emit(BenshiMessage(CommandGroup.BASIC, BasicCommand.GET_HT_STATUS, true, byteArrayOf(0x00) + status))
    }

    private suspend fun replyVolume() {
        emit(BenshiMessage(CommandGroup.BASIC, BasicCommand.GET_VOLUME, true,
            byteArrayOf(0x00, volumeLevel.toByte())))
    }

    private suspend fun replyOk(command: Int) {
        emit(BenshiMessage(CommandGroup.BASIC, command, true, byteArrayOf(0x00)))
    }

    private suspend fun replyChannel(id: Int) {
        val ch = channelList.getOrNull(id) ?: channelList[0]
        val encoded = RfChannel.encode(ch)
        emit(BenshiMessage(CommandGroup.BASIC, BasicCommand.READ_RF_CH, true,
            byteArrayOf(0x00) + encoded))
    }

    private suspend fun replySettings() {
        val body = storedSettingsData ?: buildDefaultSettingsData()
        // Always reflect current channel/scan state from mock's live state
        body[0]  = ((currentChannelA and 0x0F) shl 4 or (currentChannelB and 0x0F)).toByte()
        body[1]  = (body[1].toInt() and 0x7F or (if (isScanActive) 0x80 else 0)).toByte()
        body[9]  = ((currentChannelA and 0xF0) or ((currentChannelB and 0xF0) shr 4)).toByte()
        emit(BenshiMessage(CommandGroup.BASIC, BasicCommand.READ_SETTINGS, true, byteArrayOf(0x00) + body))
    }

    private fun buildDefaultSettingsData(): ByteArray {
        val body = ByteArray(22)  // 22 bytes: 20 data + 2 trailing padding required by radio
        body[0]  = ((currentChannelA and 0x0F) shl 4 or (currentChannelB and 0x0F)).toByte()
        body[1]  = ((if (isScanActive) 0x80 else 0) or 0x10 or 0x04).toByte()  // dual-watch=1, squelch=4
        body[4]  = ((2 shl 6) or (4 shl 3)).toByte()   // local spk=2, bt mic gain=4
        body[9]  = ((currentChannelA and 0xF0) or ((currentChannelB and 0xF0) shr 4)).toByte()
        putInt(body, 12, 144_390_000)  // VFO1 = 144.390 MHz
        putInt(body, 16, 446_000_000)  // VFO2 = 446.000 MHz
        return body
    }

    private suspend fun replyBssSettings() {
        val body = storedBssData ?: buildDefaultBssData()
        emit(BenshiMessage(CommandGroup.BASIC, BasicCommand.READ_BSS_SETTINGS, true, byteArrayOf(0x00) + body))
    }

    private fun buildDefaultBssData(): ByteArray {
        val body = ByteArray(46)
        body[0] = ((3 shl 4) or 7).toByte()  // maxFwd=3, ttl=7
        body[1] = 0xFE.toByte()              // all flags set except packet format
        body[2] = 0x00
        body[3] = 6                           // 60 second interval
        "N0CALL".forEachIndexed { i, c -> body[40 + i] = c.code.toByte() }
        "/>".forEachIndexed { i, c -> body[38 + i] = c.code.toByte() }
        return body
    }

    private suspend fun replyBattery(type: Int) {
        val value = when (type) {
            PowerStatusType.BATTERY_PERCENTAGE -> batteryPercent
            PowerStatusType.BATTERY_VOLTAGE    -> 790  // 7.90V
            else                               -> batteryPercent
        }
        emit(BenshiMessage(CommandGroup.BASIC, BasicCommand.READ_STATUS, true,
            byteArrayOf(0x00, type.toByte(), (value shr 8).toByte(), value.toByte())))
    }

    private suspend fun replyPosition() {
        // Latitude: 37.7749 N, Longitude: -122.4194 W (San Francisco)
        val latRaw  = (37.7749  * 1_000_000).toInt()
        val lonRaw  = (-122.4194 * 1_000_000).toInt()
        val altRaw  = 52  // meters
        val body = ByteArray(12)
        putInt(body, 0, latRaw)
        putInt(body, 4, lonRaw)
        putInt(body, 8, altRaw)
        emit(BenshiMessage(CommandGroup.BASIC, BasicCommand.GET_POSITION, true, byteArrayOf(0x00) + body))
    }

    private suspend fun replyAprsPath() {
        val path = "WIDE1-1,WIDE2-1".padEnd(20, '\u0000')
        emit(BenshiMessage(CommandGroup.BASIC, BasicCommand.GET_APRS_PATH, true,
            byteArrayOf(0x00) + path.toByteArray(Charsets.US_ASCII)))
    }

    private suspend fun applySettings(body: ByteArray) {
        if (body.size >= 10) {
            storedSettingsData = body.copyOf()
            currentChannelA = ((body[9].toInt() and 0xF0)) or ((body[0].toInt() and 0xF0) shr 4)
            currentChannelB = ((body[9].toInt() and 0x0F) shl 4) or (body[0].toInt() and 0x0F)
            isScanActive    = (body[1].toInt() and 0x80) != 0
        }
        replyOk(BasicCommand.WRITE_SETTINGS)
        if (RadioNotification.HT_SETTINGS_CHANGED.code in subscribedNotifications) {
            emitNotification(RadioNotification.HT_SETTINGS_CHANGED)
        }
    }

    private suspend fun applyBssSettings(body: ByteArray) {
        if (body.size >= 46) {
            storedBssData = body.copyOf()
        }
        replyOk(BasicCommand.WRITE_BSS_SETTINGS)
        if (RadioNotification.BSS_SETTINGS_CHANGED.code in subscribedNotifications) {
            emitNotification(RadioNotification.BSS_SETTINGS_CHANGED)
        }
    }

    private suspend fun handleScan(enable: Boolean) {
        isScanActive = enable
        // Also update stored settings so READ_SETTINGS returns the new scan state
        storedSettingsData?.let { data ->
            data[1] = (data[1].toInt() and 0x7F or (if (enable) 0x80 else 0)).toByte()
        }
        replyOk(BasicCommand.SET_IN_SCAN)
        if (RadioNotification.HT_STATUS_CHANGED.code in subscribedNotifications) {
            emitNotification(RadioNotification.HT_STATUS_CHANGED, buildHtStatusBytes())
        }
        // Trigger settings re-read so SettingsScreen.scan updates
        if (RadioNotification.HT_SETTINGS_CHANGED.code in subscribedNotifications) {
            emitNotification(RadioNotification.HT_SETTINGS_CHANGED)
        }
    }

    private suspend fun handleProgFunc(body: ByteArray) {
        if (body.size >= 2) {
            // PF structure: byte0 = [buttonId(4)][action(4)], byte1 = [effect(8)]
            val action = body[0].toInt() and 0x0F
            val effect = body[1].toInt() and 0xFF
            when (effect) {
                13, 14 -> { // MAIN_PTT, SUB_PTT
                    isInTx = action == 1 // SHORT = start TX, LONG_RELEASE = stop TX
                }
                18 -> { // TOGGLE_AB_CH
                    val tmp = currentChannelA
                    currentChannelA = currentChannelB
                    currentChannelB = tmp
                }
            }
        }
        replyOk(BasicCommand.DO_PROG_FUNC)
        if (RadioNotification.HT_STATUS_CHANGED.code in subscribedNotifications) {
            emitNotification(RadioNotification.HT_STATUS_CHANGED, buildHtStatusBytes())
        }
    }

    private suspend fun applyChannel(body: ByteArray) {
        if (body.size >= 25) {
            val decoded = RfChannel.decode(body)
            if (decoded != null && decoded.channelId <= 200) {
                val idx = decoded.channelId
                while (channelList.size <= idx) channelList.add(channelList.last().copy(channelId = channelList.size))
                channelList[idx] = decoded
            }
        }
        replyOk(BasicCommand.WRITE_RF_CH)
    }

    // -----------------------------------------------------------------------
    // Simulated event loop
    // -----------------------------------------------------------------------

    private suspend fun statusLoop() {
        var tick = 0
        while (true) {
            delay(2000)
            tick++

            // Simulate occasional squelch openings (RX activity)
            if (tick % 7 == 0 && !isInTx) {
                simulateRx()
            }

            // Slowly drain battery
            if (tick % 30 == 0 && batteryPercent > 0) {
                batteryPercent--
            }

            // GPS lock toggling
            if (tick % 47 == 0) {
                gpsLocked = !gpsLocked
            }

            // Periodic status notification
            if (RadioNotification.HT_STATUS_CHANGED.code in subscribedNotifications) {
                emitNotification(RadioNotification.HT_STATUS_CHANGED, buildHtStatusBytes())
            }
        }
    }

    private suspend fun simulateRx() {
        isInRx = true; squelchOpen = true
        if (RadioNotification.HT_STATUS_CHANGED.code in subscribedNotifications) {
            emitNotification(RadioNotification.HT_STATUS_CHANGED, buildHtStatusBytes())
        }
        delay((1000..4000).random().toLong())
        isInRx = false; squelchOpen = false
        if (RadioNotification.HT_STATUS_CHANGED.code in subscribedNotifications) {
            emitNotification(RadioNotification.HT_STATUS_CHANGED, buildHtStatusBytes())
        }
    }

    private suspend fun emitNotification(notification: RadioNotification, data: ByteArray = ByteArray(0)) {
        emit(BenshiMessage(CommandGroup.BASIC, BasicCommand.EVENT_NOTIFICATION, false,
            byteArrayOf(notification.code.toByte()) + data))
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun buildHtStatusBytes(): ByteArray {
        val b0 = (0x80) or                                // power on
                 (if (isInTx) 0x40 else 0) or
                 (if (squelchOpen) 0x20 else 0) or
                 (if (isInRx) 0x10 else 0) or
                 0x04 or                                   // dual-watch
                 (if (isScanActive) 0x02 else 0)           // scan
        val b1 = ((currentChannelA and 0x0F) shl 4) or
                 (if (gpsLocked) 0x08 else 0)
        val rssi = if (isInRx) (6..12).random() else 0
        val b2 = (rssi shl 4) or ((0) and 0x0F)
        val b3 = ((currentChannelA shr 4) shl 2) and 0xFF
        return byteArrayOf(b0.toByte(), b1.toByte(), b2.toByte(), b3.toByte())
    }

    private fun rfCh(
        id: Int, txHz: Long, rxHz: Long,
        txSub: SubAudio, rxSub: SubAudio, name: String,
    ) = RfChannel(
        channelId   = id,
        name        = name,
        txFreqHz    = txHz,
        rxFreqHz    = rxHz,
        txSubAudio  = txSub,
        rxSubAudio  = rxSub,
        txAtMaxPower = true,
        scan         = true,
        bandwidth    = com.hamradio.aaos.radio.protocol.BandwidthType.WIDE,
    )
}
