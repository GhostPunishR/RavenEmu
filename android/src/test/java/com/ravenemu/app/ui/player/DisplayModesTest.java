package com.ravenemu.app.ui.player;


import org.junit.Test;
import static org.junit.Assert.*;

/** The same rule as the native raven_frame_cadence, and the beta's choice of rate. */
public final class DisplayModesTest {
    private static final double GBA = 59.727500569606;

    @Test public void theGameLocksOnSixtyAndOneHundredTwentyHertzButNotNinety() {
        assertEquals(1, DisplayModes.vsyncsPerFrame(60.0, GBA));
        assertEquals(1, DisplayModes.vsyncsPerFrame(59.94, GBA));
        assertEquals(2, DisplayModes.vsyncsPerFrame(120.0, GBA));
        assertEquals(4, DisplayModes.vsyncsPerFrame(240.0, GBA));
        assertEquals(0, DisplayModes.vsyncsPerFrame(90.0, GBA));
        assertEquals(0, DisplayModes.vsyncsPerFrame(144.0, GBA));
        assertEquals(0, DisplayModes.vsyncsPerFrame(Double.NaN, GBA));
        assertEquals(0, DisplayModes.vsyncsPerFrame(60.0, 0.0));
    }

    @Test public void theCurrentRateStaysWhenItFitsOtherwiseTheHighestThatDoes() {
        float[] rates = {60f, 90f, 120f};
        assertEquals(60f, DisplayModes.preferredRefreshRate(rates, 60f, GBA), 0f);
        assertEquals(120f, DisplayModes.preferredRefreshRate(rates, 120f, GBA), 0f);
        assertEquals("Rather 120 than 60 Hz on a 90 Hz screen",
                120f, DisplayModes.preferredRefreshRate(rates, 90f, GBA), 0f);
        assertEquals(60f, DisplayModes.preferredRefreshRate(new float[]{60f, 90f}, 90f, GBA), 0f);
        assertEquals("None fits: the system keeps the choice",
                0f, DisplayModes.preferredRefreshRate(new float[]{90f, 144f}, 90f, GBA), 0f);
    }
}
