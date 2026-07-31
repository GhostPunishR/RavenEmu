package com.ravenemu.emulation.api.audio

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AudioRateControllerTest {

    @Test
    fun `la cadence native conserve le debit`() {
        val controller = AudioRateController(32768, smoothing = 1.0)
        val nativeDuration = (549.0 * 1_000_000_000.0 / 32768).toLong()

        assertEquals(1.0, controller.update(549, nativeDuration))
    }

    @Test
    fun `une trame lente produit une correction proportionnelle`() {
        val controller = AudioRateController(32768, smoothing = 1.0)
        val target = 19_330_000L
        val expected = (549.0 * 1_000_000_000.0 / 32768) / target

        assertTrue(abs(controller.update(549, target) - expected) < 0.0001)
    }

    @Test
    fun `la correction est lissee entre les trames`() {
        val controller = AudioRateController(32768)
        val first = controller.update(549, 19_330_000L)
        val second = controller.update(549, 19_330_000L)

        assertTrue(first in 0.94..0.96, "première échelle : $first")
        assertTrue(second < first, "seconde échelle : $second")
        assertTrue(second > 0.86, "correction trop brutale : $second")
    }

    @Test
    fun `la correction reste bornee et se reinitialise`() {
        val controller = AudioRateController(32768, smoothing = 1.0)

        assertEquals(0.80, controller.update(549, 100_000_000L))
        controller.reset()
        assertEquals(1.0, controller.scale)
    }
}
