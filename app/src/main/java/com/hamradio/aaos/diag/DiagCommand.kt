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
    data class Bond(val address: String) : DiagCommand()
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
  bond <addr>                  start pairing and report bond state changes
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
                "bond" -> address(args)?.let { Bond(it) } ?: Invalid("usage: bond <addr>")
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
