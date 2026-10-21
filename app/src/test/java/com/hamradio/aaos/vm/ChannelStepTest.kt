package com.hamradio.aaos.vm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChannelStepTest {

    @Test
    fun `steps up and down through sorted ids`() {
        val ids = listOf(7, 0, 3)
        assertEquals(3, ChannelStep.next(ids, 0, +1))
        assertEquals(7, ChannelStep.next(ids, 3, +1))
        assertEquals(0, ChannelStep.next(ids, 3, -1))
    }

    @Test
    fun `wraps at both ends`() {
        val ids = listOf(0, 3, 7)
        assertEquals(0, ChannelStep.next(ids, 7, +1))
        assertEquals(7, ChannelStep.next(ids, 0, -1))
    }

    @Test
    fun `current channel missing from the list steps from where it would sit`() {
        val ids = listOf(0, 3, 7)
        assertEquals(7, ChannelStep.next(ids, 5, +1))
        assertEquals(3, ChannelStep.next(ids, 5, -1))
        assertEquals(0, ChannelStep.next(ids, 9, +1))
    }

    @Test
    fun `nothing to step to`() {
        assertNull(ChannelStep.next(emptyList(), 0, +1))
        assertNull(ChannelStep.next(listOf(4), 4, +1))
        assertNull(ChannelStep.next(listOf(0, 3), 0, 0))
    }
}
