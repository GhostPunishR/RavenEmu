package com.ravenemu.core.nds

import com.ravenemu.core.nds.cartridge.NdsCartridge
import com.ravenemu.emulation.api.ConsoleType
import com.ravenemu.emulation.api.FramebufferFormat
import com.ravenemu.emulation.api.RomLoadException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Le cœur Nintendo DS vu depuis l'application, à travers le pont natif.
 *
 * Ce que ces vérifications ajoutent aux suites C++ du cœur est le **chemin** :
 * l'identifiant de console transporté par le pont doit atteindre la bonne
 * fabrique. Une branche manquante ou mal comparée ne se verrait pas côté C++,
 * où le cœur se construit directement ; elle donnerait ici « Console RavenEmu
 * inconnue », ou pire, un cœur d'une autre console qui accepterait la ROM.
 */
class NdsCoreTest {

    private fun core() = NdsCore()

    @Test
    fun `le pont natif construit bien un coeur Nintendo DS`() {
        core().use { c ->
            c.loadRom(NdsCartridge.image())
            assertEquals(ConsoleType.NINTENDO_DS, c.console)
        }
    }

    @Test
    fun `le tampon porte les deux ecrans empiles`() {
        val c = core()
        assertEquals(256, c.video.width)
        assertEquals(384, c.video.height)
        assertEquals(2 * 192, c.video.height)
        assertEquals(FramebufferFormat.ARGB_8888, c.framebufferFormat)
        c.close()
    }

    @Test
    fun `une cartouche de synthese s'amorce et une trame se balaie`() {
        core().use { c ->
            c.loadRom(NdsCartridge.image())
            val pixels = IntArray(c.video.pixelCount)
            c.runFrame(pixels)
            // Le contenu dépend du programme amorcé ; ce qui est éprouvé est
            // qu'une trame entière est parcourue sans erreur ni débordement.
            assertEquals(256 * 384, pixels.size)
        }
    }

    @Test
    fun `une image que l'en-tete ne decrit pas est refusee`() {
        core().use { c ->
            assertFailsWith<RomLoadException> { c.loadRom(ByteArray(0x100)) }
        }
    }

    @Test
    fun `une cartouche exclusivement DSi est refusee`() {
        core().use { c ->
            assertFailsWith<RomLoadException> {
                c.loadRom(NdsCartridge.image(unitCode = 0x03))
            }
        }
    }

    @Test
    fun `le moteur annonce n'avoir pas de format d'etat`() {
        // L'annonce et le refus doivent aller ensemble : un moteur qui se
        // déclarerait capable puis refuserait ferait échouer une sauvegarde que
        // l'application a proposée, au pire moment pour le joueur.
        core().use { c ->
            assertFalse(c.supportsSaveState)
            c.loadRom(NdsCartridge.image())
            assertFailsWith<IllegalStateException> { c.saveState() }
            assertFailsWith<IllegalStateException> { c.loadState(ByteArray(8)) }
        }
    }

    @Test
    fun `le fournisseur repond la meme chose que le moteur`() {
        // Les deux réponses viennent d'une seule constante ; ce test empêche
        // qu'elles se remettent à diverger le jour où le format arrivera.
        assertEquals(NdsCore().use { it.supportsSaveState }, NdsConsoleProvider().supportsSaveState)
    }

    @Test
    fun `la cartouche n'a pas encore de memoire a pile`() {
        // Se déclarer sans mémoire à pile évite que l'application n'écrive un
        // `.sav` vide par-dessus une vraie sauvegarde.
        core().use { c ->
            c.loadRom(NdsCartridge.image())
            assertFalse(c.hasBatteryRam)
            assertFalse(c.batteryRamDirty)
            assertNull(c.snapshotBatteryRam())
        }
    }

    @Test
    fun `aucun echantillon audio n'est encore produit`() {
        core().use { c ->
            c.loadRom(NdsCartridge.image())
            assertEquals(0, c.readAudio(ShortArray(64)))
        }
    }

    @Test
    fun `le moteur se ferme deux fois sans broncher`() {
        val c = core()
        c.loadRom(NdsCartridge.image())
        c.close()
        c.close()
        assertFailsWith<IllegalStateException> { c.reset() }
    }

    @Test
    fun `la cadence annoncee est celle du materiel`() {
        val c = core()
        assertTrue(c.video.refreshRateHz in 59.5..60.0, "${c.video.refreshRateHz}")
        assertEquals(32_768, c.audio.sampleRateHz)
        assertEquals(2, c.audio.channelCount)
        c.close()
    }
}
