package com.ravenemu.android

import org.junit.Assert.assertEquals
import org.junit.Test

class GbButtonLatchTest {
    private val a = 1
    private val b = 2

    @Test
    fun aHeldButtonLastsUntilReleased() {
        val latch = GbButtonLatch()
        latch.set(a, true)
        assertEquals(a, latch.takeFrame())
        assertEquals(a, latch.takeFrame())
        latch.set(a, false)
        assertEquals(0, latch.takeFrame())
    }

    @Test
    fun aPressShorterThanAFrameIsSeenForOneFrame() {
        val latch = GbButtonLatch()
        latch.set(a, true)
        latch.set(a, false)
        assertEquals(a, latch.takeFrame())
        assertEquals(0, latch.takeFrame())
    }

    @Test
    fun aStateSeesTheBriefPressWithoutConsumingIt() {
        val latch = GbButtonLatch()
        latch.set(b, true)
        latch.set(b, false)
        assertEquals(b, latch.current())
        assertEquals(b, latch.takeFrame())
        assertEquals(0, latch.current())
    }

    @Test
    fun releasingEverythingDropsPendingPresses() {
        val latch = GbButtonLatch()
        latch.set(a, true)
        latch.set(b, true)
        latch.set(b, false)
        latch.releaseAll()
        assertEquals(0, latch.takeFrame())
    }

    @Test
    fun buttonsStayIndependent() {
        val latch = GbButtonLatch()
        latch.set(a, true)
        latch.set(b, true)
        latch.set(b, false)
        assertEquals(a or b, latch.takeFrame())
        assertEquals(a, latch.takeFrame())
    }
}
