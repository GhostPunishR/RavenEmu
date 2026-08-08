package com.ravenemu.core.gba.diag

import com.ravenemu.core.gba.GbaCore
import com.ravenemu.core.gba.KotlinGbaCore
import com.ravenemu.core.gba.SyntheticRom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Durée de vie de la mesure au fil des reconstructions de la machine.
 *
 * Un power-cycle, un chargement d'état ou un changement de ROM reconstruisent la
 * `GbaMachine`. La mesure, elle, relève de l'intention de celui qui enquête :
 * elle ne doit pas s'éteindre d'elle-même au milieu d'une enquête, en laissant
 * croire que le moteur n'a rien à dire.
 */
class MeasurementLifetimeTest {

    private fun coeur() = KotlinGbaCore().apply { loadRom(SyntheticRom.build()) }

    @Test
    fun `la mesure survit a une remise a zero`() {
        val core = coeur()
        core.measuringTime = true

        core.reset()

        assertTrue(core.measuringTime, "La mesure demandée doit survivre au power-cycle")
        val snapshot = assertNotNull(core.debugSnapshot())
        assertTrue(snapshot.lumaMeasured, "Le relevé doit se déclarer actif après remise à zéro")
    }

    @Test
    fun `la mesure survit a un chargement d'etat`() {
        val core = coeur()
        core.measuringTime = true
        val etat = core.saveState()

        core.loadState(etat)

        assertTrue(core.measuringTime)
        assertTrue(assertNotNull(core.debugSnapshot()).lumaMeasured)
    }

    @Test
    fun `la mesure eteinte le reste apres une remise a zero`() {
        // La symétrie compte autant : réactiver la mesure toute seule ferait
        // payer un balayage d'image par trame à qui n'a rien demandé.
        val core = coeur()
        core.reset()

        assertFalse(core.measuringTime)
        assertFalse(assertNotNull(core.debugSnapshot()).lumaMeasured)
    }

    @Test
    fun `la mesure demandee avant le chargement d'une ROM s'applique quand meme`() {
        // L'écran d'émulation règle le diagnostic avant de charger la cartouche.
        val core = KotlinGbaCore()
        core.measuringTime = true
        core.loadRom(SyntheticRom.build())

        assertTrue(assertNotNull(core.debugSnapshot()).lumaMeasured)
    }

    @Test
    fun `une image noire mesuree reste visible dans le releve`() {
        // Le cas que le relevé masquait : trois zéros sont une lecture, pas une
        // absence de lecture. C'est même celle qu'on cherche à confirmer pendant
        // un fondu au noir.
        val core = coeur()
        core.measuringTime = true
        val framebuffer = IntArray(core.video.pixelCount)
        repeat(3) { core.runFrame(framebuffer) }

        val snapshot = assertNotNull(core.debugSnapshot())
        assertEquals(0, snapshot.lumaMax, "La ROM synthétique produit une image noire")
        val texte = snapshot.toDiagnosticText(fps = 60.0, frameTimeMs = 16.0)
        assertTrue("luma min 0" in texte, "Le relevé doit afficher la mesure nulle :\n$texte")
    }

    @Test
    fun `sans mesure la ligne de dynamique est absente`() {
        val core = coeur()
        val framebuffer = IntArray(core.video.pixelCount)
        repeat(3) { core.runFrame(framebuffer) }

        val texte = assertNotNull(core.debugSnapshot())
            .toDiagnosticText(fps = 60.0, frameTimeMs = 16.0)
        assertFalse("luma" in texte, "Sans mesure, aucune valeur à afficher :\n$texte")
    }

    @Test
    fun `l'adaptateur natif conserve l'intention avant d'ouvrir son handle`() {
        val core = GbaCore()

        core.measuringTime = true
        assertTrue(core.measuringTime)

        core.measuringTime = false
        assertFalse(core.measuringTime)
    }
}
