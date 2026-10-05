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

    /**
     * Le contact traverse le pont.
     *
     * Ce que cette vérification attrape est propre à la frontière : un nom de
     * symbole ou une signature qui ne coïncident pas ne se voient ni à la
     * compilation de Kotlin ni à celle du C++, seulement au premier appel, sous
     * la forme d'un `UnsatisfiedLinkError`. Ce que le contact devient ensuite
     * est éprouvé côté C++, où il s'observe.
     */
    @Test
    fun `un contact d'ecran tactile traverse le pont`() {
        core().use { c ->
            c.loadRom(NdsCartridge.image())
            c.setTouch(down = true, x = 128, y = 96)
            c.setTouch(down = false, x = 0, y = 0)
            // Hors de l'écran : le cœur ramène sur le bord plutôt que de refuser,
            // et le pont ne doit rien contrôler de son côté.
            c.setTouch(down = true, x = -5, y = 4096)
            val pixels = IntArray(c.video.pixelCount)
            c.runFrame(pixels)
        }
    }

    @Test
    fun `la console declare la taille de son ecran tactile`() {
        // La conversion d'un doigt en pixel appartient à l'interface, et elle la
        // fait avec ces deux nombres : les écrire faux enverrait chaque contact
        // à côté sans qu'aucun cœur ne s'en aperçoive.
        val screen = requireNotNull(ConsoleType.NINTENDO_DS.touchScreen)
        assertEquals(256, screen.width)
        assertEquals(192, screen.height)
        assertEquals(Pair(0, 0), screen.pixelAt(0.0, 0.0))
        assertEquals(Pair(255, 191), screen.pixelAt(1.0, 1.0))
        assertEquals(Pair(128, 96), screen.pixelAt(0.5, 0.5))
        // Les consoles sans dalle ne déclarent rien plutôt qu'une taille nulle.
        assertNull(ConsoleType.GAME_BOY.touchScreen)
        assertNull(ConsoleType.GAME_BOY_ADVANCE.touchScreen)
    }

    /**
     * Le chargement par descripteur traverse réellement le pont.
     *
     * C'est le chemin par lequel une grosse cartouche entre : l'image ne touche
     * jamais le tas Java, et le cœur la prend au lieu de la recopier. Ce que
     * cette vérification attrape est propre à la frontière — un symbole ou une
     * signature qui ne coïncident pas ne se voient qu'au premier appel — et le
     * fait qu'une image lue ainsi décrive la même cartouche qu'une image passée
     * en mémoire.
     */
    /**
     * Le chargement par descripteur traverse réellement le pont.
     *
     * Le numéro de descripteur ne s'obtient pas portablement depuis une machine
     * virtuelle de bureau : `FileDescriptor` ne le publie pas, et la réflexion
     * y est refusée depuis le système de modules. Ce que cette vérification
     * établit est donc ce qui ne peut l'être qu'ici : que le symbole natif
     * existe et se lie, et qu'il refuse proprement ce qu'il doit refuser. La
     * lecture elle-même est éprouvée côté C++, sur un vrai fichier.
     *
     * Ce refus doit être une **exception**. Une erreur traverserait les
     * rattrapages de l'application jusqu'à l'arrêter, ce qui est exactement ce
     * qu'un fichier choisi par un joueur ne doit jamais pouvoir provoquer.
     */
    @Test
    fun `le chargement par descripteur refuse proprement un descripteur invalide`() {
        core().use { c ->
            // Exiger une exception, c'est exiger que ce ne soit pas une erreur :
            // `Error` ne descend pas d'`Exception`, et une erreur ferait échouer
            // cette attente au lieu de passer inaperçue.
            assertFailsWith<Exception> {
                c.loadRomFromDescriptor(descriptor = -1, sizeBytes = 16L)
            }
        }
        core().use { c ->
            // Un numéro qu'aucun fichier n'occupe : la lecture échoue, et le
            // refus reste une exception.
            assertFailsWith<Exception> {
                c.loadRomFromDescriptor(descriptor = 100_000, sizeBytes = 16L)
            }
        }
    }

    /**
     * Les consoles dont les cartouches sont petites n'ont pas ce chemin.
     *
     * Elles rendent `false` plutôt que d'échouer : l'appelant retombe alors sur
     * le chargement en mémoire, qui leur convient.
     */
    @Test
    fun `une console sans ce chemin le dit au lieu d'echouer`() {
        val gb = com.ravenemu.core.gb.GameBoyCore()
        assertFalse(gb.loadRomFromDescriptor(descriptor = 0, sizeBytes = 0L))
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
