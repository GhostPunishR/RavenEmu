package com.ravenemu.input

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SkinGeometryTest {

    @Test
    fun `screenRect normalise respecte le ratio natif`() {
        for (geometry in listOf(RavenSkinGeometries.gb, RavenSkinGeometries.gba)) {
            val rect = geometry.screenRect
            val physicalRatio = rect.width * geometry.designAspectRatio / rect.height
            assertTrue(
                abs(physicalRatio - geometry.nativeScreenAspectRatio) < 0.001f,
                "ratio $physicalRatio au lieu de ${geometry.nativeScreenAspectRatio}",
            )
        }
    }

    @Test
    fun `surface reste centree et native sur plusieurs portraits`() {
        val devices = listOf(
            Triple(1080, 1920, 0),
            Triple(1080, 2400, 96),
            Triple(1600, 2560, 64),
            Triple(800, 1280, 48),
        )
        for (geometry in listOf(RavenSkinGeometries.gb, RavenSkinGeometries.gba)) {
            for ((width, height, inset) in devices) {
                val result = SkinLayoutCalculator.calculate(width, height, inset, geometry)
                val surface = result.surfaceRect
                val ratio = surface.width.toFloat() / surface.height
                assertTrue(abs(ratio - geometry.nativeScreenAspectRatio) < 0.005f)
                assertTrue(surface.left >= result.skinRect.left)
                assertTrue(surface.top >= result.skinRect.top)
                assertTrue(surface.right <= result.skinRect.right)
                assertTrue(surface.bottom <= result.skinRect.bottom)
                assertTrue(result.skinRect.top >= inset)
            }
        }
    }

    @Test
    fun `coque conserve son ratio sans deformation`() {
        val geometry = RavenSkinGeometries.gb
        val result = SkinLayoutCalculator.calculate(
            containerWidth = 1080,
            containerHeight = 2520,
            topInset = 84,
            geometry = geometry,
        )
        val ratio = result.skinRect.width.toFloat() / result.skinRect.height
        assertTrue(abs(ratio - geometry.designAspectRatio) < 0.001f)
    }

    @Test
    fun `GBA place L MENU R entre ecran et commandes principales`() {
        val geometry = RavenSkinGeometries.gba
        val left = geometry.controls.getValue(ControlId.BUTTON_L)
        val menu = geometry.controls.getValue(ControlId.MENU)
        val right = geometry.controls.getValue(ControlId.BUTTON_R)
        val dpad = geometry.controls.getValue(ControlId.DPAD)

        assertTrue(left.top > geometry.screenRect.bottom)
        assertEquals(left.centerY, right.centerY)
        assertEquals(left.centerY, menu.centerY)
        assertTrue(menu.centerY < dpad.centerY)
    }
}
