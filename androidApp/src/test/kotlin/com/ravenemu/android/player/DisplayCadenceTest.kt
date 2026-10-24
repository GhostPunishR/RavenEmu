package com.ravenemu.android.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DisplayCadenceTest {
    private val game = DisplayCadence.periodNanos(59.7275)

    @Test fun locksOnSixtyAndOneHundredTwentyHertz() {
        assertEquals(1, DisplayCadence.vsyncsPerFrame(game, DisplayCadence.periodNanos(60.0)))
        assertEquals(2, DisplayCadence.vsyncsPerFrame(game, DisplayCadence.periodNanos(120.0)))
        assertEquals(4, DisplayCadence.vsyncsPerFrame(game, DisplayCadence.periodNanos(240.0)))
        // Within 5 %: a 57 Hz or 62 Hz screen still carries the game.
        assertEquals(1, DisplayCadence.vsyncsPerFrame(game, DisplayCadence.periodNanos(57.0)))
        assertEquals(1, DisplayCadence.vsyncsPerFrame(game, DisplayCadence.periodNanos(62.0)))
    }

    @Test fun refusesScreensTheGameDoesNotDivide() {
        assertEquals(0, DisplayCadence.vsyncsPerFrame(game, DisplayCadence.periodNanos(90.0)))
        assertEquals(0, DisplayCadence.vsyncsPerFrame(game, DisplayCadence.periodNanos(144.0)))
        assertEquals(0, DisplayCadence.vsyncsPerFrame(game, DisplayCadence.periodNanos(50.0)))
        assertEquals(0, DisplayCadence.vsyncsPerFrame(game, DisplayCadence.periodNanos(30.0)))
        assertEquals(0, DisplayCadence.vsyncsPerFrame(game, 0L))
        assertEquals(0, DisplayCadence.vsyncsPerFrame(0L, DisplayCadence.periodNanos(60.0)))
        assertEquals(0L, DisplayCadence.periodNanos(0.0))
        assertEquals(0L, DisplayCadence.periodNanos(Double.NaN))
    }

    @Test fun keepsAFittingCurrentRateAndOtherwiseTakesTheHighestThatFits() {
        val phone = listOf(60f, 90f, 120f)
        assertEquals(60f, DisplayCadence.preferredRefreshRate(phone, 60f, 59.7275))
        assertEquals(120f, DisplayCadence.preferredRefreshRate(phone, 120f, 59.7275))
        assertEquals(120f, DisplayCadence.preferredRefreshRate(phone, 90f, 59.7275))
        assertEquals(120f, DisplayCadence.preferredRefreshRate(listOf(60f, 120f, 144f), 144f, 59.7275))
        assertEquals(60f, DisplayCadence.preferredRefreshRate(listOf(60f, 90f), 90f, 59.7275))
        assertNull(DisplayCadence.preferredRefreshRate(listOf(90f), 90f, 59.7275))
        assertNull(DisplayCadence.preferredRefreshRate(emptyList(), 90f, 59.7275))
    }
}
