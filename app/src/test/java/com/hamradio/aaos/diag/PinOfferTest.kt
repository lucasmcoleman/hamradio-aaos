package com.hamradio.aaos.diag

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unprompted PIN offers must stay inside their own pairing attempt (codex #955). */
@OptIn(ExperimentalCoroutinesApi::class)
class PinOfferTest {
    private class Probe(scope: TestScope, var bonding: Boolean = true) {
        var offers = 0
        val reports = mutableListOf<String>()
        val offer = PinOffer(scope.backgroundScope, { bonding }, { offers++; "setPin=true" }, { reports += it })
    }

    @Test fun offersAtEachStepWhileBonding() = runTest {
        val p = Probe(this)
        p.offer.onBonding()
        advanceTimeBy(999); runCurrent()
        assertEquals(0, p.offers)
        advanceTimeBy(1); runCurrent()
        assertEquals(1, p.offers)
        advanceTimeBy(10_000); runCurrent()   // backgroundScope work is skipped by advanceUntilIdle
        assertEquals(3, p.offers)
        assertTrue(p.reports.last().startsWith("unprompted PIN offer 3 at 6 s"))
    }

    @Test fun cancelStopsLaterOffers() = runTest {
        val p = Probe(this)
        p.offer.onBonding()
        advanceTimeBy(1_500); runCurrent()
        p.offer.cancel()                      // bond settled or the attempt ended
        advanceTimeBy(10_000); runCurrent()   // backgroundScope work is skipped by advanceUntilIdle
        assertEquals(1, p.offers)
    }

    @Test fun noOfferOnceBondingIsOver() = runTest {
        val p = Probe(this)
        p.offer.onBonding()
        p.bonding = false
        advanceTimeBy(10_000); runCurrent()   // backgroundScope work is skipped by advanceUntilIdle
        assertEquals(0, p.offers)
    }

    @Test fun repeatedBondingEventDoesNotDoubleOffers() = runTest {
        val p = Probe(this)
        p.offer.onBonding()
        advanceTimeBy(500); runCurrent()
        p.offer.onBonding()
        advanceTimeBy(10_000); runCurrent()   // backgroundScope work is skipped by advanceUntilIdle
        assertEquals(3, p.offers)
    }

    @Test fun cancelledAttemptNeverReachesTheNextOne() = runTest {
        val first = Probe(this)
        first.offer.onBonding()
        first.offer.cancel()
        val second = Probe(this)
        second.offer.onBonding()
        advanceTimeBy(10_000); runCurrent()   // backgroundScope work is skipped by advanceUntilIdle
        assertEquals(0, first.offers)
        assertEquals(3, second.offers)
    }

    @Test fun pinAndKeyNeverPrinted() {
        assertEquals("bond AA:BB:CC:DD:EE:FF pin:**** bredr", redactPin("bond AA:BB:CC:DD:EE:FF pin:0000 bredr"))
        assertEquals("pairwatch 60 pin:****", redactPin("pairwatch 60 pin:1234567890123456"))
        val text = pairingRequestText(2, hasKey = true, action = "setPairingConfirmation failed: SecurityException")
        assertEquals("pairing request: variant=2 (passkey confirmation) key=(redacted); " +
            "setPairingConfirmation failed: SecurityException", text)
        assertFalse(pairingRequestText(0, hasKey = false, action = "setPin=true").contains("key"))
    }
}
