package com.hamradio.aaos.radio.protocol

// ---------------------------------------------------------------------------
// Enums
// ---------------------------------------------------------------------------

enum class ModulationType(val code: Int) {
    FM(0), AM(1), DMR(2);
    companion object { fun fromCode(c: Int) = entries.firstOrNull { it.code == c } ?: FM }
}

enum class BandwidthType(val code: Int) {
    NARROW(0), WIDE(1);
    companion object { fun fromCode(c: Int) = if (c == 1) WIDE else NARROW }
}

enum class RadioNotification(val code: Int) {
    UNKNOWN(0),
    HT_STATUS_CHANGED(1),
    DATA_RXD(2),
    NEW_INQUIRY_DATA(3),
    RESTORE_FACTORY_SETTINGS(4),
    HT_CH_CHANGED(5),
    HT_SETTINGS_CHANGED(6),
    RINGING_STOPPED(7),
    RADIO_STATUS_CHANGED(8),
    USER_ACTION(9),
    SYSTEM_EVENT(10),
    BSS_SETTINGS_CHANGED(11),
    DATA_TXD(12),
    POSITION_CHANGE(13);
    companion object { fun fromCode(c: Int) = entries.firstOrNull { it.code == c } ?: UNKNOWN }
}

enum class ReplyStatus(val code: Int) {
    SUCCESS(0),
    NOT_SUPPORTED(1),
    NOT_AUTHENTICATED(2),
    INSUFFICIENT_RESOURCES(3),
    AUTHENTICATING(4),
    INVALID_PARAMETER(5),
    INCORRECT_STATE(6),
    IN_PROGRESS(7);
    companion object { fun fromCode(c: Int) = entries.firstOrNull { it.code == c } ?: NOT_SUPPORTED }
}

// ---------------------------------------------------------------------------
// Sub-audio (CTCSS / DCS)
// ---------------------------------------------------------------------------

sealed class SubAudio {
    object None : SubAudio()
    data class Ctcss(val hz: Float) : SubAudio()  // e.g. 88.5
    data class Dcs(val code: Int) : SubAudio()    // e.g. 023
}

fun Int.toSubAudio(): SubAudio = when {
    this == 0    -> SubAudio.None
    this < 6700  -> SubAudio.Dcs(this)
    else         -> SubAudio.Ctcss(this / 100f)
}

fun SubAudio.toRaw(): Int = when (this) {
    is SubAudio.None    -> 0
    is SubAudio.Dcs     -> code
    is SubAudio.Ctcss   -> (hz * 100).toInt()
}

// ---------------------------------------------------------------------------
// RF Channel
// ---------------------------------------------------------------------------

