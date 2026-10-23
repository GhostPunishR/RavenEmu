package com.ravenemu.android.player

import com.ravenemu.shared.GbVideoFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GbVideoFrameTest {
    @Test fun validatesOneNativeGameBoyFrame() {
        val pixels = IntArray(160 * 144) { 0xFFE0F8D0.toInt() }
        assertEquals(pixels.size, GbVideoFrame(160, 144, pixels).argb.size)
        assertThrows(IllegalArgumentException::class.java) {
            GbVideoFrame(160, 144, IntArray(5))
        }
        assertThrows(IllegalArgumentException::class.java) {
            GbVideoFrame(240, 160, IntArray(240 * 160))
        }
    }
}
