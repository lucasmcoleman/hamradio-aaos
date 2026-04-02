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
            if (bytes.size < 25) return null
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
                txFreqHz    = txFreqRaw.toLong(),
                rxFreqHz    = rxFreqRaw.toLong(),
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
            val txFreqRaw = ch.txFreqHz.toInt()
            val rxFreqRaw = ch.rxFreqHz.toInt()
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

/**
 * Radio settings — 22-byte wire format using bit-level packing.
 * Fields cross byte boundaries so BitReader/BitWriter must be used.
 * The trailing 2 bytes (padding) are required by the radio firmware.
 */
data class RadioSettings(
    val channelA: Int = 0,
    val channelB: Int = 1,
    val scan: Boolean = false,
    val aghfpCallMode: Boolean = false,
    val doubleChannel: Int = 1,       // 0=off 1=single 2=dual
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
    val autoShareLocCh: Int = 0,
    val hmSpeaker: Int = 0,
    val positioningSystem: Int = 0,   // 0=GPS 1=BDS 2=GPS+BDS
    val timeOffset: Int = 0,
    val useFreqRange2: Boolean = false,
    val pttLock: Boolean = false,
    val leadingSyncBitEn: Boolean = false,
    val pairingAtPowerOn: Boolean = false,
    val screenTimeout: Int = 3,
    val vfoX: Int = 0,
    val imperialUnit: Boolean = false,
    val wxMode: Int = 0,
    val noaaCh: Int = 0,
    val vfo1TxPower: Int = 0,         // 0=high 1=med 2=low
    val vfo2TxPower: Int = 0,
    val disDigitalMute: Boolean = false,
    val signalingEccEn: Boolean = false,
    val chDataLock: Boolean = false,
    val vfo1ModFreqHz: Long = 144_390_000L,
    val vfo2ModFreqHz: Long = 446_000_000L,
    val rawData: ByteArray = ByteArray(0),
) {
    /** Encode settings to 22-byte wire format using bit-level packing. */
    fun patchRawData(): ByteArray {
        val buf = ByteArray(22)
        val w = BitWriter(buf)
        w.writeBits(channelA and 0x0F, 4)           // channelA lower
        w.writeBits(channelB and 0x0F, 4)           // channelB lower
        w.writeBool(scan)
        w.writeBool(aghfpCallMode)
        w.writeBits(doubleChannel, 2)
        w.writeBits(squelchLevel, 4)
        w.writeBool(tailElim)
        w.writeBool(autoRelayEn)
        w.writeBool(autoPowerOn)
        w.writeBool(keepAghfpLink)
        w.writeBits(micGain, 3)
        w.writeBits(txHoldTime, 4)
        w.writeBits(txTimeLimit, 5)
        w.writeBits(localSpeaker, 2)
        w.writeBits(btMicGain, 3)
        w.writeBool(adaptiveResponse)
        w.writeBool(disTone)
        w.writeBool(powerSavingMode)
        w.writeBits(autoPowerOff, 3)
        w.writeBits(autoShareLocCh, 5)
        w.writeBits(hmSpeaker, 2)
        w.writeBits(positioningSystem, 4)
        w.writeBits(timeOffset, 6)
        w.writeBool(useFreqRange2)
        w.writeBool(pttLock)
        w.writeBool(leadingSyncBitEn)
        w.writeBool(pairingAtPowerOn)
        w.writeBits(screenTimeout, 5)
        w.writeBits(vfoX, 2)
        w.writeBool(imperialUnit)
        w.writeBits(channelA shr 4, 4)              // channelA upper
        w.writeBits((channelB shr 4) and 0x0F, 4)   // channelB upper
        w.writeBits(wxMode, 2)
        w.writeBits(noaaCh, 4)
        w.writeBits(vfo1TxPower, 2)
        w.writeBits(vfo2TxPower, 2)
        w.writeBool(disDigitalMute)
        w.writeBool(signalingEccEn)
        w.writeBool(chDataLock)
        w.writeBits(0, 3)                           // padding
        // VFO frequencies (32 bits each, big-endian, stored as Hz)
        putInt(buf, 12, vfo1ModFreqHz.toInt())
        putInt(buf, 16, vfo2ModFreqHz.toInt())
        // bytes 20-21: trailing padding required by radio firmware
        return buf
    }

    companion object {
        /** Wire size including the 2-byte trailing padding. */
        const val WIRE_SIZE = 22

        fun decode(bytes: ByteArray): RadioSettings? {
            if (bytes.size < 20) return null
            val r = BitReader(bytes)
            val chALower = r.readBits(4)
            val chBLower = r.readBits(4)
            val scan = r.readBool()
            val aghfpCallMode = r.readBool()
            val doubleChannel = r.readBits(2)
            val squelchLevel = r.readBits(4)
            val tailElim = r.readBool()
            val autoRelayEn = r.readBool()
            val autoPowerOn = r.readBool()
            val keepAghfpLink = r.readBool()
            val micGain = r.readBits(3)
            val txHoldTime = r.readBits(4)
            val txTimeLimit = r.readBits(5)
            val localSpeaker = r.readBits(2)
            val btMicGain = r.readBits(3)
            val adaptiveResponse = r.readBool()
            val disTone = r.readBool()
            val powerSavingMode = r.readBool()
            val autoPowerOff = r.readBits(3)
            val autoShareLocCh = r.readBits(5)
            val hmSpeaker = r.readBits(2)
            val positioningSystem = r.readBits(4)
            val timeOffset = r.readBits(6)
            val useFreqRange2 = r.readBool()
            val pttLock = r.readBool()
            val leadingSyncBitEn = r.readBool()
            val pairingAtPowerOn = r.readBool()
            val screenTimeout = r.readBits(5)
            val vfoX = r.readBits(2)
            val imperialUnit = r.readBool()
            val chAUpper = r.readBits(4)
            val chBUpper = r.readBits(4)
            val wxMode = r.readBits(2)
            val noaaCh = r.readBits(4)
            val vfo1TxPower = r.readBits(2)
            val vfo2TxPower = r.readBits(2)
            val disDigitalMute = r.readBool()
            val signalingEccEn = r.readBool()
            val chDataLock = r.readBool()
            r.readBits(3) // padding

            return RadioSettings(
                channelA         = (chAUpper shl 4) or chALower,
                channelB         = (chBUpper shl 4) or chBLower,
                scan             = scan,
                aghfpCallMode    = aghfpCallMode,
                doubleChannel    = doubleChannel,
                squelchLevel     = squelchLevel,
                tailElim         = tailElim,
                autoRelayEn      = autoRelayEn,
                autoPowerOn      = autoPowerOn,
                keepAghfpLink    = keepAghfpLink,
                micGain          = micGain,
                txHoldTime       = txHoldTime,
                txTimeLimit      = txTimeLimit,
                localSpeaker     = localSpeaker,
                btMicGain        = btMicGain,
                adaptiveResponse = adaptiveResponse,
                disTone          = disTone,
                powerSavingMode  = powerSavingMode,
                autoPowerOff     = autoPowerOff,
                autoShareLocCh   = autoShareLocCh,
                hmSpeaker        = hmSpeaker,
                positioningSystem= positioningSystem,
                timeOffset       = timeOffset,
                useFreqRange2    = useFreqRange2,
                pttLock          = pttLock,
                leadingSyncBitEn = leadingSyncBitEn,
                pairingAtPowerOn = pairingAtPowerOn,
                screenTimeout    = screenTimeout,
                vfoX             = vfoX,
                imperialUnit     = imperialUnit,
                wxMode           = wxMode,
                noaaCh           = noaaCh,
                vfo1TxPower      = vfo1TxPower,
                vfo2TxPower      = vfo2TxPower,
                disDigitalMute   = disDigitalMute,
                signalingEccEn   = signalingEccEn,
                chDataLock       = chDataLock,
                vfo1ModFreqHz    = if (bytes.size >= 16) getInt(bytes, 12).toLong() else 144_390_000L,
                vfo2ModFreqHz    = if (bytes.size >= 20) getInt(bytes, 16).toLong() else 446_000_000L,
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
    val locationShareIntervalSec: Int = 120,
    val bssUserIdLower: Long = 0,
    val pttReleaseIdInfo: String = "",
    val beaconMessage: String = "",
    val aprsSymbol: String = "/>",
    val aprsCallsign: String = "",
    val rawData: ByteArray = ByteArray(0),
) {
    fun patchRawData(): ByteArray {
        val buf = if (rawData.size >= 46) rawData.copyOf() else ByteArray(46)
        buf[0] = (((maxFwdTimes and 0x0F) shl 4) or (timeToLive and 0x0F)).toByte()
        buf[1] = ((if (pttReleaseSendLocation) 0x80 else 0) or
                  (if (pttReleaseSendIdInfo) 0x40 else 0) or
                  (if (pttReleaseSendBssUserId) 0x20 else 0) or
                  (if (shouldShareLocation) 0x10 else 0) or
                  (if (sendPwrVoltage) 0x08 else 0) or
                  ((packetFormat and 0x01) shl 2) or
                  (if (allowPositionCheck) 0x02 else 0) or
                  (buf[1].toInt() and 0x01)).toByte()
        buf[2] = (((aprsSsid and 0x0F) shl 4) or (buf[2].toInt() and 0x0F)).toByte()
        buf[3] = (locationShareIntervalSec / 10).coerceIn(0, 255).toByte()
        // bssUserIdLower: little-endian
        buf[4] = (bssUserIdLower and 0xFF).toByte()
        buf[5] = ((bssUserIdLower shr 8) and 0xFF).toByte()
        buf[6] = ((bssUserIdLower shr 16) and 0xFF).toByte()
        buf[7] = ((bssUserIdLower shr 24) and 0xFF).toByte()
        pttReleaseIdInfo.padEnd(12, '\u0000').take(12).toByteArray(Charsets.US_ASCII).copyInto(buf, 8)
        beaconMessage.padEnd(18, '\u0000').take(18).toByteArray(Charsets.US_ASCII).copyInto(buf, 20)
        aprsSymbol.padEnd(2, '\u0000').take(2).toByteArray(Charsets.US_ASCII).copyInto(buf, 38)
        aprsCallsign.padEnd(6, '\u0000').take(6).toByteArray(Charsets.US_ASCII).copyInto(buf, 40)
        return buf
    }

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
                rawData                 = bytes.copyOf(),
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
// GPS Position (from GET_POSITION reply)
// ---------------------------------------------------------------------------

data class RadioPosition(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val altitudeMeters: Int = 0,
    val speedKnots: Int = 0,
    val headingDegrees: Int = 0,
    val timestampUtc: Long = 0,
    val accuracy: Int = 0,
    val locked: Boolean = false,
) {
    val speedMph: Double get() = speedKnots * 1.15078
    val speedKmh: Double get() = speedKnots * 1.852

    companion object {
        /**
         * Decode position from GET_POSITION reply payload.
         * Format from HTCommander:
         *   Bytes 0-2: latitude raw (24-bit, value / 60 / 500 = degrees)
         *   Bytes 3-5: longitude raw (24-bit, same encoding)
         *   Bytes 6-7: altitude (16-bit, meters)
         *   Bytes 8-9: speed (16-bit, knots)
         *   Bytes 10-11: heading (16-bit, degrees)
         *   Bytes 12-15: unix timestamp (32-bit)
         *   Bytes 16-17: accuracy (16-bit)
         */
        fun decode(bytes: ByteArray): RadioPosition? {
            if (bytes.size < 6) return null
            val latRaw = ((bytes[0].toInt() and 0xFF) shl 16) or
                         ((bytes[1].toInt() and 0xFF) shl 8) or
                         (bytes[2].toInt() and 0xFF)
            val lonRaw = ((bytes[3].toInt() and 0xFF) shl 16) or
                         ((bytes[4].toInt() and 0xFF) shl 8) or
                         (bytes[5].toInt() and 0xFF)
            // Convert from raw to decimal degrees (signed 24-bit / 60 / 500)
            val latSigned = if (latRaw > 0x7FFFFF) latRaw - 0x1000000 else latRaw
            val lonSigned = if (lonRaw > 0x7FFFFF) lonRaw - 0x1000000 else lonRaw
            val lat = latSigned / 60.0 / 500.0
            val lon = lonSigned / 60.0 / 500.0

            val alt = if (bytes.size >= 8)
                ((bytes[6].toInt() and 0xFF) shl 8) or (bytes[7].toInt() and 0xFF) else 0
            val speed = if (bytes.size >= 10)
                ((bytes[8].toInt() and 0xFF) shl 8) or (bytes[9].toInt() and 0xFF) else 0
            val heading = if (bytes.size >= 12)
                ((bytes[10].toInt() and 0xFF) shl 8) or (bytes[11].toInt() and 0xFF) else 0
            val timestamp = if (bytes.size >= 16)
                ((bytes[12].toLong() and 0xFF) shl 24) or
                ((bytes[13].toLong() and 0xFF) shl 16) or
                ((bytes[14].toLong() and 0xFF) shl 8) or
                (bytes[15].toLong() and 0xFF) else 0L
            val accuracy = if (bytes.size >= 18)
                ((bytes[16].toInt() and 0xFF) shl 8) or (bytes[17].toInt() and 0xFF) else 0

            val locked = lat != 0.0 || lon != 0.0
            return RadioPosition(lat, lon, alt, speed, heading, timestamp, accuracy, locked)
        }
    }
}

// ---------------------------------------------------------------------------
// Bit-level reader/writer — for fields that cross byte boundaries
// ---------------------------------------------------------------------------

/** Reads fields of arbitrary bit width from a byte array. */
class BitReader(private val data: ByteArray) {
    var bitPos: Int = 0

    fun readBits(count: Int): Int {
        var result = 0
        for (i in 0 until count) {
            val byteIdx = bitPos / 8
            val bitIdx = 7 - (bitPos % 8)
            if (byteIdx < data.size) {
                result = (result shl 1) or ((data[byteIdx].toInt() shr bitIdx) and 1)
            } else {
                result = result shl 1
            }
            bitPos++
        }
        return result
    }

    fun readBool(): Boolean = readBits(1) != 0
}

/** Writes fields of arbitrary bit width into a byte array. */
class BitWriter(private val data: ByteArray) {
    var bitPos: Int = 0

    fun writeBits(value: Int, count: Int) {
        for (i in count - 1 downTo 0) {
            val bit = (value shr i) and 1
            val byteIdx = bitPos / 8
            val bitIdx = 7 - (bitPos % 8)
            if (byteIdx < data.size) {
                if (bit == 1) data[byteIdx] = (data[byteIdx].toInt() or (1 shl bitIdx)).toByte()
                else data[byteIdx] = (data[byteIdx].toInt() and (1 shl bitIdx).inv()).toByte()
            }
            bitPos++
        }
    }

    fun writeBool(value: Boolean) = writeBits(if (value) 1 else 0, 1)
}

// ---------------------------------------------------------------------------
// TNC Data Fragment — for APRS/BSS packet reception
// ---------------------------------------------------------------------------

data class TncDataFragment(
    val isFinal: Boolean,
    val withChannelId: Boolean,
    val fragmentId: Int,
    val payload: ByteArray,
    val channelId: Int?,
) {
    companion object {
        fun decode(data: ByteArray): TncDataFragment? {
            if (data.isEmpty()) return null
            val header = data[0].toInt() and 0xFF
            val isFinal = (header and 0x80) != 0
            val withCh = (header and 0x40) != 0
            val fragId = header and 0x3F
            val payloadEnd = if (withCh) data.size - 1 else data.size
            val payload = if (data.size > 1) data.copyOfRange(1, payloadEnd) else ByteArray(0)
            val chId = if (withCh && data.size > 1) data.last().toInt() and 0xFF else null
            return TncDataFragment(isFinal, withCh, fragId, payload, chId)
        }
    }
}

// ---------------------------------------------------------------------------
// AX.25 / APRS packet
// ---------------------------------------------------------------------------

data class AprsPacket(
    val source: String,
    val sourceSsid: Int,
    val destination: String,
    val destinationSsid: Int,
    val digipeaters: List<String>,
    val info: String,
    val latitude: Double?,
    val longitude: Double?,
) {
    companion object {
        /** Decode a raw AX.25 frame (after TNC fragment reassembly). */
        fun decode(raw: ByteArray): AprsPacket? {
            if (raw.size < 16) return null  // minimum: 7+7 address + control + pid + 1 byte info

            // AX.25 addresses: 7 bytes each, callsign chars are shifted left 1 bit
            fun decodeCallsign(data: ByteArray, offset: Int): Pair<String, Int> {
                val call = (0 until 6).map { i ->
                    ((data[offset + i].toInt() and 0xFF) shr 1).toChar()
                }.joinToString("").trimEnd()
                val ssid = (data[offset + 6].toInt() shr 1) and 0x0F
                return call to ssid
            }

            val (dest, destSsid) = decodeCallsign(raw, 0)
            val (src, srcSsid) = decodeCallsign(raw, 7)

            // Check for digipeaters (address extension bit)
            val digis = mutableListOf<String>()
            var addrEnd = 14
            if ((raw[13].toInt() and 0x01) == 0) {
                // More address fields follow
                while (addrEnd + 7 <= raw.size) {
                    val (digi, digiSsid) = decodeCallsign(raw, addrEnd)
                    digis.add(if (digiSsid > 0) "$digi-$digiSsid" else digi)
                    val isLast = (raw[addrEnd + 6].toInt() and 0x01) != 0
                    addrEnd += 7
                    if (isLast) break
                }
            }

            // Control (0x03 = UI frame) + PID (0xF0 = no layer 3)
            if (addrEnd + 2 > raw.size) return null
            val infoStart = addrEnd + 2
            if (infoStart > raw.size) return null
            val info = raw.copyOfRange(infoStart, raw.size)
                .map { it.toInt().and(0xFF).toChar() }
                .joinToString("")

            // Parse APRS position from info field
            val (lat, lon) = parseAprsPosition(info)

            return AprsPacket(
                source = src,
                sourceSsid = srcSsid,
                destination = dest,
                destinationSsid = destSsid,
                digipeaters = digis,
                info = info,
                latitude = lat,
                longitude = lon,
            )
        }

        /** Parse lat/lon from APRS info field (uncompressed positions). */
        private fun parseAprsPosition(info: String): Pair<Double?, Double?> {
            if (info.isEmpty()) return null to null
            val dataType = info[0]
            // Uncompressed position: !, =, /, @ followed by DDmm.mmN/DDDmm.mmW
            if (dataType in listOf('!', '=', '/', '@') && info.length >= 20) {
                val posStr = if (dataType in listOf('/', '@')) info.substring(8) else info.substring(1)
                if (posStr.length < 19) return null to null
                val lat = parseAprsLat(posStr.substring(0, 8))
                val lon = parseAprsLon(posStr.substring(9, 18))
                return lat to lon
            }
            return null to null
        }

        private fun parseAprsLat(s: String): Double? {
            if (s.length < 8) return null
            val deg = s.substring(0, 2).toDoubleOrNull() ?: return null
            val min = s.substring(2, 7).toDoubleOrNull() ?: return null
            val dir = s[7]
            val result = deg + min / 60.0
            return if (dir == 'S') -result else result
        }

        private fun parseAprsLon(s: String): Double? {
            if (s.length < 9) return null
            val deg = s.substring(0, 3).toDoubleOrNull() ?: return null
            val min = s.substring(3, 8).toDoubleOrNull() ?: return null
            val dir = s[8]
            val result = deg + min / 60.0
            return if (dir == 'W') -result else result
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
