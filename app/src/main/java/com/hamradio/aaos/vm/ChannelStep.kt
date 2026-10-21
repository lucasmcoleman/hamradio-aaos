package com.hamradio.aaos.vm

/** Channel up/down over the radio's stored channels, wrapping at either end. */
object ChannelStep {
    /**
     * The channel id [delta] steps from [current] in [ids] (sorted ascending, wrapping).
     * Returns null when there's nowhere to go: no channels, or only the current one.
     * If [current] isn't in the list, steps from where it would sit.
     */
    fun next(ids: List<Int>, current: Int, delta: Int): Int? {
        val sorted = ids.distinct().sorted()
        if (sorted.isEmpty() || delta == 0) return null
        if (sorted.size == 1 && sorted[0] == current) return null
        val at = sorted.indexOf(current)
        val n = sorted.size
        val idx = if (at >= 0) {
            Math.floorMod(at + delta, n)
        } else {
            val insert = sorted.indexOfFirst { it > current }.let { if (it < 0) n else it }
            Math.floorMod(if (delta > 0) insert + delta - 1 else insert + delta, n)
        }
        return sorted[idx].takeIf { it != current }
    }
}
