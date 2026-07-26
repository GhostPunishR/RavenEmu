package com.ravenemu.emulation.api.timing

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdaptiveFrameSkipperTest {

    @Test
    fun `une trame dans le budget conserve le rendu`() {
        val skipper = AdaptiveFrameSkipper(frameBudgetNanos = 16_742_000L)

        skipper.onFrameCompleted(rendered = true, workNanos = 15_000_000L)

        assertTrue(skipper.shouldRender())
    }

    @Test
    fun `une trame lente saute exactement la composition suivante`() {
        val skipper = AdaptiveFrameSkipper(frameBudgetNanos = 16_742_000L)

        skipper.onFrameCompleted(rendered = true, workNanos = 19_200_000L)
        assertFalse(skipper.shouldRender())

        skipper.onFrameCompleted(rendered = false, workNanos = 12_500_000L)
        assertTrue(skipper.shouldRender())
    }

    @Test
    fun `reset restaure toujours le rendu`() {
        val skipper = AdaptiveFrameSkipper(frameBudgetNanos = 10L)
        skipper.onFrameCompleted(rendered = true, workNanos = 11L)
        assertFalse(skipper.shouldRender())

        skipper.reset()

        assertTrue(skipper.shouldRender())
    }

    @Test
    fun `le budget doit etre positif`() {
        assertFailsWith<IllegalArgumentException> {
            AdaptiveFrameSkipper(frameBudgetNanos = 0L)
        }
    }
}
