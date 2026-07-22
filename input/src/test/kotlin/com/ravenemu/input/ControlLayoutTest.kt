package com.ravenemu.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ControlLayoutTest {

    @Test
    fun `dispositions par defaut completes`() {
        for (layout in listOf(ControlLayout.defaultPortrait(), ControlLayout.defaultLandscape())) {
            for (id in ControlId.entries) {
                assertNotNull(layout.element(id), "élément $id manquant")
            }
        }
    }

    @Test
    fun `coordonnees relatives bornees`() {
        val layout = ControlLayout.defaultPortrait()
        for (element in layout.elements) {
            assertTrue(element.centerX in 0f..1f)
            assertTrue(element.centerY in 0f..1f)
        }
    }

    @Test
    fun `clamped ramene les valeurs dans les bornes`() {
        val element = ControlElement(
            ControlId.BUTTON_A,
            centerX = 1.5f,
            centerY = -0.2f,
            scale = 9f,
            opacity = 0f,
        ).clamped()
        assertEquals(1f, element.centerX)
        assertEquals(0f, element.centerY)
        assertEquals(2.5f, element.scale)
        assertEquals(0.1f, element.opacity)
    }

    @Test
    fun `with remplace un element par id`() {
        val layout = ControlLayout.defaultPortrait()
        val moved = layout.element(ControlId.BUTTON_A)!!.copy(centerX = 0.5f, centerY = 0.5f)
        val updated = layout.with(moved)
        assertEquals(0.5f, updated.element(ControlId.BUTTON_A)!!.centerX)
        assertEquals(layout.elements.size, updated.elements.size)
    }

    @Test
    fun `json aller-retour`() {
        val layout = ControlLayout.defaultLandscape().copy(hapticFeedback = false)
        val restored = ControlLayout.fromJson(layout.toJson())
        assertEquals(layout, restored)
    }

    @Test
    fun `json corrompu renvoie null`() {
        assertNull(ControlLayout.fromJson("{invalide"))
    }
}
