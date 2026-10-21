package com.hamradio.aaos.vm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PttLatchTest {

    private var downs = 0
    private var ups = 0
    private val latch = PttLatch(sendDown = { downs++ }, sendUp = { ups++ })

    @Test
    fun `one press sends one down and one up`() {
        latch.down(); latch.up()
        assertEquals(1, downs); assertEquals(1, ups)
        assertFalse(latch.keyed)
    }

    @Test
    fun `every release is forwarded`() {
        // gesture-cancel release, then screen-dispose release: the controller ignores the extra one
        latch.down(); latch.up(); latch.up()
        assertEquals(2, ups)
    }

    @Test
    fun `release is forwarded even after transmit ended on its own`() {
        latch.down()
        latch.onRadioTx(true)
        assertTrue(latch.onRadioTx(false))   // e.g. a status flicker or the radio's own timeout
        latch.up()
        assertEquals(1, ups)                 // never swallowed: the mic may still be streaming
    }

    @Test
    fun `release without a press is still forwarded`() {
        latch.up()
        assertEquals(1, ups)
    }

    @Test
    fun `transmit ending on its own clears the toggle intent without sending`() {
        latch.down()
        assertFalse(latch.onRadioTx(true))
        assertTrue(latch.onRadioTx(false))
        assertFalse(latch.keyed)
        assertEquals(0, ups)
    }

    @Test
    fun `intent stays keyed while capture is still starting`() {
        latch.down()
        assertFalse(latch.onRadioTx(false))
        assertTrue(latch.keyed)
    }

    @Test
    fun `transmit reports while not keyed are ignored`() {
        assertFalse(latch.onRadioTx(true))
        assertFalse(latch.onRadioTx(false))
        assertFalse(latch.keyed)
    }
}
