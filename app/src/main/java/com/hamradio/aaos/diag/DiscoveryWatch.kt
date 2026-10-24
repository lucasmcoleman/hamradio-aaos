package com.hamradio.aaos.diag

import kotlinx.coroutines.CompletableDeferred

/**
 * Tracks one classic discovery for the `scan` command. Only a STARTED seen after [arm] counts,
 * and only a FINISHED after that STARTED completes [finished], so a broadcast left over from an
 * earlier discovery can't end the new scan early (codex #947).
 */
internal const val SCAN_COMPLETE = "completed"

/** How far a scan got; only [SCAN_COMPLETE] makes "the radio did not answer" meaningful (codex #948). */
internal fun scanOutcome(requested: Boolean, sawStarted: Boolean, finished: Boolean): String = when {
    !requested -> "inconclusive: startDiscovery refused"
    !sawStarted -> "inconclusive: discovery never reported starting"
    !finished -> "inconclusive: discovery did not finish within 12 s"
    else -> SCAN_COMPLETE
}

internal class DiscoveryWatch {
    @Volatile private var armed = false
    @Volatile private var started = false
    val sawStarted: Boolean get() = started
    val finished = CompletableDeferred<Unit>()

    /** Call right before startDiscovery(). */
    fun arm() { armed = true }

    fun onStarted() { if (armed) started = true }

    fun onFinished() { if (started) finished.complete(Unit) }
}
