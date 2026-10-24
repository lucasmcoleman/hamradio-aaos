package com.hamradio.aaos.diag

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Offsets from the start of BONDING at which a PIN is offered without a pairing request. */
internal val PIN_OFFER_AT_MS = listOf(1_000L, 3_000L, 6_000L)

/**
 * Unprompted PIN offers for one pairing attempt (ACTION_PAIRING_REQUEST may only reach
 * privileged apps). Runs in the attempt's own [scope] and is cancelled when the bond settles
 * or the attempt ends, so an offer can never land on a later attempt (codex #955).
 */
internal class PinOffer(
    private val scope: CoroutineScope,
    private val stillBonding: () -> Boolean,
    private val offer: () -> String,
    private val report: (String) -> Unit,
    private val atMs: List<Long> = PIN_OFFER_AT_MS,
) {
    private var job: Job? = null

    @Synchronized fun onBonding() {
        if (job?.isActive == true) return
        job = scope.launch {
            var elapsed = 0L
            atMs.forEachIndexed { i, at ->
                delay(at - elapsed)
                elapsed = at
                if (!isActive || !stillBonding()) return@launch
                report("unprompted PIN offer ${i + 1} at ${at / 1000} s: ${offer()}")
            }
        }
    }

    @Synchronized fun cancel() {
        job?.cancel()
        job = null
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
