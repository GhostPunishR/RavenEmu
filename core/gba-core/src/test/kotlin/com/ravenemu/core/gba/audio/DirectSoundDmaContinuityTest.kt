package com.ravenemu.core.gba.audio

import com.ravenemu.core.gba.GbaCore
import com.ravenemu.core.gba.GbaMachine
import com.ravenemu.core.gba.SyntheticRom
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Continuité du flux Direct Sound pendant un gros transfert DMA.
 *
 * C'est la reproduction de bout en bout du défaut entendu en jeu. Un DMA long
 * arrête le processeur et rend d'un coup plusieurs milliers de cycles à la
 * boucle de la machine. Tant que les périphériques avançaient de ce bloc entier
 * d'un seul bond, tous les débordements de timer étaient traités d'abord — donc
 * toute la FIFO consommée — puis tous les échantillons produits ensuite, en
 * n'observant que la dernière valeur dépilée. Le flux se figeait sur une
 * constante pendant toute la durée du transfert : un craquement, puis un blanc.
 *
 * Rien n'est mesuré ici en millisecondes ni en pourcentage : la propriété est
 * combinatoire. Le tampon d'échantillons ne contient jamais deux valeurs égales
 * à deux rangs d'écart, et le point d'échantillonnage avance exactement de deux
 * octets à chaque fois ; **deux trames PCM consécutives ne peuvent donc pas être
 * identiques**, sauf si le flux a été figé.
 */
class DirectSoundDmaContinuityTest {

    /** Tampon d'échantillons du « jeu », en fin d'EWRAM. */
    private val sampleBase = 0x0203_C000
    private val sampleBytes = 16 * 1024

    /**
     * Valeur de l'octet d'indice [i] du tampon : un pas de 13 sur sept bits,
     * translaté en valeur signée. Deux octets distants de deux rangs diffèrent
     * toujours de 26 modulo 128, ce qui rend toute répétition détectable.
     */
    private fun octet(i: Int): Int = ((i * 13) and 0x7F) - 64

    private fun machine(): GbaMachine {
        val m = GbaMachine(SyntheticRom.build())
        val bus = m.bus
        for (i in 0 until sampleBytes) bus.write8(sampleBase + i, octet(i) and 0xFF)

        bus.write16(0x0400_0084, 0x0080) // audio activé
        // Direct Sound A seul : volume plein, gauche et droite, timer 0, FIFO
        // remise à zéro. Le canal B reste muet et rattaché au timer 1.
        bus.write16(0x0400_0082, 0x0B04 or 0x4000)
        // Canal DMA 1 : tampon EWRAM vers FIFO A, mode spécial, mots de 32 bits.
        bus.write32(0x0400_00BC, sampleBase)
        bus.write32(0x0400_00C0, 0x0400_00A0)
        bus.write16(0x0400_00C4, 4)
        bus.write16(0x0400_00C6, 0x8000 or 0x3000 or 0x0400)
        // Timer 0, prédiviseur 1, rechargement 0xFF00 : un débordement tous les
        // 256 cycles, soit un octet consommé pour deux échantillons produits.
        bus.write16(0x0400_0100, 0xFF00)
        bus.write16(0x0400_0102, 0x0080)
        return m
    }

    private fun vider(m: GbaMachine): ShortArray {
        val tampon = ShortArray(32768)
        val n = m.apu.readSamples(tampon)
        return tampon.copyOf(n)
    }

    /** Plus longue suite de trames stéréo consécutives strictement identiques. */
    private fun plusLongPalier(pcm: ShortArray): Int {
        var max = 1
        var courant = 1
        var i = 2
        while (i + 1 < pcm.size) {
            courant = if (pcm[i] == pcm[i - 2] && pcm[i + 1] == pcm[i - 1]) courant + 1 else 1
            if (courant > max) max = courant
            i += 2
        }
        return max
    }

    /**
     * Déclenche un transfert DMA long et retourne les cycles qu'il coûte. Le
     * canal 3 recopie 4096 mots de 32 bits d'EWRAM à EWRAM, comme le ferait un
     * jeu qui décompresse une carte de tuiles.
     */
    private fun grosTransfert(m: GbaMachine): Int {
        m.bus.write32(0x0400_00D4, 0x0200_0000)
        m.bus.write32(0x0400_00D8, 0x0201_0000)
        m.bus.write16(0x0400_00DC, 4096)
        m.bus.write16(0x0400_00DE, 0x8400) // activé, 32 bits, déclenchement immédiat
        return m.dma.pendingCycles
    }

    @Test
    fun `un gros DMA ne fige pas le flux Direct Sound`() {
        val m = machine()
        m.runFrame(GbaCore.CYCLES_PER_FRAME) // amorçage du flux
        vider(m)

        val cyclesDma = grosTransfert(m)
        assertTrue(
            cyclesDma > 4 * CYCLES_PAR_ECHANTILLON,
            "Le transfert doit être assez long pour couvrir plusieurs échantillons, mesuré : $cyclesDma",
        )

        // La boucle de la machine rend ces cycles aux périphériques en un seul
        // appel : c'est exactement le chemin qui produisait le blanc.
        m.runFrame(cyclesDma)
        val pcm = vider(m)

        val attendu = cyclesDma / CYCLES_PAR_ECHANTILLON
        assertTrue(
            pcm.size / 2 >= attendu / 2,
            "Trop peu d'échantillons produits : ${pcm.size / 2} pour $attendu attendus",
        )
        assertTrue(
            plusLongPalier(pcm) <= PALIER_MAXIMAL,
            "Flux figé sur ${plusLongPalier(pcm)} trames consécutives pendant le transfert",
        )
    }

    @Test
    fun `le flux reste vivant sur plusieurs transferts successifs`() {
        // Un jeu ne fait pas un gros DMA isolé : il en enchaîne. Le défaut se
        // rejouait à chaque transfert, ce qui donnait un son haché.
        val m = machine()
        m.runFrame(GbaCore.CYCLES_PER_FRAME)
        vider(m)

        repeat(4) {
            val cycles = grosTransfert(m)
            m.runFrame(cycles)
            val pcm = vider(m)
            assertTrue(
                plusLongPalier(pcm) <= PALIER_MAXIMAL,
                "Transfert n°$it : flux figé sur ${plusLongPalier(pcm)} trames",
            )
        }
    }

    @Test
    fun `sans gros transfert le flux est deja continu`() {
        // Contrôle négatif : le défaut ne vient pas du flux lui-même, mais de la
        // taille des blocs rendus aux périphériques. Sans DMA long, la boucle
        // avance de quelques cycles par instruction et le son est correct — y
        // compris avant correction.
        val m = machine()
        m.runFrame(GbaCore.CYCLES_PER_FRAME)
        vider(m)
        m.runFrame(GbaCore.CYCLES_PER_FRAME)
        val pcm = vider(m)

        assertTrue(pcm.isNotEmpty(), "Une trame doit produire du son")
        assertTrue(
            plusLongPalier(pcm) <= PALIER_MAXIMAL,
            "Flux figé hors transfert sur ${plusLongPalier(pcm)} trames",
        )
    }

    private companion object {
        /** Période d'un échantillon PCM, en cycles CPU. */
        const val CYCLES_PAR_ECHANTILLON = 512

        /**
         * Deux trames consécutives identiques restent possibles : la saturation
         * du mixage peut aplatir deux valeurs voisines. Trois de suite ne le sont
         * pas avec ce tampon. Le défaut d'origine figeait plusieurs dizaines de
         * trames d'affilée, très au-delà de cette marge.
         */
        const val PALIER_MAXIMAL = 2
    }
}
