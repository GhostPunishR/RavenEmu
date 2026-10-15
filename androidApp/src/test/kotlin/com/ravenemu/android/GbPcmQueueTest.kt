package com.ravenemu.android

import com.ravenemu.shared.GbPcmQueue
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GbPcmQueueTest {
    @Test fun preservesStereoSamplesAcrossPartialDeviceWrites() {
        val q = GbPcmQueue(8)
        assertEquals(0, q.append(shortArrayOf(1, 2, 3, 4, 5, 6), 6))
        val head = ShortArray(4)
        assertEquals(4, q.copyHead(head))
        assertArrayEquals(shortArrayOf(1, 2, 3, 4), head)
        q.discard(4)
        assertEquals(0, q.append(shortArrayOf(7, 8, 9, 10), 4))
        val next = ShortArray(8)
        assertEquals(6, q.copyHead(next))
        assertArrayEquals(shortArrayOf(5, 6, 7, 8, 9, 10), next.copyOf(6))
    }

    @Test fun dropsOldestSamplesWhenDeviceFallsBehind() {
        val q = GbPcmQueue(6)
        q.append(shortArrayOf(1, 2, 3, 4, 5, 6), 6)
        assertEquals(4, q.append(shortArrayOf(7, 8, 9, 10), 4))
        val out = ShortArray(6)
        assertEquals(6, q.copyHead(out))
        assertArrayEquals(shortArrayOf(5, 6, 7, 8, 9, 10), out)
    }

    @Test fun validatesStereoAlignmentAndClearsOnStop() {
        val q = GbPcmQueue(4)
        assertThrows(IllegalArgumentException::class.java) {
            q.append(shortArrayOf(1, 2, 3), 3)
        }
        q.append(shortArrayOf(1, 2), 2)
        q.clear()
        assertEquals(0, q.size)
    }
}
