package com.hamradio.aaos.vm

/**
 * Tracks whether the app has keyed the radio, so PTT down/up are sent exactly once per press.
 *
 * Both edges currently send the same PF_MAIN_PTT command, so a duplicate down (a second tap,
 * a recomposed button) or a duplicate up would flip the radio's state instead of confirming it.
 * The latch drops the duplicate. When the radio reports that TX ended on its own (its timeout,
 * a dropped link), the latch clears without sending anything.
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
