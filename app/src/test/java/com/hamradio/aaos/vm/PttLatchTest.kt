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
    fun `duplicate down is dropped`() {
        assertTrue(latch.down())
        assertFalse(latch.down())
        assertEquals(1, downs)
        assertTrue(latch.keyed)
    }

    @Test
    fun `up without down sends nothing`() {
        // e.g. the screen-dispose safety release when PTT was never pressed
        assertFalse(latch.up())
        assertEquals(0, ups)
    }

    @Test
    fun `release after cancel then dispose sends a single up`() {
        latch.down()
        latch.up()   // finally block on gesture cancel
        latch.up()   // DisposableEffect on leaving the screen
        assertEquals(1, ups)
    }

    @Test
    fun `radio ending TX on its own clears the latch without sending`() {
        latch.down()
        assertFalse(latch.onRadioTx(true))
        assertTrue(latch.onRadioTx(false))
        assertFalse(latch.keyed)
        assertEquals(0, ups)
        // the next press keys again instead of sending a stray up
        latch.down()
        assertEquals(2, downs)
    }

    @Test
    fun `no TX report yet keeps the latch keyed`() {
        latch.down()
        assertFalse(latch.onRadioTx(false))   // radio hasn't caught up
        assertTrue(latch.keyed)
    }

    @Test
    fun `TX reports while not keyed are ignored`() {
        assertFalse(latch.onRadioTx(true))
        assertFalse(latch.onRadioTx(false))
        assertFalse(latch.keyed)
    }
}
