package com.hamradio.aaos.radio

import android.util.Log
import com.hamradio.aaos.radio.protocol.RadioPosition
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private const val TAG = "SmartBeacon"

/**
 * Computes APRS beacon interval based on speed and heading change.
 *
 * Algorithm (standard SmartBeaconing):
 * - Stopped (< 3 mph): beacon every [slowInterval] seconds
 * - Moving (> [fastSpeedMph]): beacon every [fastInterval] seconds
 * - In between: linear interpolation
 * - Turn detected (heading change > [turnThreshold]°): beacon immediately
 *
 * Defaults match standard APRS SmartBeaconing for mobile:
 * - Fast: 90 seconds at 60+ mph
 * - Slow: 1800 seconds (30 min) when stopped
 * - Turn: 30° heading change triggers immediate beacon
 */
data class SmartBeaconConfig(
    val enabled: Boolean = false,
    val fastInterval: Int = 90,        // seconds at fast speed
    val slowInterval: Int = 1800,      // seconds when stopped
    val fastSpeedMph: Int = 60,        // mph for fastest interval
    val slowSpeedMph: Int = 3,         // mph below which = stopped
    val turnThreshold: Int = 30,       // degrees of heading change
    val turnInterval: Int = 15,        // minimum seconds between turn beacons
)

object SmartBeacon {

    private var lastHeading: Int = -1
    private var lastBeaconTime: Long = 0

    /**
     * Compute the beacon interval based on current position/speed.
     * Returns interval in seconds, or 0 if an immediate beacon should be sent.
     */
    fun computeInterval(position: RadioPosition, config: SmartBeaconConfig): Int {
        if (!config.enabled) return -1  // not active

        val speedMph = position.speedMph
        val heading = position.headingDegrees
        val now = System.currentTimeMillis() / 1000

        // Check for turn
        if (lastHeading >= 0 && speedMph > config.slowSpeedMph) {
            val headingChange = abs(heading - lastHeading).let { if (it > 180) 360 - it else it }
            if (headingChange >= config.turnThreshold) {
                val elapsed = now - lastBeaconTime
                if (elapsed >= config.turnInterval) {
                    Log.d(TAG, "Turn detected: $headingChange° change, beacon now")
                    lastHeading = heading
                    lastBeaconTime = now
                    return 0  // beacon immediately
                }
            }
        }
        lastHeading = heading

        // Speed-based interval
        val interval = when {
            speedMph <= config.slowSpeedMph -> config.slowInterval
            speedMph >= config.fastSpeedMph -> config.fastInterval
            else -> {
                // Linear interpolation
                val speedRange = config.fastSpeedMph - config.slowSpeedMph
                val intervalRange = config.slowInterval - config.fastInterval
                val fraction = (speedMph - config.slowSpeedMph) / speedRange.toDouble()
                (config.slowInterval - fraction * intervalRange).toInt()
            }
        }

        return max(config.fastInterval, min(config.slowInterval, interval))
    }

    fun markBeaconSent() {
        lastBeaconTime = System.currentTimeMillis() / 1000
    }

    fun reset() {
        lastHeading = -1
        lastBeaconTime = 0
    }
}
