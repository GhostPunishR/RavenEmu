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

    /**
     * Les ROM synthétiques de base contiennent surtout des zéros et des octets
     * d'en-tête. Les exécuter linéairement finit par interpréter ces métadonnées
     * comme des opcodes, dont STOP. C'est particulièrement mauvais comme test de
     * parité depuis que le cœur natif émule correctement STOP alors que l'ancien
     * oracle Kotlin ne le fait pas encore. On place donc une vraie boucle stable
     * à l'entrée pour que ces cas vérifient le câblage, pas du code accidentel.
     */
    private fun boucleStable(rom: ByteArray): ByteArray = rom.apply {
        this[0x0100] = 0x18 // JR -2
        this[0x0101] = 0xFE.toByte()
    }

    /**
     * ROM qui fait travailler le matériel, au lieu de boucler à vide.
     *
     * Les ROM des autres cas n'exécutent aucun programme : écran éteint, APU
     * muet, timer arrêté. Elles ne pouvaient donc pas départager les deux
     * implémentations sur le PPU, l'APU ni les timers - précisément les organes
     * les plus délicats à porter. Ce programme SM83 allume l'écran avec une
     * tuile en VRAM, déclenche le canal 1 pour que l'APU produise vraiment des
     * échantillons, et arme le timer, puis boucle.
     *
     * Les octets sont écrits à la main : le dépôt n'a pas d'assembleur Game Boy,
     * et en introduire un dépasserait le cadre de ce lot.
     */
    private fun programmeReel(cgbFlag: Int = 0x00): ByteArray =
        TestRoms.build(type = 0x00, cgbFlag = cgbFlag) { rom ->
            var i = 0
            fun octets(vararg valeurs: Int) {
                for (v in valeurs) rom[i++] = v.toByte()
            }

            // 0x0100 : l'entrée ne dispose que de quatre octets avant la zone
            // réservée à l'en-tête ; on saute par-dessus.
            i = 0x0100
            octets(0x00, 0xC3, 0x50, 0x01) // NOP ; JP 0x0150

            i = 0x0150
            // Alimentation de l'APU, volume et routage des deux voies.
            octets(0x3E, 0x80, 0xE0, 0x26) // LD A,0x80 ; LDH (NR52),A
            octets(0x3E, 0x77, 0xE0, 0x24) // LD A,0x77 ; LDH (NR50),A
            octets(0x3E, 0xFF, 0xE0, 0x25) // LD A,0xFF ; LDH (NR51),A
            // Canal 1 : rapport cyclique, enveloppe, fréquence, déclenchement.
            octets(0x3E, 0x80, 0xE0, 0x11) // LD A,0x80 ; LDH (NR11),A
            octets(0x3E, 0xF3, 0xE0, 0x12) // LD A,0xF3 ; LDH (NR12),A
            octets(0x3E, 0x83, 0xE0, 0x13) // LD A,0x83 ; LDH (NR13),A
            octets(0x3E, 0x87, 0xE0, 0x14) // LD A,0x87 ; LDH (NR14),A
            // Seize octets de motif dans la première tuile.
            octets(0x21, 0x00, 0x80) // LD HL,0x8000
            octets(0x06, 0x10)       // LD B,16
            octets(0x3E, 0xAA)       // LD A,0xAA
            octets(0x77, 0x23, 0x05) // LD (HL),A ; INC HL ; DEC B
            octets(0x20, 0xFB)       // JR NZ,-5
            // Palette monochrome.
            octets(0x3E, 0xE4, 0xE0, 0x47) // LD A,0xE4 ; LDH (BGP),A
            // Palette de fond Game Boy Color : sans elle l'écran resterait
            // uniformément noir en mode Color ; `BGP` n'y a aucun effet et les
            // palettes CGB valent zéro au démarrage.
            octets(0x3E, 0x80, 0xE0, 0x68) // LD A,0x80 ; LDH (BCPS),A
            octets(0x3E, 0xFF, 0xE0, 0x69, 0x3E, 0x7F, 0xE0, 0x69)
            octets(0x3E, 0x1F, 0xE0, 0x69, 0x3E, 0x00, 0xE0, 0x69)
            octets(0x3E, 0xE0, 0xE0, 0x69, 0x3E, 0x03, 0xE0, 0x69)
            octets(0x3E, 0x00, 0xE0, 0x69, 0x3E, 0x7C, 0xE0, 0x69)
            octets(0x3E, 0x91, 0xE0, 0x40) // LD A,0x91 ; LDH (LCDC),A
            // Timer : recharge à zéro, horloge la plus rapide.
            octets(0x3E, 0x00, 0xE0, 0x06) // LD A,0 ; LDH (TMA),A
            octets(0x3E, 0x05, 0xE0, 0x07) // LD A,0x05 ; LDH (TAC),A
            octets(0x18, 0xFE)             // JR -2
        }

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

            // L'oracle Kotlin et le cœur C++ n'ont plus vocation à partager leur
            // représentation binaire interne : le natif porte désormais l'état
            // du pipeline PPU, des DMA, de STOP, du SIO et de l'IR. On exige en
            // revanche que chacun puisse restaurer transactionnellement son
            // propre état avant de poursuivre la comparaison fonctionnelle.
            val etatReference = reference.saveState()
            val etatNatif = natif.saveState()
            reference.loadState(etatReference)
            natif.loadState(etatNatif)
        }
    }

    @Test
    fun `le coeur natif suit le Kotlin sur une cartouche Game Boy`() {
        comparer(boucleStable(TestRoms.build()), "Game Boy")
    }

    @Test
    fun `le coeur natif suit le Kotlin sur une cartouche Game Boy Color`() {
        comparer(boucleStable(TestRoms.build(cgbFlag = 0x80)), "Game Boy Color")
    }

    @Test
    fun `le coeur natif suit le Kotlin sur une cartouche a pile`() {
        comparer(
            boucleStable(TestRoms.build(type = 0x13, romSizeCode = 0x01, ramSizeCode = 0x02)),
            "MBC3 + pile",
        )
    }

    @Test
    fun `le coeur natif suit le Kotlin sur un programme qui allume ecran et son`() {
        comparer(programmeReel(), "programme réel, Game Boy")
    }

    @Test
    fun `le coeur natif suit le Kotlin sur ce programme en mode Color`() {
        comparer(programmeReel(cgbFlag = 0x80), "programme réel, Game Boy Color")
    }

    @Test
    fun `chaque implementation restaure son propre etat puis reste en parite`() {
        val rom = programmeReel(cgbFlag = 0x80)
        val reference = KotlinGameBoyCore(HORLOGE)
        GameBoyCore(HORLOGE).use { natif ->
            reference.loadRom(rom, null)
            natif.loadRom(rom, null)

            repeat(40) {
                reference.runFrame(framebuffer())
                natif.runFrame(framebuffer())
            }

            val etatReference = reference.saveState()
            val etatNatif = natif.saveState()
            reference.loadState(etatReference)
            natif.loadState(etatNatif)

            val suiteRef = framebuffer()
            val suiteNat = framebuffer()
            repeat(20) { pas ->
                reference.runFrame(suiteRef)
                natif.runFrame(suiteNat)
                assertContentEquals(
                    suiteRef,
                    suiteNat,
                    "Divergence à la trame $pas après restaurations propres",
                )
            }
        }
    }

    @Test
    fun `les deux implementations exposent la meme RAM a pile`() {
        val rom = TestRoms.build(type = 0x13, romSizeCode = 0x01, ramSizeCode = 0x02)
        val battery = ByteArray(8 * 1024) { i -> (i * 17).toByte() }
        val reference = KotlinGameBoyCore(HORLOGE)
        GameBoyCore(HORLOGE).use { natif ->
            reference.loadRom(rom, battery)
            natif.loadRom(rom, battery)

            assertTrue(reference.hasBatteryRam)
            assertTrue(natif.hasBatteryRam)
            assertEquals(reference.batteryRamDirty, natif.batteryRamDirty)
            assertContentEquals(
                reference.snapshotBatteryRam()?.data,
                natif.snapshotBatteryRam()?.data,
            )
        }
    }
}
