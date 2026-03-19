package com.hamradio.aaos.radio.protocol

/**
 * Encodes and decodes Benshi radio messages.
 *
 * Over BLE:    raw bytes, no GAIA wrapper.
 * Over RFCOMM: wrapped in a GAIA frame (see GaiaFrame).
 *
 * Wire layout of the message payload:
 *   Bits  0-15:  command_group  (16-bit big-endian)
 *   Bit   16:    is_reply       (1 bit, MSB of next word)
 *   Bits 17-31:  command        (15 bits)
 *   Bits 32+:    body           (variable)
 */
data class BenshiMessage(
    val commandGroup: Int,
    val command: Int,
    val isReply: Boolean,
    val body: ByteArray,
) {
    companion object {
        /** Decode a raw byte array into a BenshiMessage. */
        fun decode(bytes: ByteArray): BenshiMessage? {
            if (bytes.size < 4) return null
            val group   = ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
            val word2   = ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF)
            val isReply = (word2 and 0x8000) != 0
            val command = word2 and 0x7FFF
            val body    = bytes.copyOfRange(4, bytes.size)
            return BenshiMessage(group, command, isReply, body)
        }

        /** Build a command (not a reply). */
        fun command(group: Int, command: Int, body: ByteArray = ByteArray(0)): BenshiMessage =
            BenshiMessage(group, command, false, body)

        /** Build a basic-group command. */
        fun basic(command: Int, body: ByteArray = ByteArray(0)): BenshiMessage =
            command(CommandGroup.BASIC, command, body)
    }

    /** Encode to raw bytes (suitable for BLE write or GAIA payload). */
    fun encode(): ByteArray {
        val out = ByteArray(4 + body.size)
        out[0] = (commandGroup shr 8).toByte()
        out[1] = commandGroup.toByte()
        val word2 = (if (isReply) 0x8000 else 0) or (command and 0x7FFF)
        out[2] = (word2 shr 8).toByte()
        out[3] = word2.toByte()
        body.copyInto(out, 4)
        return out
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BenshiMessage) return false
        return commandGroup == other.commandGroup &&
               command == other.command &&
               isReply == other.isReply &&
               body.contentEquals(other.body)
    }

    override fun hashCode(): Int {
        var r = commandGroup
        r = 31 * r + command
        r = 31 * r + isReply.hashCode()
        r = 31 * r + body.contentHashCode()
        return r
    }
}

// ---------------------------------------------------------------------------
// GAIA frame (Bluetooth Classic RFCOMM transport)
// ---------------------------------------------------------------------------

/**
 * GAIA frame wrapper for Bluetooth Classic / RFCOMM transport.
 *
 * Format: [0xFF][0x01][flags][payload_len][4 cmd bytes + payload][checksum?]
 * payload_len = body.size   (does NOT include the 4-byte command header)
 */
object GaiaFrame {
    private const val SYNC: Byte    = 0xFF.toByte()
    private const val VERSION: Byte = 0x01
    private const val FLAG_CHECKSUM = 0x01

    fun encode(message: BenshiMessage, withChecksum: Boolean = false): ByteArray {
        val msgBytes = message.encode()
        val payloadLen = msgBytes.size - 4  // subtract the 4-byte header
        val frameSize = 4 + msgBytes.size + (if (withChecksum) 1 else 0)
        val frame = ByteArray(frameSize)
        frame[0] = SYNC
        frame[1] = VERSION
        frame[2] = if (withChecksum) FLAG_CHECKSUM.toByte() else 0
        frame[3] = payloadLen.toByte()
        msgBytes.copyInto(frame, 4)
        if (withChecksum) {
            var cs = 0
            for (i in 1 until frameSize - 1) cs = cs xor (frame[i].toInt() and 0xFF)
            frame[frameSize - 1] = cs.toByte()
        }
        return frame
    }