data class RfChannel(
    val channelId: Int,
    val name: String,
    val txFreqHz: Long,           // frequency in Hz
    val rxFreqHz: Long,
    val txMod: ModulationType = ModulationType.FM,
    val rxMod: ModulationType = ModulationType.FM,
    val txSubAudio: SubAudio = SubAudio.None,
    val rxSubAudio: SubAudio = SubAudio.None,
    val scan: Boolean = true,
    val txAtMaxPower: Boolean = true,
    val txAtMedPower: Boolean = false,
    val talkAround: Boolean = false,
    val bandwidth: BandwidthType = BandwidthType.WIDE,
    val preDeEmphBypass: Boolean = false,
    val sign: Boolean = false,
    val txDisable: Boolean = false,
    val fixedFreq: Boolean = false,
    val fixedBandwidth: Boolean = false,
    val fixedTxPower: Boolean = false,
    val mute: Boolean = false,
) {
    val txFreqMhz: Double get() = txFreqHz / 1_000_000.0
    val rxFreqMhz: Double get() = rxFreqHz / 1_000_000.0

    companion object {
        fun decode(bytes: ByteArray): RfChannel? {
            if (bytes.size < 30) return null
            val channelId = bytes[0].toInt() and 0xFF
            val txMod = ModulationType.fromCode((bytes[1].toInt() shr 6) and 0x03)
            val txFreqRaw = getInt(bytes, 1) and 0x3FFFFFFF
            val rxMod = ModulationType.fromCode((bytes[5].toInt() shr 6) and 0x03)
            val rxFreqRaw = getInt(bytes, 5) and 0x3FFFFFFF
            val txSubAudio = getShort(bytes, 9).toSubAudio()
            val rxSubAudio = getShort(bytes, 11).toSubAudio()
            val flags1 = bytes[13].toInt() and 0xFF
            val flags2 = bytes[14].toInt() and 0xFF
            val name = bytes.slice(15..24)
                .map { it.toInt().and(0xFF).toChar() }
                .joinToString("")
                .trimEnd('\u0000')
            return RfChannel(
                channelId   = channelId,
                name        = name,
                txFreqHz    = txFreqRaw * 100L,
                rxFreqHz    = rxFreqRaw * 100L,
                txMod       = txMod,
                rxMod       = rxMod,
                txSubAudio  = txSubAudio,
                rxSubAudio  = rxSubAudio,
                scan              = (flags1 and 0x80) != 0,
                txAtMaxPower      = (flags1 and 0x40) != 0,
                talkAround        = (flags1 and 0x20) != 0,
                bandwidth         = BandwidthType.fromCode((flags1 shr 4) and 0x01),
                preDeEmphBypass   = (flags1 and 0x08) != 0,
                sign              = (flags1 and 0x04) != 0,
                txAtMedPower      = (flags1 and 0x02) != 0,
                txDisable         = (flags1 and 0x01) != 0,
                fixedFreq         = (flags2 and 0x80) != 0,
                fixedBandwidth    = (flags2 and 0x40) != 0,
                fixedTxPower      = (flags2 and 0x20) != 0,
                mute              = (flags2 and 0x10) != 0,
            )
        }

        fun encode(ch: RfChannel): ByteArray {
            val buf = ByteArray(25)
            buf[0] = ch.channelId.toByte()
            val txFreqRaw = (ch.txFreqHz / 100L).toInt()
            val rxFreqRaw = (ch.rxFreqHz / 100L).toInt()
            putInt(buf, 1, (ch.txMod.code shl 30) or (txFreqRaw and 0x3FFFFFFF))
            putInt(buf, 5, (ch.rxMod.code shl 30) or (rxFreqRaw and 0x3FFFFFFF))
            putShort(buf, 9,  ch.txSubAudio.toRaw())
            putShort(buf, 11, ch.rxSubAudio.toRaw())
            var f1 = 0
            if (ch.scan)             f1 = f1 or 0x80
            if (ch.txAtMaxPower)     f1 = f1 or 0x40
            if (ch.talkAround)       f1 = f1 or 0x20
            f1 = f1 or (ch.bandwidth.code shl 4)
            if (ch.preDeEmphBypass)  f1 = f1 or 0x08
            if (ch.sign)             f1 = f1 or 0x04
            if (ch.txAtMedPower)     f1 = f1 or 0x02
            if (ch.txDisable)        f1 = f1 or 0x01
            buf[13] = f1.toByte()
            var f2 = 0
            if (ch.fixedFreq)        f2 = f2 or 0x80
            if (ch.fixedBandwidth)   f2 = f2 or 0x40
            if (ch.fixedTxPower)     f2 = f2 or 0x20
            if (ch.mute)             f2 = f2 or 0x10
            buf[14] = f2.toByte()
            val nameBytes = ch.name.padEnd(10, '\u0000').take(10).toByteArray(Charsets.US_ASCII)
            nameBytes.copyInto(buf, 15)
            return buf
        }
    }
}

// ---------------------------------------------------------------------------
// Radio Settings
// ---------------------------------------------------------------------------

