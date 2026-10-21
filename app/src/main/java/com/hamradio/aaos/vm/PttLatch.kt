package com.hamradio.aaos.vm

/**
 * Remembers which PTT request the UI sent last, so toggle mode knows whether the next tap means
 * start or stop, even while the controller is still starting capture.
 *
 * Every request is forwarded. RadioController ignores repeated starts and stops, so forwarding is
 * always safe, while dropping a stop could leave the mic streaming. RadioController.isPttActive
 * feeds [onRadioTx]: when transmit ends on its own (app timeout, focus loss, dropped link), the
 * intent clears so the next toggle tap starts again.
 */
class PttLatch(
    private val sendDown: () -> Unit,
    private val sendUp: () -> Unit,
) {
    @Volatile var keyed: Boolean = false
        private set
    private var sawTx = false

    @Synchronized
    fun down() {
        keyed = true
        sawTx = false
        sendDown()
    }

    @Synchronized
    fun up() {
        keyed = false
        sendUp()
    }

    /** Feed the controller's transmit state. Clears the intent if transmit stopped on its own. */
    @Synchronized
    fun onRadioTx(isInTx: Boolean): Boolean {
        if (!keyed) return false
        if (isInTx) { sawTx = true; return false }
        if (!sawTx) return false          // capture hasn't started yet; keep waiting
        keyed = false
        return true
    }
}