    /**
     * Attempt to decode one GAIA frame from [buf] starting at [offset].
     * Returns (consumed_bytes, message) or (0, null) if incomplete, (-n, null) if sync error.
     */
    fun decode(buf: ByteArray, offset: Int = 0): Pair<Int, BenshiMessage?> {
        val remaining = buf.size - offset
        if (remaining < 8) return Pair(0, null)
        if (buf[offset] != SYNC || buf[offset + 1] != VERSION) {
            // find next sync byte
            for (i in offset + 1 until buf.size) {
                if (buf[i] == SYNC) return Pair(-(i - offset), null)
            }
            return Pair(-(remaining - 1), null)
        }
        val flags      = buf[offset + 2].toInt() and 0xFF
        val payloadLen = buf[offset + 3].toInt() and 0xFF
        val hasChecksum = (flags and FLAG_CHECKSUM) != 0
        val totalLen   = 4 + 4 + payloadLen + (if (hasChecksum) 1 else 0)
        if (remaining < totalLen) return Pair(0, null)
        val msgBytes = buf.copyOfRange(offset + 4, offset + 4 + 4 + payloadLen)
        val msg = BenshiMessage.decode(msgBytes) ?: return Pair(totalLen, null)
        return Pair(totalLen, msg)
    }
}

// ---------------------------------------------------------------------------
// Command builder helpers
// ---------------------------------------------------------------------------

object RadioCommands {

    fun getDevInfo() = BenshiMessage.basic(BasicCommand.GET_DEV_INFO)

    fun getHtStatus() = BenshiMessage.basic(BasicCommand.GET_HT_STATUS)

    fun getVolume() = BenshiMessage.basic(BasicCommand.GET_VOLUME)

    fun setVolume(level: Int): BenshiMessage {
        val body = ByteArray(1) { level.coerceIn(0, 15).toByte() }
        return BenshiMessage.basic(BasicCommand.SET_VOLUME, body)
    }

    fun getSettings() = BenshiMessage.basic(BasicCommand.READ_SETTINGS)

    fun getBssSettings() = BenshiMessage.basic(BasicCommand.READ_BSS_SETTINGS)

    fun readChannel(channelId: Int): BenshiMessage {
        val body = ByteArray(1) { channelId.toByte() }
        return BenshiMessage.basic(BasicCommand.READ_RF_CH, body)
    }

    fun registerNotification(notification: RadioNotification): BenshiMessage {
        val body = ByteArray(1) { notification.code.toByte() }
        return BenshiMessage.basic(BasicCommand.REGISTER_NOTIFICATION, body)
    }

    fun registerAllNotifications(): List<BenshiMessage> =
        RadioNotification.entries
            .filter { it != RadioNotification.UNKNOWN }
            .map { registerNotification(it) }

    fun readBatteryStatus(type: Int = PowerStatusType.BATTERY_PERCENTAGE): BenshiMessage {
        val body = ByteArray(2).also { it[1] = type.toByte() }
        return BenshiMessage.basic(BasicCommand.READ_STATUS, body)
    }

    fun setHtOnOff(on: Boolean): BenshiMessage {
        val body = ByteArray(1) { (if (on) 1 else 0).toByte() }
        return BenshiMessage.basic(BasicCommand.SET_HT_ON_OFF, body)
    }

    fun setScan(enable: Boolean): BenshiMessage {
        val body = ByteArray(1) { (if (enable) 1 else 0).toByte() }
        return BenshiMessage.basic(BasicCommand.SET_IN_SCAN, body)
    }

    fun writeChannel(channel: RfChannel): BenshiMessage {
        val body = RfChannel.encode(channel)
        return BenshiMessage.basic(BasicCommand.WRITE_RF_CH, body)
    }

    fun storeSettings() = BenshiMessage.basic(BasicCommand.STORE_SETTINGS)

    fun getPosition() = BenshiMessage.basic(BasicCommand.GET_POSITION)
}
