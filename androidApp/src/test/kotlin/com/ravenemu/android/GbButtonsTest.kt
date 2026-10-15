package com.ravenemu.android

import com.ravenemu.shared.GbButton
import com.ravenemu.shared.GbDpadHitTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GbButtonsTest {
    @Test fun matchesNativeBridgeButtonOrder() {
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9),
            GbButton.entries.map { it.nativeCode })
    }

    @Test fun dpadSupportsCardinalsAndDiagonals() {
        val w = 148f
        assertEquals(emptySet<GbButton>(), GbDpadHitTest.buttonsAt(74f, 74f, w, w))
        assertEquals(setOf(GbButton.UP), GbDpadHitTest.buttonsAt(74f, 8f, w, w))
        assertEquals(setOf(GbButton.DOWN), GbDpadHitTest.buttonsAt(74f, 140f, w, w))
        assertEquals(setOf(GbButton.LEFT), GbDpadHitTest.buttonsAt(8f, 74f, w, w))
        assertEquals(setOf(GbButton.RIGHT), GbDpadHitTest.buttonsAt(140f, 74f, w, w))
        assertEquals(
            setOf(GbButton.UP, GbButton.RIGHT),
            GbDpadHitTest.buttonsAt(120f, 18f, w, w),
        )
        assertTrue(GbDpadHitTest.buttonsAt(-50f, -50f, w, w).isEmpty())
    }

    @Test fun rejectsInvalidPadDimensions() {
        assertTrue(GbDpadHitTest.buttonsAt(0f, 0f, 0f, 100f).isEmpty())
    }
}
