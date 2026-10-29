package com.ravenemu.app;

import org.junit.Test;
import static org.junit.Assert.*;

/** Geometry regressions from a real handset screenshot:
 * toolbar under status icons, credits under three-button navigation bar. */
public final class SystemBarInsetsTest {
    @Test public void reservesStatusBarAndThreeButtonNavigation() {
        int[] p = SystemBarInsets.contentPadding(
                new int[]{0, 0, 0, 0},
                new int[]{0, 74, 0, 92},
                new int[]{0, 0, 0, 0});
        assertArrayEquals(new int[]{0, 74, 0, 92}, p);
    }

    @Test public void usesLargestBottomInsetForKeyboard() {
        int[] p = SystemBarInsets.contentPadding(
                new int[]{5, 6, 7, 8},
                new int[]{0, 48, 0, 70},
                new int[]{0, 0, 0, 690});
        assertArrayEquals(new int[]{5, 54, 7, 698}, p);
    }

    @Test public void protectsLandscapeCutoutWithoutFakeSpacing() {
        assertArrayEquals(new int[]{83, 24, 12, 31},
                SystemBarInsets.contentPadding(
                        new int[]{0, 0, 0, 0},
                        new int[]{83, 24, 12, 31},
                        new int[]{0, 0, 0, 0}));
    }

    @Test public void ignoresNegativeInsetValues() {
        assertArrayEquals(new int[]{2, 3, 4, 5},
                SystemBarInsets.contentPadding(
                        new int[]{2, 3, 4, 5},
                        new int[]{-7, -6, -5, -4},
                        new int[]{0, 0, 0, -2}));
    }

    @Test public void purePaddingCalculationDoesNotAccumulate() {
        int[] base = {0, 0, 0, 0};
        int[] bars = {0, 63, 0, 77};
        int[] ime = {0, 0, 0, 0};
        assertArrayEquals(SystemBarInsets.contentPadding(base, bars, ime),
                SystemBarInsets.contentPadding(base, bars, ime));
        assertArrayEquals(new int[]{0, 0, 0, 0}, base);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMalformedInsets() {
        SystemBarInsets.contentPadding(new int[]{0, 0}, new int[4], new int[4]);
    }
}
