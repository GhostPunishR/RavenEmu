package com.ravenemu.core.gba

import com.ravenemu.emulation.api.EmulatorButton
import com.ravenemu.emulation.api.EmulatorCore
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Parité entre l'implémentation Kotlin de référence et le cœur C++ livré.
 *
 * Le portage natif a conservé l'implémentation Kotlin dans les sources de test
 * en la qualifiant d'« oracle de régression ». Un oracle suppose une
 * comparaison : sans elle, les centaines de tests de ce module valident du code
 * qui n'est plus livré, et le C++ embarqué dans l'APK n'est couvert par rien.
 * C'est cette comparaison.
 *
 * Ce qui est comparé, à chaque trame : le tampon vidéo entier, le nombre
 * d'échantillons audio produits et leur contenu. Puis, en fin de course, les
 * octets d'état — le format étant persisté chez les joueurs, une divergence y
 * casserait leurs sauvegardes.
 */
class NativeParityTest {

    private companion object {
        /**
         * Assez de trames pour traverser plusieurs cycles complets du séquenceur
         * audio et de la machine à états du PPU, sans allonger la suite : une
         * divergence de cadence se voit dès les premières dizaines de trames.
         */
        const val FRAMES = 180

        val BOUTONS = EmulatorButton.entries.toList()
    }

    private fun framebuffer() = IntArray(240 * 160)

    /** Draine tout l'audio disponible et le rend sous forme comparable. */
    private fun drain(core: EmulatorCore): ShortArray {
        val tampon = ShortArray(8192)
        val produits = core.readAudio(tampon)
        return tampon.copyOf(produits)
    }

    @Test
    fun `le coeur natif produit les memes trames et le meme audio que le Kotlin`() {
        val rom = SyntheticRom.build()
        val reference = KotlinGbaCore()
        GbaCore().use { natif ->
            reference.loadRom(rom, null)
            natif.loadRom(rom, null)

            val trameRef = framebuffer()
            val trameNat = framebuffer()

            for (trame in 0 until FRAMES) {
                // Le saut de rendu fait partie du contrat : l'alterner évite
                // qu'une divergence ne se cache dans le chemin non rendu.
                val rendu = trame % 3 != 0
                reference.runFrame(trameRef, rendu)
                natif.runFrame(trameNat, rendu)
                assertContentEquals(trameRef, trameNat, "Trames différentes à la trame $trame")

                val audioRef = drain(reference)
                val audioNat = drain(natif)
                assertEquals(
                    audioRef.size,
                    audioNat.size,
                    "Nombre d'échantillons différent à la trame $trame",
                )
                assertContentEquals(audioRef, audioNat, "Audio différent à la trame $trame")

                // Les entrées passent par des chemins distincts (appel direct
                // contre traversée JNI) : elles doivent aboutir au même KEYINPUT.
                val bouton = BOUTONS[trame % BOUTONS.size]
                reference.setButton(bouton, trame % 2 == 0)
                natif.setButton(bouton, trame % 2 == 0)
            }

            assertContentEquals(
                reference.saveState(),
                natif.saveState(),
                "États divergents après $FRAMES trames",
            )
        }
    }

    @Test
    fun `un etat ecrit par le Kotlin est relu par le natif et inversement`() {
        // La compatibilité des sauvegardes est la promesse la plus exposée du
        // portage : le format est persisté chez les joueurs. Une divergence de
        // largeur ou d'ordre de champ enfouie dans le PPU, l'APU ou la cartouche
        // ne se verrait qu'ici.
        val rom = SyntheticRom.build()
        val reference = KotlinGbaCore()
        GbaCore().use { natif ->
            reference.loadRom(rom, null)
            natif.loadRom(rom, null)

            val trame = framebuffer()
            repeat(40) {
                reference.runFrame(trame, true)
                natif.runFrame(framebuffer(), true)
            }

            val etatKotlin = reference.saveState()
            natif.loadState(etatKotlin)
            assertContentEquals(
                etatKotlin,
                natif.saveState(),
                "Le natif n'a pas restitué à l'identique un état écrit par le Kotlin",
            )

            val etatNatif = natif.saveState()
            reference.loadState(etatNatif)
            assertContentEquals(
                etatNatif,
                reference.saveState(),
                "Le Kotlin n'a pas restitué à l'identique un état écrit par le natif",
            )

            // Repartir du même état doit produire la même suite : un état qui se
            // relit sans reprendre l'exécution à l'identique ne vaut rien.
            val suiteRef = framebuffer()
            val suiteNat = framebuffer()
            repeat(20) { pas ->
                reference.runFrame(suiteRef, true)
                natif.runFrame(suiteNat, true)
                assertContentEquals(
                    suiteRef,
                    suiteNat,
                    "Divergence à la trame $pas après restauration croisée",
                )
            }
        }
    }

    @Test
    fun `les deux implementations detectent la meme sauvegarde de cartouche`() {
        val rom = SyntheticRom.build()
        val reference = KotlinGbaCore()
        GbaCore().use { natif ->
            reference.loadRom(rom, null)
            natif.loadRom(rom, null)
            assertEquals(reference.saveType, natif.saveType, "Type de sauvegarde divergent")
            assertEquals(
                reference.hasBatteryRam,
                natif.hasBatteryRam,
                "Présence de RAM à pile divergente",
            )
            assertEquals(
                reference.snapshotBatteryRam()?.data?.size,
                natif.snapshotBatteryRam()?.data?.size,
                "Taille de RAM à pile divergente",
            )
        }
    }

    @Test
    fun `la comparaison porte bien sur la bibliotheque native du depot`() {
        // Garde-fou sur le dispositif lui-même : si la propriété n'était pas
        // posée, `System.loadLibrary` chercherait une bibliothèque du système et
        // la parité ne prouverait plus rien sur le code de ce dépôt.
        val chemin = System.getProperty("ravenemu.native.library")
        assertTrue(
            !chemin.isNullOrBlank(),
            "Les tests de parité doivent charger la bibliothèque construite depuis cores/",
        )
    }
}