data class RadioSettings(
    val channelA: Int = 0,
    val channelB: Int = 1,
    val scan: Boolean = false,
    val doubleChannel: Int = 1,       // 0=off 1=dual 2=triple
    val squelchLevel: Int = 4,
    val tailElim: Boolean = true,
    val autoRelayEn: Boolean = false,
    val autoPowerOn: Boolean = false,
    val keepAghfpLink: Boolean = false,
    val micGain: Int = 4,
    val txHoldTime: Int = 0,
    val txTimeLimit: Int = 0,
    val localSpeaker: Int = 2,
    val btMicGain: Int = 4,
    val adaptiveResponse: Boolean = false,
    val disTone: Boolean = false,
    val powerSavingMode: Boolean = false,
    val autoPowerOff: Int = 0,
    val hmSpeaker: Int = 0,
    val positioningSystem: Int = 0,   // 0=GPS 1=BDS 2=GPS+BDS
    val timeOffset: Int = 0,
    val pttLock: Boolean = false,
    val screenTimeout: Int = 3,
    val imperialUnit: Boolean = false,
    val vfo1ModFreqHz: Long = 144_390_000L,
    val vfo2ModFreqHz: Long = 446_000_000L,
    val rawData: ByteArray = ByteArray(0),
) {
    companion object {
        fun decode(bytes: ByteArray): RadioSettings? {
            if (bytes.size < 17) return null  // bytes[0] = reply_status, payload from [1]
            val d = bytes
            val chALower  = (d[0].toInt() and 0xF0) shr 4
            val chBLower  = d[0].toInt() and 0x0F
            val chAUpper  = (d[9].toInt() and 0xF0)
            val chBUpper  = ((d[9].toInt() and 0x0F) shl 4)
            return RadioSettings(
                channelA         = chAUpper or chALower,
                channelB         = chBUpper or chBLower,
                scan             = (d[1].toInt() and 0x80) != 0,
                doubleChannel    = (d[1].toInt() shr 4) and 0x03,
                squelchLevel     = d[1].toInt() and 0x0F,
                tailElim         = (d[2].toInt() and 0x80) != 0,
                autoRelayEn      = (d[2].toInt() and 0x40) != 0,
                autoPowerOn      = (d[2].toInt() and 0x20) != 0,
                keepAghfpLink    = (d[2].toInt() and 0x10) != 0,
                micGain          = (d[2].toInt() shr 1) and 0x07,
                localSpeaker     = (d[4].toInt() shr 6) and 0x03,
                btMicGain        = (d[4].toInt() shr 3) and 0x07,
                adaptiveResponse = (d[4].toInt() and 0x04) != 0,
                disTone          = (d[4].toInt() and 0x02) != 0,
                powerSavingMode  = (d[4].toInt() and 0x01) != 0,
                autoPowerOff     = (d[5].toInt() shr 4) and 0x0F,
                hmSpeaker        = (d[6].toInt() shr 6) and 0x03,
                positioningSystem= (d[6].toInt() shr 2) and 0x0F,
                pttLock          = (d[7].toInt() and 0x04) != 0,
                screenTimeout    = (d[8].toInt() shr 3) and 0x1F,
                imperialUnit     = (d[8].toInt() and 0x01) != 0,
                vfo1ModFreqHz    = getInt(d, 12).toLong() * 100L,
                vfo2ModFreqHz    = getInt(d, 16).toLong() * 100L,
                rawData          = bytes.copyOf(),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// HT (Transceiver) Status
// ---------------------------------------------------------------------------

data class HtStatus(
    val isPowerOn: Boolean,
    val isInTx: Boolean,
    val isSquelchOpen: Boolean,
    val isInRx: Boolean,
    val doubleChannel: Int,
    val isScan: Boolean,
    val isRadioMode: Boolean,   // FM radio (not ham) mode
    val isGpsLocked: Boolean,
    val isHfpConnected: Boolean,
    val isAocConnected: Boolean,
    val channelId: Int,
    val rssi: Int,              // 0–15
    val region: Int,
) {
    companion object {
        val DISCONNECTED = HtStatus(
            isPowerOn = false, isInTx = false, isSquelchOpen = false,
            isInRx = false, doubleChannel = 0, isScan = false,
            isRadioMode = false, isGpsLocked = false, isHfpConnected = false,
            isAocConnected = false, channelId = 0, rssi = 0, region = 0
        )

        fun decode(bytes: ByteArray): HtStatus? {
            if (bytes.size < 2) return null
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            val rssi:   Int
            val region: Int
            val chUpper: Int
            if (bytes.size >= 4) {
                val b2 = bytes[2].toInt() and 0xFF
                val b3 = bytes[3].toInt() and 0xFF
                rssi    = (b2 shr 4) and 0x0F
                region  = ((b2 and 0x0F) shl 2) or (b3 shr 6)
                chUpper = (b3 shr 2) and 0x0F
            } else {
                rssi = 0; region = 0; chUpper = 0
            }
            val chLower = (b1 shr 4) and 0x0F
            return HtStatus(
                isPowerOn       = (b0 and 0x80) != 0,
                isInTx          = (b0 and 0x40) != 0,
                isSquelchOpen   = (b0 and 0x20) != 0,
                isInRx          = (b0 and 0x10) != 0,
                doubleChannel   = (b0 shr 2) and 0x03,
                isScan          = (b0 and 0x02) != 0,
                isRadioMode     = (b0 and 0x01) != 0,
                isGpsLocked     = (b1 and 0x08) != 0,
                isHfpConnected  = (b1 and 0x04) != 0,
                isAocConnected  = (b1 and 0x02) != 0,
                channelId       = (chUpper shl 4) or chLower,
                rssi            = rssi,
                region          = region,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// BSS / APRS Settings
// ---------------------------------------------------------------------------

data class BssSettings(
    val maxFwdTimes: Int = 3,
    val timeToLive: Int = 7,
    val pttReleaseSendLocation: Boolean = true,
    val pttReleaseSendIdInfo: Boolean = true,
    val pttReleaseSendBssUserId: Boolean = true,
    val shouldShareLocation: Boolean = true,
    val sendPwrVoltage: Boolean = false,
    val packetFormat: Int = 0,       // 0=BSS 1=APRS
    val allowPositionCheck: Boolean = true,
    val aprsSsid: Int = 0,
    val locationShareIntervalSec: Int = 60,
    val bssUserIdLower: Long = 0,
    val pttReleaseIdInfo: String = "",
    val beaconMessage: String = "",
    val aprsSymbol: String = "/>",
    val aprsCallsign: String = "",
) {
    companion object {
        fun decode(bytes: ByteArray): BssSettings? {
            if (bytes.size < 46) return null
            val decodeAscii = { arr: ByteArray, start: Int, len: Int ->
                arr.slice(start until start + len)
                    .map { it.toInt().and(0xFF).toChar() }
                    .joinToString("").trimEnd('\u0000')
            }
            return BssSettings(
                maxFwdTimes             = (bytes[0].toInt() and 0xF0) shr 4,
                timeToLive              = bytes[0].toInt() and 0x0F,
                pttReleaseSendLocation  = (bytes[1].toInt() and 0x80) != 0,
                pttReleaseSendIdInfo    = (bytes[1].toInt() and 0x40) != 0,
                pttReleaseSendBssUserId = (bytes[1].toInt() and 0x20) != 0,
                shouldShareLocation     = (bytes[1].toInt() and 0x10) != 0,
                sendPwrVoltage          = (bytes[1].toInt() and 0x08) != 0,
                packetFormat            = (bytes[1].toInt() shr 2) and 0x01,
                allowPositionCheck      = (bytes[1].toInt() and 0x02) != 0,
                aprsSsid                = (bytes[2].toInt() and 0xF0) shr 4,
                locationShareIntervalSec= (bytes[3].toInt() and 0xFF) * 10,
                bssUserIdLower          = (bytes[4].toLong() and 0xFF) or
                                          ((bytes[5].toLong() and 0xFF) shl 8) or
                                          ((bytes[6].toLong() and 0xFF) shl 16) or
                                          ((bytes[7].toLong() and 0xFF) shl 24),
                pttReleaseIdInfo        = decodeAscii(bytes, 8, 12),
                beaconMessage           = decodeAscii(bytes, 20, 18),
                aprsSymbol              = decodeAscii(bytes, 38, 2),
                aprsCallsign            = decodeAscii(bytes, 40, 6),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Device Info
// ---------------------------------------------------------------------------

data class DeviceInfo(
    val vendorId: Int,
    val productId: Int,
    val hwVersion: Int,
    val softVersion: Int,
    val supportRadio: Boolean,
    val supportMediumPower: Boolean,
    val haveNoSpeaker: Boolean,
    val regionCount: Int,
    val supportNoaa: Boolean,
    val gmrs: Boolean,
    val supportVfo: Boolean,
    val supportDmr: Boolean,
    val channelCount: Int,
    val freqRangeCount: Int,
) {
    companion object {
        fun decode(bytes: ByteArray): DeviceInfo? {
            if (bytes.size < 10) return null
            return DeviceInfo(
                vendorId          = bytes[0].toInt() and 0xFF,
                productId         = getShort(bytes, 1),
                hwVersion         = bytes[3].toInt() and 0xFF,
                softVersion       = getShort(bytes, 4),
                supportRadio      = (bytes[6].toInt() and 0x80) != 0,
                supportMediumPower= (bytes[6].toInt() and 0x40) != 0,
                haveNoSpeaker     = (bytes[6].toInt() and 0x08) != 0,
                regionCount       = ((bytes[6].toInt() and 0x03) shl 4) or ((bytes[7].toInt() and 0xF0) shr 4),
                supportNoaa       = (bytes[7].toInt() and 0x08) != 0,
                gmrs              = (bytes[7].toInt() and 0x04) != 0,
                supportVfo        = (bytes[7].toInt() and 0x02) != 0,
                supportDmr        = (bytes[7].toInt() and 0x01) != 0,
                channelCount      = bytes[8].toInt() and 0xFF,
                freqRangeCount    = (bytes[9].toInt() and 0xF0) shr 4,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Byte helpers
// ---------------------------------------------------------------------------

fun getShort(bytes: ByteArray, offset: Int): Int =
    ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

fun getInt(bytes: ByteArray, offset: Int): Int =
    ((bytes[offset].toInt() and 0xFF) shl 24) or
    ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
    ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
    (bytes[offset + 3].toInt() and 0xFF)

fun putShort(buf: ByteArray, offset: Int, value: Int) {
    buf[offset]     = (value shr 8).toByte()
    buf[offset + 1] = value.toByte()
}

fun putInt(buf: ByteArray, offset: Int, value: Int) {
    buf[offset]     = (value shr 24).toByte()
    buf[offset + 1] = (value shr 16).toByte()
    buf[offset + 2] = (value shr 8).toByte()
    buf[offset + 3] = value.toByte()
}
