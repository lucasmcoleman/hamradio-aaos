package com.hamradio.aaos.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** codex #947: an earlier discovery's FINISHED must not end the new scan. */
class DiscoveryWatchTest {
    @Test fun oldFinishedAfterNewRequestIsIgnored() {
        val watch = DiscoveryWatch()
        watch.arm()
        watch.onFinished()                       // left over from the previous discovery
        assertFalse(watch.finished.isCompleted)
        watch.onStarted()
        watch.onFinished()
        assertTrue(watch.finished.isCompleted)
    }

    @Test fun onlyAFullRunIsConclusive() {
        assertEquals(SCAN_COMPLETE, scanOutcome(requested = true, sawStarted = true, finished = true))
        listOf(
            scanOutcome(false, false, false), scanOutcome(true, false, false), scanOutcome(true, true, false),
        ).forEach { assertTrue(it, it.startsWith("inconclusive")) }
    }

    @Test fun startedBeforeArmDoesNotCount() {
        val watch = DiscoveryWatch()
        watch.onStarted()
        watch.arm()
        watch.onFinished()
        assertFalse(watch.finished.isCompleted)
    }
}
