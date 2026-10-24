package com.ravenemu.android.player

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

    @Test fun followsTheGameWithoutDisplayBeat() {
        val pacer = GbFramePacer(period)
        pacer.reset(0L)
        pacer.shouldRender(0L)
        assertEquals(period - 1_000_000L, pacer.nextWaitNanos(1_000_000L, 0L, 0L))
        assertEquals(1.0, pacer.speed, 0.0)
        // A 90 Hz screen is not a multiple of the game: the console's own period stays.
        pacer.shouldRender(period)
        assertEquals(period - 1_000_000L, pacer.nextWaitNanos(period + 1_000_000L, period, 11_111_111L))
        assertEquals(period, pacer.framePeriodNanos)
    }

    @Test fun locksEachFrameToOneVsyncAtSixtyHertz() {
        val game = DisplayCadence.periodNanos(59.7275)
        val display = DisplayCadence.periodNanos(60.0)
        val pacer = GbFramePacer(game)
        pacer.reset(0L)
        var now = 0L
        val ready = mutableListOf<Long>()
        repeat(600) {
            pacer.shouldRender(now)
            // The frame takes 4 ms; the display beats on its own grid from 0.
            now += 4_000_000L
            ready += now
            val vsync = now / display * display
            now += pacer.nextWaitNanos(now, vsync, display)
        }
        assertEquals(display, pacer.framePeriodNanos)
        assertEquals(game.toDouble() / display, pacer.speed, 1e-9)
        // Once settled, each image is ready mid-way between two vsyncs: one per interval.
        for (time in ready.drop(16)) {
            val phase = Math.floorMod(time, display)
            assertTrue("phase $phase", phase in display / 4..display * 3 / 4)
        }
        val intervals = ready.drop(16).map { it / display }
        assertEquals(intervals.size, intervals.toSet().size)
        assertEquals(intervals.last() - intervals.first(), (intervals.size - 1).toLong())
    }

    @Test fun showsEachFrameForTwoVsyncsAtOneHundredTwentyHertz() {
        val game = DisplayCadence.periodNanos(59.7275)
        val display = DisplayCadence.periodNanos(120.0)
        val pacer = GbFramePacer(game)
        pacer.reset(0L)
        var now = 3_000_000L
        val ready = mutableListOf<Long>()
        repeat(240) {
            pacer.shouldRender(now)
            now += 2_000_000L
            ready += now
            now += pacer.nextWaitNanos(now, now / display * display, display)
        }
        assertEquals(2 * display, pacer.framePeriodNanos)
        val intervals = ready.drop(16).map { it / display }
        for (index in 1 until intervals.size) assertEquals(2L, intervals[index] - intervals[index - 1])
    }

    @Test fun catchesUpWhenLateEvenWhenLocked() {
        val display = DisplayCadence.periodNanos(60.0)
        val pacer = GbFramePacer(DisplayCadence.periodNanos(59.7275))
        pacer.reset(0L)
        pacer.shouldRender(0L)
        // A 50 ms hiccup: no wait, the frames run back to back until on the grid again.
        assertEquals(0L, pacer.nextWaitNanos(50_000_000L, 50_000_000L / display * display, display))
    }

    @Test fun rejectsInvalidSettings() {
        assertThrows(IllegalArgumentException::class.java) { GbFramePacer(0L) }
        assertThrows(IllegalArgumentException::class.java) { GbFramePacer(period, maxLagNanos = 1L) }
        assertThrows(IllegalArgumentException::class.java) {
            GbFramePacer(period, maxConsecutiveSkips = -1)
        }
    }
}
