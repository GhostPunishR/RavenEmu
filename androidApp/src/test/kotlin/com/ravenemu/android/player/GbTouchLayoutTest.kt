package com.ravenemu.android.player

import com.ravenemu.shared.GbTouchLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GbTouchLayoutTest {
    @Test fun thumbClustersFitOnCommonPhoneWidths() {
        for (width in listOf(240f, 280f, 320f, 360f, 393f, 480f)) {
            val cluster = GbTouchLayout.clusterDp(width)
            assertTrue(cluster <= 132f)
            assertTrue(2f * cluster + 32f <= width)
        }
    }

    @Test fun smallAndLargeWidthsAreBounded() {
        assertEquals(104f, GbTouchLayout.clusterDp(240f), .01f)
        assertEquals(132f, GbTouchLayout.clusterDp(393f), .01f)
    }
}
