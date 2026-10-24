package com.hamradio.aaos.diag

import java.net.InetAddress
import java.util.UUID

/**
 * Fixed command set for the Diagnostics link. Anything that doesn't parse is refused;
 * there is no shell or free-form code path.
 */
sealed class DiagCommand {
    object Help : DiagCommand()
    object Info : DiagCommand()
    object Bonded : DiagCommand()
    object Profiles : DiagCommand()
    object Off : DiagCommand()
    object Scan : DiagCommand()
    data class Prop(val key: String) : DiagCommand()
    data class Uuids(val address: String) : DiagCommand()
    /** Pair, answering a PIN request with [pin] if given; [transport] "bredr"/"le" forces one (null = auto). */
    data class Bond(val address: String, val pin: String? = null, val transport: String? = null) : DiagCommand()

    /** Wait [seconds] for a pairing request from the saved radio (Pair pressed on the radio). */
    data class PairWatch(val seconds: Int, val pin: String? = null) : DiagCommand()
    data class Gatt(val address: String) : DiagCommand()
    data class Log(val lines: Int) : DiagCommand()

    /** RFCOMM by service [uuid] or raw [channel], then read for [readMs]. */
    data class Rfcomm(
        val address: String,
        val uuid: UUID?,
        val channel: Int?,
        val secure: Boolean,
        val readMs: Long,
    ) : DiagCommand()

    data class Invalid(val reason: String) : DiagCommand()

    companion object {
        private val ADDRESS = Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$")
        private val PROP_KEY = Regex("^[a-zA-Z0-9._-]{1,92}$")
        private val SHORT_UUID = Regex("^[0-9a-fA-F]{4}$")

        const val USAGE = """commands:
  info                         device, OS and permission summary
  prop <key>                   one system property (e.g. ro.build.fingerprint)
  bonded                       paired devices
  scan                         classic discovery (12 s); reports whether the saved radio answered
  profiles                     Bluetooth profile states on this head unit
  uuids <addr>                 cached + fresh SDP service UUIDs of a device
  rfcomm <addr> <uuid|1101|ch:N> <secure|insecure> [readMs]
                               open an RFCOMM socket, read briefly, close
  bond <addr> [pin:NNNN] [bredr|le]
                               start pairing and report the pairing request type and
                               bond state changes; a PIN request is answered with NNNN
  pairwatch [seconds] [pin:NNNN]
                               wait (default 60 s) for the saved radio to ask to pair
                               and report it; a PIN request is answered with NNNN
  gatt <addr>                  list a device's BLE services
  log [lines]                  this app's recent log
  off                          turn the link off"""

        fun parse(line: String): DiagCommand {
            val parts = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (parts.isEmpty()) return Invalid("empty command")
            val args = parts.drop(1)
            return when (parts[0].lowercase()) {
                "help" -> Help
                "info" -> Info
                "bonded" -> Bonded
                "profiles" -> Profiles
                "off" -> Off
                "scan" -> if (args.isEmpty()) Scan else Invalid("usage: scan")
                "prop" -> args.singleOrNull()?.takeIf { PROP_KEY.matches(it) }?.let { Prop(it) }
                    ?: Invalid("usage: prop <key>")
                "uuids" -> address(args)?.let { Uuids(it) } ?: Invalid("usage: uuids <addr>")
                "bond" -> parseBond(args)
                "pairwatch" -> parsePairWatch(args)
                "gatt" -> address(args)?.let { Gatt(it) } ?: Invalid("usage: gatt <addr>")
                "log" -> {
                    val n = args.firstOrNull()?.toIntOrNull() ?: 200
                    if (args.size > 1 || n !in 1..2000) Invalid("usage: log [1-2000]") else Log(n)
                }
                "rfcomm" -> parseRfcomm(args)
                else -> Invalid("unknown command '${parts[0]}' (try help)")
            }
        }

        private fun address(args: List<String>): String? =
            args.singleOrNull()?.uppercase()?.takeIf { ADDRESS.matches(it) }

        private val PIN = Regex("^pin:([0-9]{4,16})$")

        private fun parseBond(args: List<String>): DiagCommand {
            val usage = Invalid("usage: bond <addr> [pin:NNNN] [bredr|le]")
            if (args.size !in 1..3) return usage
            val addr = address(args.take(1)) ?: return usage
            var pin: String? = null
            var transport: String? = null
            for (a in args.drop(1)) {
                val m = PIN.matchEntire(a)
                when {
                    m != null && pin == null -> pin = m.groupValues[1]
                    a.lowercase() in setOf("bredr", "le") && transport == null -> transport = a.lowercase()
                    else -> return usage
                }
            }
            return Bond(addr, pin, transport)
        }

        private fun parsePairWatch(args: List<String>): DiagCommand {
            val usage = Invalid("usage: pairwatch [10-120] [pin:NNNN]")
            if (args.size > 2) return usage
            var seconds: Int? = null
            var pin: String? = null
            for (a in args) {
                val m = PIN.matchEntire(a)
                when {
                    m != null && pin == null -> pin = m.groupValues[1]
                    a.toIntOrNull() != null && seconds == null -> seconds = a.toInt()
                    else -> return usage
                }
            }
            val s = seconds ?: 60
            return if (s in 10..120) PairWatch(s, pin) else usage
        }

        private fun parseRfcomm(args: List<String>): DiagCommand {
            val usage = Invalid("usage: rfcomm <addr> <uuid|1101|ch:N> <secure|insecure> [readMs]")
            if (args.size !in 3..4) return usage
            val addr = address(args.take(1)) ?: return usage
            val secure = when (args[2].lowercase()) {
                "secure" -> true
                "insecure" -> false
                else -> return usage
            }
            val readMs = args.getOrNull(3)?.toLongOrNull() ?: 1500L
            if (readMs !in 0..10_000) return usage
            val target = args[1]
            if (target.startsWith("ch:")) {
                val ch = target.removePrefix("ch:").toIntOrNull() ?: return usage
                if (ch !in 1..30) return usage
                return Rfcomm(addr, null, ch, secure, readMs)
            }
            val uuid = when {
                SHORT_UUID.matches(target) -> UUID.fromString("0000$target-0000-1000-8000-00805F9B34FB")
                else -> runCatching { UUID.fromString(target) }.getOrNull() ?: return usage
            }
            return Rfcomm(addr, uuid, null, secure, readMs)
        }

        /** Bluetooth address a command acts on, if any (only the saved radio is allowed). */
        fun targetAddress(cmd: DiagCommand): String? = when (cmd) {
            is Uuids -> cmd.address
            is Bond -> cmd.address
            is Gatt -> cmd.address
            is Rfcomm -> cmd.address
            else -> null
        }

        /** Refusal text when [cmd] targets anything but [savedRadio], else null. */
        fun targetRefusal(cmd: DiagCommand, savedRadio: String?): String? {
            val target = targetAddress(cmd) ?: return null
            if (target == savedRadio?.uppercase()) return null
            return "refused: $target is not the radio saved in the app (${savedRadio ?: "none saved"})"
        }

        /**
         * Pre-filter only, to keep stray LAN connections from raising the Allow prompt:
         * Lucas's PC or loopback (adb forward). Authentication is the pinned TLS client
         * certificate plus the on-screen approval, not this check.
         */
        fun isAllowedPeer(peer: InetAddress, allowedHost: String): Boolean =
            peer.isLoopbackAddress || peer.hostAddress == allowedHost
    }
}
