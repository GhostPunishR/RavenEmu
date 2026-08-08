package com.ravenemu.core.gb

import com.ravenemu.emulation.api.EmulatorButton
import com.ravenemu.emulation.api.EmulatorCore
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Parité entre l'implémentation Kotlin de référence et le cœur C++ livré.
 *
 * Voir la note du test homonyme de `gba-core` : l'implémentation Kotlin est
 * restée dans les sources de test sous le nom d'oracle, mais un oracle qu'on
 * ne confronte à rien ne prouve rien. Les deux modes de la gamme sont couverts,
 * le Game Boy monochrome produisant des niveaux 0..3 quand le Game Boy Color
 * produit déjà de l'ARGB : une confusion de format ne se verrait pas sur un
 * seul des deux.
 */
class NativeParityTest {

    private companion object {
        const val FRAMES = 180
        val BOUTONS = EmulatorButton.entries.toList()

        /**
         * Horloge figée, identique des deux côtés.
         *
         * Les deux implémentations lisent l'heure système par défaut. Les
         * laisser faire ferait diverger tout état qui date quoi que ce soit, et
         * la parité échouerait pour une raison sans rapport avec le portage.
         */
        const val EPOCH_FIXE = 1_700_000_000L
        val HORLOGE: () -> Long = { EPOCH_FIXE }
    }

    private fun framebuffer() = IntArray(160 * 144)

    private fun drain(core: EmulatorCore): ShortArray {
        val tampon = ShortArray(8192)
        return tampon.copyOf(core.readAudio(tampon))
    }

    /** Compare les deux implémentations sur une ROM donnée, trame par trame. */
    private fun comparer(rom: ByteArray, etiquette: String) {
        val reference = KotlinGameBoyCore(HORLOGE)
        GameBoyCore(HORLOGE).use { natif ->
            reference.loadRom(rom, null)
            natif.loadRom(rom, null)

            assertEquals(
                reference.framebufferFormat,
                natif.framebufferFormat,
                "$etiquette : format de tampon vidéo divergent",
            )

            val trameRef = framebuffer()
            val trameNat = framebuffer()

            for (trame in 0 until FRAMES) {
                reference.runFrame(trameRef)
                natif.runFrame(trameNat)
                assertContentEquals(
                    trameRef,
                    trameNat,
                    "$etiquette : trames différentes à la trame $trame",
                )

                val audioRef = drain(reference)
                val audioNat = drain(natif)
                assertEquals(
                    audioRef.size,
                    audioNat.size,
                    "$etiquette : nombre d'échantillons différent à la trame $trame",
                )
                assertContentEquals(
                    audioRef,
                    audioNat,
                    "$etiquette : audio différent à la trame $trame",
                )

                val bouton = BOUTONS[trame % BOUTONS.size]
                reference.setButton(bouton, trame % 2 == 0)
                natif.setButton(bouton, trame % 2 == 0)
            }

            assertContentEquals(
                reference.saveState(),
                natif.saveState(),
                "$etiquette : états divergents après $FRAMES trames",
            )
        }
    }

    @Test
    fun `le coeur natif suit le Kotlin sur une cartouche Game Boy`() {
        comparer(TestRoms.build(), "Game Boy")
    }

    @Test
    fun `le coeur natif suit le Kotlin sur une cartouche Game Boy Color`() {
        comparer(TestRoms.build(cgbFlag = 0x80), "Game Boy Color")
    }

    @Test
    fun `le coeur natif suit le Kotlin sur une cartouche a pile`() {
        // MBC3 avec RAM et pile : couvre la banque de cartouche et la RAM
        // sauvegardée, dont le format `.sav` est exposé aux joueurs.
        comparer(TestRoms.build(type = 0x13, romSizeCode = 0x01, ramSizeCode = 0x02), "MBC3 + pile")
    }

    @Test
    fun `un etat ecrit par le Kotlin est relu par le natif et inversement`() {
        val rom = TestRoms.build(type = 0x13, romSizeCode = 0x01, ramSizeCode = 0x02)
        val reference = KotlinGameBoyCore(HORLOGE)
        GameBoyCore(HORLOGE).use { natif ->
            reference.loadRom(rom, null)
            natif.loadRom(rom, null)

            repeat(40) {
                reference.runFrame(framebuffer())
                natif.runFrame(framebuffer())
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

            val suiteRef = framebuffer()
            val suiteNat = framebuffer()
            repeat(20) { pas ->
                reference.runFrame(suiteRef)
                natif.runFrame(suiteNat)
                assertContentEquals(
                    suiteRef,
                    suiteNat,
                    "Divergence à la trame $pas après restauration croisée",
                )
            }
        }
    }

    @Test
    fun `les deux implementations exposent la meme RAM a pile`() {
        val rom = TestRoms.build(type = 0x13, romSizeCode = 0x01, ramSizeCode = 0x02)
        val reference = KotlinGameBoyCore(HORLOGE)
        GameBoyCore(HORLOGE).use { natif ->
            reference.loadRom(rom, null)
            natif.loadRom(rom, null)
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
        val chemin = System.getProperty("ravenemu.native.library")
        assertTrue(
            !chemin.isNullOrBlank(),
            "Les tests de parité doivent charger la bibliothèque construite depuis cores/",
        )
    }
}
