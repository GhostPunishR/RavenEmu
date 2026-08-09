package com.ravenemu.core.gba

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Réglage de présence de l'horloge de cartouche, éprouvé **sur le cœur livré**.
 *
 * Les deux fonctions natives que ce réglage traverse — `setGbaForcedRtc` et
 * `gbaRtcActive` — ne sont reliées à Kotlin que par leur nom : une signature
 * erronée ne se verrait qu'à l'exécution, sur l'appareil, sous la forme d'un
 * `UnsatisfiedLinkError`. Les exercer ici les fait tomber à la compilation de la
 * suite plutôt que chez le joueur.
 *
 * La détection cherche la bibliothèque Seiko des cartouches d'origine. Elle ne
 * peut rien affirmer d'une ROM modifiée : certaines pilotent l'horloge sans
 * porter cette signature, et annoncent alors que l'heure est illisible. Le
 * réglage doit donc primer dans les deux sens.
 */
class GbaRtcOverrideTest {

    private companion object {
        /** Signature que cherche la détection, telle que l'embarquent les cartouches d'origine. */
        const val SIGNATURE = "SIIRTC_V001"

        /** Hors de l'en-tête et du programme : une zone de données ordinaire. */
        const val SIGNATURE_OFFSET = 0x200
    }

    private fun rom(withSignature: Boolean): ByteArray {
        val image = SyntheticRom.build()
        if (withSignature) {
            SIGNATURE.forEachIndexed { index, character ->
                image[SIGNATURE_OFFSET + index] = character.code.toByte()
            }
        }
        return image
    }

    private fun rtcActiveFor(withSignature: Boolean, forced: Boolean?): Boolean =
        GbaCore(forcedRtc = forced).use { core ->
            core.loadRom(rom(withSignature))
            core.rtcActive
        }

    @Test
    fun `la signature seule suffit a instancier l'horloge`() {
        assertTrue(rtcActiveFor(withSignature = true, forced = null))
    }

    @Test
    fun `sans signature l'horloge reste absente`() {
        assertFalse(rtcActiveFor(withSignature = false, forced = null))
    }

    @Test
    fun `le reglage impose l'horloge a une ROM sans signature`() {
        // Le cas des hacks qui ajoutent eux-mêmes le pilote : c'est la raison
        // d'être du réglage.
        assertTrue(rtcActiveFor(withSignature = false, forced = true))
    }

    @Test
    fun `le reglage retire l'horloge malgre la signature`() {
        assertFalse(rtcActiveFor(withSignature = true, forced = false))
    }

    @Test
    fun `le reglage applique apres coup vaut au prochain chargement`() {
        GbaCore().use { core ->
            core.loadRom(rom(withSignature = false))
            assertFalse(core.rtcActive, "l'horloge ne devrait pas être là sans réglage")

            core.forcedRtc = true
            core.loadRom(rom(withSignature = false))
            assertTrue(core.rtcActive, "le réglage posé après coup n'a pas été pris en compte")

            core.forcedRtc = null
            core.loadRom(rom(withSignature = false))
            assertFalse(core.rtcActive, "le retour à la détection n'a pas été pris en compte")
        }
    }

    @Test
    fun `l'horloge reste absente tant qu'aucune ROM n'est chargee`() {
        GbaCore(forcedRtc = true).use { core ->
            assertFalse(core.rtcActive, "aucune cartouche : aucune horloge")
        }
    }

    @Test
    fun `le reglage ne perturbe pas la detection du type de sauvegarde`() {
        // Les deux réglages traversent le même cœur natif et le même appel de
        // construction : forcer l'un ne doit rien changer à l'autre.
        GbaCore(forcedRtc = true).use { core ->
            core.loadRom(rom(withSignature = false))
            assertEquals(GbaCore(forcedRtc = null).use { reference ->
                reference.loadRom(rom(withSignature = false))
                reference.saveType
            }, core.saveType)
        }
    }
}
