package com.ravenemu.input

import com.ravenemu.emulation.api.ConsoleType
import com.ravenemu.emulation.api.EmulatorButton
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

    /**
     * Les touches visibles sont celles de la console, nommées une à une.
     *
     * Les attendus ne se lisent pas dans `ConsoleType` : une vérification qui
     * se compare à la déclaration qu’elle vérifie accepterait n’importe quelle
     * déclaration. La Game Boy n’a pas de gâchettes, la Game Boy Advance en a
     * deux et pas de X ni Y, la Nintendo DS a les quatre.
     */
    @Test
    fun `chaque console montre ses propres touches`() {
        val attendus = mapOf(
            ConsoleType.GAME_BOY to emptySet<ControlId>(),
            ConsoleType.GAME_BOY_ADVANCE to setOf(ControlId.BUTTON_L, ControlId.BUTTON_R),
            ConsoleType.NINTENDO_DS to setOf(
                ControlId.BUTTON_L,
                ControlId.BUTTON_R,
                ControlId.BUTTON_X,
                ControlId.BUTTON_Y,
            ),
        )
        val optionnelles = setOf(
            ControlId.BUTTON_L,
            ControlId.BUTTON_R,
            ControlId.BUTTON_X,
            ControlId.BUTTON_Y,
        )
        for (console in ConsoleType.entries) {
            val attendu = assertNotNull(attendus[console], "console $console sans attendu")
            val dispositions = listOf(
                ControlLayout.defaultPortrait(console.buttons),
                ControlLayout.defaultLandscape(console.buttons),
            )
            for (disposition in dispositions) {
                val visibles = disposition.elements
                    .filter { it.visible && it.id in optionnelles }
                    .map { it.id }
                    .toSet()
                assertEquals(attendu, visibles, "touches visibles pour $console")
            }
        }
    }

    /**
     * Les huit touches communes restent visibles sur toutes les consoles : une
     * console qui en aurait perdu une serait injouable.
     */
    @Test
    fun `les touches communes ne dependent pas de la console`() {
        val communes = setOf(
            ControlId.DPAD,
            ControlId.BUTTON_A,
            ControlId.BUTTON_B,
            ControlId.START,
            ControlId.SELECT,
        )
        for (console in ConsoleType.entries) {
            val disposition = ControlLayout.defaultPortrait(console.buttons)
            for (id in communes) {
                assertTrue(disposition.element(id)!!.visible, "$id cachée sur $console")
            }
        }
    }

    /**
     * Une Nintendo DS possède bien les six touches que la Game Boy n’a pas :
     * c’est ce que la disposition ci-dessus lit, et il vaut mieux le savoir ici
     * que de voir une console sans gâchettes à l’écran.
     */
    @Test
    fun `la Nintendo DS declare ses six touches en propre`() {
        val touches = ConsoleType.NINTENDO_DS.buttons
        for (touche in listOf(EmulatorButton.L, EmulatorButton.R, EmulatorButton.X, EmulatorButton.Y)) {
            assertTrue(touche in touches, "$touche manquante sur la Nintendo DS")
        }
        assertEquals(12, touches.size)
        assertTrue(EmulatorButton.X !in ConsoleType.GAME_BOY_ADVANCE.buttons)
        assertTrue(EmulatorButton.L !in ConsoleType.GAME_BOY.buttons)
    }

    /**
     * Chaque console range ses dispositions sous un mot qui lui est propre :
     * deux consoles partageant le même relèveraient la disposition de l’autre,
     * et une console à douze touches en hériterait une à huit.
     */
    @Test
    fun `chaque console a son propre suffixe de profil`() {
        assertEquals("gb", ConsoleType.GAME_BOY.layoutKey)
        assertEquals("gba", ConsoleType.GAME_BOY_ADVANCE.layoutKey)
        assertEquals("nds", ConsoleType.NINTENDO_DS.layoutKey)
        assertEquals(ConsoleType.entries.size, ConsoleType.entries.map { it.layoutKey }.toSet().size)
    }
}
