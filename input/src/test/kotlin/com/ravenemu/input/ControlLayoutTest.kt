package com.ravenemu.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun `portrait GB respecte la disposition Raven sans gachettes`() {
        val layout = ControlLayout.ravenGbPortrait()
        val geometry = RavenSkinGeometries.gb
        val menu = layout.element(ControlId.MENU)!!
        val dpad = layout.element(ControlId.DPAD)!!

        assertEquals(0.5f, menu.centerX)
        assertEquals(geometry.controls.getValue(ControlId.MENU).centerY, menu.centerY)
        assertTrue(menu.centerY > geometry.screenRect.bottom)
        assertTrue(menu.centerY < dpad.centerY, "MENU doit être sous l'écran, avant le D-pad")
        assertFalse(layout.element(ControlId.BUTTON_L)!!.visible)
        assertFalse(layout.element(ControlId.BUTTON_R)!!.visible)
        assertEquals(
            setOf(
                ControlId.DPAD,
                ControlId.BUTTON_A,
                ControlId.BUTTON_B,
                ControlId.START,
                ControlId.SELECT,
                ControlId.MENU,
            ),
            layout.elements.filter(ControlElement::visible).map(ControlElement::id).toSet(),
        )
    }

    @Test
    fun `portrait GBA affiche L MENU R sous ecran`() {
        val layout = ControlLayout.ravenGbaPortrait()
        val geometry = RavenSkinGeometries.gba
        val left = layout.element(ControlId.BUTTON_L)!!
        val menu = layout.element(ControlId.MENU)!!
        val right = layout.element(ControlId.BUTTON_R)!!
        val dpad = layout.element(ControlId.DPAD)!!

        assertTrue(left.visible)
        assertTrue(right.visible)
        assertEquals(0.5f, menu.centerX)
        assertTrue(left.centerX < menu.centerX)
        assertTrue(menu.centerX < right.centerX)
        assertEquals(menu.centerY, left.centerY)
        assertEquals(menu.centerY, right.centerY)
        assertTrue(left.centerY > geometry.screenRect.bottom)
        assertTrue(menu.centerY < dpad.centerY)
    }

    @Test
    fun `rectangles de controle pilotent centres graphiques et tactiles`() {
        for ((skin, geometry) in listOf(
            TouchSkin.RAVEN_GB to RavenSkinGeometries.gb,
            TouchSkin.RAVEN_GBA to RavenSkinGeometries.gba,
        )) {
            val layout = if (skin == TouchSkin.RAVEN_GB) {
                ControlLayout.ravenGbPortrait()
            } else {
                ControlLayout.ravenGbaPortrait()
            }
            for (id in ControlId.entries) {
                val element = layout.element(id)!!
                val rect = geometry.controls.getValue(id)
                assertEquals(rect.centerX, element.centerX, "$skin / $id x")
                assertEquals(rect.centerY, element.centerY, "$skin / $id y")
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
    fun `profil personnalise conserve positions apparence et visibilite`() {
        val custom = ControlLayout.ravenGbaPortrait()
            .with(
                ControlElement(
                    id = ControlId.BUTTON_A,
                    centerX = 0.43f,
                    centerY = 0.57f,
                    scale = 1.42f,
                    opacity = 0.61f,
                    visible = true,
                    touchScale = 1.37f,
                )
            )
            .with(customShoulder())
            .copy(hapticFeedback = false, locked = true)

        assertEquals(custom, ControlLayout.fromJson(custom.toJson()))
    }

    @Test
    fun `json corrompu renvoie null`() {
        assertNull(ControlLayout.fromJson("{invalide"))
    }

    private fun customShoulder(): ControlElement = ControlElement(
        id = ControlId.BUTTON_L,
        centerX = 0.12f,
        centerY = 0.33f,
        scale = 0.82f,
        opacity = 0.77f,
        visible = false,
        touchScale = 1.6f,
    )
}
