package com.hamradio.aaos.diag

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Offsets from the start of BONDING at which a PIN is offered without a pairing request. */
internal val PIN_OFFER_AT_MS = listOf(1_000L, 3_000L, 6_000L)

/**
 * One pairing attempt's permission to touch Bluetooth. Once [close]d it stays closed, and
 * [runIfOpen] holds the same lock as [close], so no action can start after close returns
 * (codex #956).
 */
internal class AttemptGate {
    private val lock = Any()
    private var closed = false

    fun close() = synchronized(lock) { closed = true }

    /** Runs [action] only while open; [ready] is checked under the lock right before it. */
    fun <T> runIfOpen(ready: () -> Boolean = { true }, action: () -> T): T? = synchronized(lock) {
        if (closed || !ready() || closed) null else action()
    }
}

/**
 * Unprompted PIN offers for one pairing attempt (ACTION_PAIRING_REQUEST may only reach
 * privileged apps). Starts at most once per attempt, and every offer goes through the
 * attempt's [gate], so nothing fires after the bond settles or the attempt ends.
 */
internal class PinOffer(
    private val scope: CoroutineScope,
    private val gate: AttemptGate,
    private val stillBonding: () -> Boolean,
    private val offer: () -> String,
    private val report: (String) -> Unit,
    private val atMs: List<Long> = PIN_OFFER_AT_MS,
) {
    private var started = false
    private var job: Job? = null

    @Synchronized fun onBonding() {
        if (started) return
        started = true
        job = scope.launch {
            var elapsed = 0L
            atMs.forEachIndexed { i, at ->
                delay(at - elapsed)
                elapsed = at
                val result = gate.runIfOpen(stillBonding, offer) ?: return@launch
                report("unprompted PIN offer ${i + 1} at ${at / 1000} s: $result")
            }
        }
    }

    /** Closes the attempt's gate (permanent) and stops the schedule. */
    @Synchronized fun close() {
        started = true
        gate.close()
        job?.cancel()
    }
}

private val PIN_ARG = Regex("pin:[0-9]+")

/** Command text safe to print or keep in a log: PIN digits replaced. */
internal fun redactPin(text: String): String = PIN_ARG.replace(text, "pin:****")

/** One-line pairing request report; never includes the PIN or the pairing key itself. */
internal fun pairingRequestText(variant: Int, hasKey: Boolean, action: String): String {
    val name = when (variant) {
        0 -> "PIN"
        1 -> "passkey entry"
        2 -> "passkey confirmation"
        3 -> "consent"
        4 -> "display passkey"
        5 -> "display PIN"
        6 -> "OOB consent"
        7 -> "16-digit PIN"
        else -> "unknown"
    }
    return "pairing request: variant=$variant ($name)${if (hasKey) " key=(redacted)" else ""}; $action"
}
