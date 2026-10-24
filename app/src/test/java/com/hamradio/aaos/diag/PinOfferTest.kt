package com.hamradio.aaos.diag

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unprompted PIN offers and request answers must stay inside their own pairing attempt (codex #955/#956). */
@OptIn(ExperimentalCoroutinesApi::class)
class PinOfferTest {
    private class Probe(scope: TestScope, var bonding: Boolean = true) {
        val gate = AttemptGate()
        var offers = 0
        var beforeCheck: () -> Unit = {}
        val reports = mutableListOf<String>()
        val offer = PinOffer(
            scope.backgroundScope, gate,
            stillBonding = { beforeCheck(); bonding },
            offer = { offers++; "setPin=true" },
            report = { reports += it },
        )
    }

    // backgroundScope work is skipped by advanceUntilIdle, so time is advanced explicitly
    private fun TestScope.runPast6s() { advanceTimeBy(10_000); runCurrent() }

    @Test fun offersAtEachStepWhileBonding() = runTest {
        val p = Probe(this)
        p.offer.onBonding()
        advanceTimeBy(999); runCurrent()
        assertEquals(0, p.offers)
        advanceTimeBy(1); runCurrent()
        assertEquals(1, p.offers)
        runPast6s()
        assertEquals(3, p.offers)
        assertTrue(p.reports.last().startsWith("unprompted PIN offer 3 at 6 s"))
    }

    @Test fun closeStopsLaterOffers() = runTest {
        val p = Probe(this)
        p.offer.onBonding()
        advanceTimeBy(1_500); runCurrent()
        p.offer.close()
        runPast6s()
        assertEquals(1, p.offers)
    }

    @Test fun noOfferOnceBondingIsOver() = runTest {
        val p = Probe(this)
        p.offer.onBonding()
        p.bonding = false
        runPast6s()
        assertEquals(0, p.offers)
    }

    @Test fun repeatedBondingEventDoesNotDoubleOffers() = runTest {
        val p = Probe(this)
        p.offer.onBonding()
        advanceTimeBy(500); runCurrent()
        p.offer.onBonding()
        runPast6s()
        assertEquals(3, p.offers)
    }

    @Test fun bondingAfterCloseStartsNothing() = runTest {
        val p = Probe(this)
        p.offer.close()
        p.offer.onBonding()
        runPast6s()
        assertEquals(0, p.offers)
    }

    @Test fun bondingAfterTheBatchFinishedStartsNoNewBatch() = runTest {
        val p = Probe(this)
        p.offer.onBonding()
        runPast6s()
        p.offer.onBonding()
        runPast6s()
        assertEquals(3, p.offers)
    }

    @Test fun closeBetweenReadinessAndDispatchStopsTheCall() = runTest {
        val p = Probe(this)
        p.beforeCheck = { p.gate.close() }     // the attempt ends while the offer is being checked
        p.offer.onBonding()
        runPast6s()
        assertEquals(0, p.offers)
    }

    @Test fun closedGateRefusesRequestAnswers() {
        val gate = AttemptGate()
        assertEquals("ok", gate.runIfOpen { "ok" })
        gate.close()
        var called = false
        assertNull(gate.runIfOpen { called = true })
        assertFalse(called)
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
