package com.ravenemu.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GbFramePacerTest {
    private val period = 16_000_000L

    @Test fun waitsTheRestOfThePeriodWhenOnTime() {
        val pacer = GbFramePacer(period)
        pacer.reset(0L)
        assertTrue(pacer.shouldRender(0L))
        assertEquals(period - 5_000_000L, pacer.nextWaitNanos(5_000_000L))
        assertTrue(pacer.shouldRender(period))
        assertEquals(period - 1_000_000L, pacer.nextWaitNanos(period + 1_000_000L))
    }

    @Test fun catchesUpALateFrameInsteadOfDroppingIt() {
        val pacer = GbFramePacer(period)
        pacer.reset(0L)
        // A 40 ms hiccup: the next frames start immediately, no time is lost.
        assertEquals(0L, pacer.nextWaitNanos(40_000_000L))
        assertEquals(0L, pacer.nextWaitNanos(41_000_000L))
        // Third deadline (48 ms) is ahead again: the session is back on time.
        assertEquals(6_000_000L, pacer.nextWaitNanos(42_000_000L))
    }

    @Test fun abandonsOnlyALagLongerThanTheLimit() {
        val pacer = GbFramePacer(period, maxLagNanos = 100_000_000L)
        pacer.reset(0L)
        assertEquals(0L, pacer.nextWaitNanos(500_000_000L))
        // Restarted from 500 ms: the following frame waits a full period.
        assertEquals(period - 1_000_000L, pacer.nextWaitNanos(501_000_000L))
    }

    @Test fun skipsAtMostThreeImagesInARowWhileLate() {
        val pacer = GbFramePacer(period)
        pacer.reset(0L)
        val late = 10 * period
        assertFalse(pacer.shouldRender(late))
        assertFalse(pacer.shouldRender(late))
        assertFalse(pacer.shouldRender(late))
        assertTrue(pacer.shouldRender(late))
        assertFalse(pacer.shouldRender(late))
    }

    @Test fun rendersEveryImageWithinOnePeriodOfLateness() {
        val pacer = GbFramePacer(period)
        pacer.reset(0L)
        assertTrue(pacer.shouldRender(period))
        assertTrue(pacer.shouldRender(period - 1))
    }

    @Test fun startsFromTheFirstCallWithoutExplicitReset() {
        val pacer = GbFramePacer(period)
        assertTrue(pacer.shouldRender(1_000_000_000L))
        assertEquals(period, pacer.nextWaitNanos(1_000_000_000L))
    }

    @Test fun rejectsInvalidSettings() {
        assertThrows(IllegalArgumentException::class.java) { GbFramePacer(0L) }
        assertThrows(IllegalArgumentException::class.java) { GbFramePacer(period, maxLagNanos = 1L) }
        assertThrows(IllegalArgumentException::class.java) {
            GbFramePacer(period, maxConsecutiveSkips = -1)
        }
    }
}
