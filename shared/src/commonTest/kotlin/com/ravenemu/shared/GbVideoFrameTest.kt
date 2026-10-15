package com.ravenemu.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GbVideoFrameTest {
    @Test fun acceptsNativeDmgFramebuffer() {
        val pixels = IntArray(160 * 144) { 0xFFE0F8D0.toInt() }
        val frame = GbVideoFrame(160, 144, pixels)
        assertEquals(160 * 144, frame.argb.size)
        assertEquals(pixels[0], frame.argb[0])
    }

    @Test fun refusesWrongDimensionsOrPixelCount() {
        assertFailsWith<IllegalArgumentException> {
            GbVideoFrame(240, 160, IntArray(240 * 160))
        }
        assertFailsWith<IllegalArgumentException> {
            GbVideoFrame(160, 144, IntArray(160 * 144 - 1))
        }
    }
}
