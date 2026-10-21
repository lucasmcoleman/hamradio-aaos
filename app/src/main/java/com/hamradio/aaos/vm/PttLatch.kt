package com.hamradio.aaos.vm

/**
 * Tracks whether the user is holding (or has toggled on) PTT, so each press sends one start and
 * one stop request to RadioController.
 *
 * The controller starts and stops mic streaming explicitly and ignores repeats, so the latch is
 * about the UI: it collapses duplicate requests (a second tap, the gesture-cancel release plus the
 * screen-dispose release) and lets toggle mode know which request comes next. It's fed
 * RadioController.isPttActive; when transmit ends on its own (app timeout, focus loss, dropped
 * link), the latch clears without sending anything.
 */
class PttLatch(
    private val sendDown: () -> Unit,
    private val sendUp: () -> Unit,
) {
    @Volatile var keyed: Boolean = false
        private set
    private var sawTx = false

    @Synchronized
    fun down(): Boolean {
        if (keyed) return false
        keyed = true
        sawTx = false
        sendDown()
        return true
    }

    @Synchronized
    fun up(): Boolean {
        if (!keyed) return false
        keyed = false
        sendUp()
        return true
    }

    /** Feed the radio's reported TX state. Clears the latch if TX stopped while we held it. */
    @Synchronized
    fun onRadioTx(isInTx: Boolean): Boolean {
        if (!keyed) return false
        if (isInTx) { sawTx = true; return false }
        if (!sawTx) return false          // radio hasn't reported TX yet; keep waiting
        keyed = false
        return true
    }
}
