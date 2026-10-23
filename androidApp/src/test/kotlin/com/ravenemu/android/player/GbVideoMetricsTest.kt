package com.ravenemu.android.player

import com.ravenemu.shared.GbVideoMetricsAccumulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class GbVideoMetricsTest {
    @Test fun aggregatesAndResetsOncePerSecond() {
        val tracker = GbVideoMetricsAccumulator(59.7)
        assertNull(tracker.record(1_000_000_000L, 10_000_000L, 2_000_000L))
        assertNull(tracker.record(1_500_000_000L, 10_000_000L, 2_000_000L))
        val first = requireNotNull(tracker.record(2_000_000_000L, 10_000_000L, 2_000_000L))
        assertEquals(3.0, first.actualFps, 0.01)
        assertEquals(59.7, first.targetFps, 0.01)
        assertEquals(10.0, first.coreMs, 0.01)
        assertEquals(2.0, first.conversionMs, 0.01)
        assertNull(tracker.record(2_500_000_000L, 10_000_000L, 2_000_000L))
        val next = requireNotNull(tracker.record(3_000_000_000L, 20_000_000L, 4_000_000L))
        assertEquals(2.0, next.actualFps, 0.01)
        assertEquals(15.0, next.coreMs, 0.01)
        assertEquals(3.0, next.conversionMs, 0.01)
    }

    @Test fun rejectsInvalidTimings() {
        assertThrows(IllegalArgumentException::class.java) {
            GbVideoMetricsAccumulator(0.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            GbVideoMetricsAccumulator(60.0).record(0L, -1L, 0L)
        }
    }
}
