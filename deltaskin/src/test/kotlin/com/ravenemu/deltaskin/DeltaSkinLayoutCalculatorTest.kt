package com.ravenemu.deltaskin

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeltaSkinLayoutCalculatorTest {
    @Test
    fun `telephones et insets gardent panneau et jeu separes`() {
        val devices = listOf(
            Triple(360.0, 640.0, DeltaSkinInsets()),
            Triple(393.0, 852.0, DeltaSkinInsets(top = 47.0, bottom = 21.0)),
            Triple(412.0, 915.0, DeltaSkinInsets(left = 4.0, top = 32.0, right = 4.0)),
        )
        for ((width, height, insets) in devices) {
            val layout = calculate(width, height, insets, DeltaSkinSize(320.0, 274.0))
            assertEquals(layout.gameArea.bottom, layout.panel.top, 0.0001)
            assertTrue(layout.gameScreen.bottom <= layout.gameArea.bottom)
            assertTrue(layout.gameScreen.top >= layout.gameArea.top)
            assertTrue(layout.panel.bottom <= height - insets.bottom + 0.0001)
        }
    }

    @Test
    fun `panneaux 320 par 240 et 320 par 274 ne sont pas etires`() {
        for (mapping in listOf(DeltaSkinSize(320.0, 240.0), DeltaSkinSize(320.0, 274.0))) {
            val layout = calculate(393.0, 852.0, DeltaSkinInsets(), mapping)
            assertEquals(mapping.height / mapping.width, layout.panel.height / layout.panel.width, 1e-9)
            val scaleX = layout.panel.width / mapping.width
            val scaleY = layout.panel.height / mapping.height
            assertEquals(scaleX, scaleY, 1e-9)
        }
    }

    @Test
    fun `ratios natifs GB et GBA sont preserves et centres`() {
        for (native in listOf(DeltaSkinSize(160.0, 144.0), DeltaSkinSize(240.0, 160.0))) {
            val layout = DeltaSkinLayoutCalculator.calculate(
                393.0,
                852.0,
                DeltaSkinInsets(top = 30.0),
                DeltaSkinSize(320.0, 240.0),
                native,
            )!!
            assertTrue(abs(layout.gameScreen.width / layout.gameScreen.height - native.width / native.height) < 1e-9)
            assertEquals(
                layout.gameArea.left + layout.gameArea.width / 2.0,
                layout.gameScreen.left + layout.gameScreen.width / 2.0,
                1e-9,
            )
            assertEquals(
                layout.gameArea.top + layout.gameArea.height / 2.0,
                layout.gameScreen.top + layout.gameScreen.height / 2.0,
                1e-9,
            )
        }
    }

    @Test
    fun `hitbox suit exactement la transformation du PDF`() {
        val mapping = DeltaSkinSize(320.0, 240.0)
        val layout = calculate(360.0, 640.0, DeltaSkinInsets(), mapping)
        val mapped = DeltaSkinLayoutCalculator.mapFrame(
            DeltaSkinFrame(20.0, 60.0, 80.0, 40.0),
            layout.panel,
            mapping,
        )
        assertEquals(layout.panel.left + 22.5, mapped.left, 1e-9)
        assertEquals(layout.panel.top + 67.5, mapped.top, 1e-9)
        assertEquals(90.0, mapped.width, 1e-9)
        assertEquals(45.0, mapped.height, 1e-9)
    }

    private fun calculate(
        width: Double,
        height: Double,
        insets: DeltaSkinInsets,
        mapping: DeltaSkinSize,
    ): DeltaSkinLayout = DeltaSkinLayoutCalculator.calculate(
        width,
        height,
        insets,
        mapping,
        DeltaSkinSize(160.0, 144.0),
    )!!
}
