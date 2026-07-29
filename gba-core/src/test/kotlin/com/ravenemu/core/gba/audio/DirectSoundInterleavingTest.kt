package com.ravenemu.core.gba.audio

import com.ravenemu.core.gba.GbaMachine
import com.ravenemu.core.gba.SyntheticRom
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Entrelacement des timers, des FIFO Direct Sound et de la génération PCM.
 *
 * Les canaux Direct Sound ne sont pas cadencés par l'horloge audio : c'est un
 * débordement de timer qui dépile l'octet suivant de leur FIFO, et cette valeur
 * est ensuite lue au moment d'échantillonner. Les deux mécanismes doivent donc
 * rester entrelacés dans le temps.
 *
 * Avancer les périphériques d'un gros bloc traitait tous les débordements en
 * premier, puis produisait tous les échantillons : ceux-ci n'observaient que la
 * dernière valeur dépilée. Sur un bloc suffisamment long, la FIFO était vidée
 * avant qu'un seul échantillon ne soit produit, et **tout le contenu audio du
 * bloc disparaissait**. C'est le blanc qui s'entend pendant un gros DMA.
 *
 * La propriété vérifiée ici est simple à énoncer et difficile à contourner : le
 * PCM produit ne doit pas dépendre du découpage des appels.
 *
 * Ces tests pilotent [GbaMachine.advancePeripherals], l'ordonnanceur réel, et
 * non une copie : réécrire la boucle dans le banc d'essai la ferait passer quoi
 * qu'il arrive au moteur.
 */
class DirectSoundInterleavingTest {

    /**
     * Machine réelle configurée pour ne produire que du Direct Sound : les
     * quatre canaux PSG restent au repos, faute de déclenchement, et tout ce qui
     * sort du mixage vient donc des FIFO.
     */
    private class Banc(recharge: Int = RECHARGE) {
        val machine = GbaMachine(SyntheticRom.build())
        val apu get() = machine.apu

        init {
            val bus = machine.bus
            bus.write16(SOUNDCNT_X, 0x0080) // audio activé
            bus.write16(SOUNDCNT_H, DS_A or B_SUR_TIMER1)
            // Prédiviseur 1 : la période de débordement est directement le
            // complément du rechargement, en cycles CPU.
            bus.write16(TM_RELOAD, recharge)
            bus.write16(TM_CONTROL, TIMER_ACTIF)
        }

        /**
         * Remplit la FIFO d'une alternance franche de valeurs signées : toute
         * valeur perdue ou observée au mauvais instant se voit immédiatement
         * dans le PCM.
         */
        fun remplirFifo(mots: Int = 8, canal: Int = 0) {
            val adresse = if (canal == 0) FIFO_A else FIFO_B
            repeat(mots) { i ->
                machine.bus.write32(adresse, if (i % 2 == 0) MOT_POSITIF else MOT_NEGATIF)
            }
        }

        /** Avance de [total] cycles CPU par blocs d'au plus [pas] cycles. */
        fun avancer(total: Int, pas: Int) {
            var restant = total
            while (restant > 0) {
                val bloc = minOf(pas, restant)
                machine.advancePeripherals(bloc)
                restant -= bloc
            }
        }

        fun pcm(): ShortArray {
            val tampon = ShortArray(TAMPON)
            val n = apu.readSamples(tampon)
            return tampon.copyOf(n)
        }
    }

    private fun executer(total: Int, pas: Int, mots: Int = 8): ShortArray =
        Banc().apply { remplirFifo(mots); avancer(total, pas) }.pcm()

    @Test
    fun `le PCM ne depend pas du decoupage des appels`() {
        // Le test central. Sans ordonnancement par événement, la variante en un
        // seul appel ne produit que des zéros là où la référence porte du son.
        val pcmReference = executer(TOTAL, pas = 4)
        val pcmBatched = executer(TOTAL, pas = TOTAL)

        assertTrue(
            pcmReference.any { it != 0.toShort() },
            "La référence doit contenir du son, sinon le test ne prouve rien",
        )
        assertContentEquals(pcmReference, pcmBatched)
    }

    @Test
    fun `tous les decoupages intermediaires donnent le meme PCM`() {
        val reference = executer(TOTAL, pas = 1)
        for (pas in listOf(3, 7, 64, 128, 512, 1000, TOTAL)) {
            assertContentEquals(reference, executer(TOTAL, pas = pas), "pas de $pas cycles")
        }
    }

    @Test
    fun `un gros bloc n'avale pas le contenu de la FIFO`() {
        // Le symptôme le plus visible : sur un bloc long, la FIFO était vidée
        // avant le premier échantillon et le bloc entier sortait muet.
        val enUnBloc = executer(TOTAL, pas = TOTAL)
        assertTrue(
            enUnBloc.any { it != 0.toShort() },
            "Le bloc ne doit pas être muet : les valeurs de la FIFO ont été perdues",
        )
    }

    @Test
    fun `l'alternance de la FIFO se retrouve dans le PCM`() {
        // Une FIFO alternée doit produire un signal qui change de signe, pas une
        // valeur figée : c'est ce qui distingue un entrelacement correct d'un
        // simple « dernière valeur vue ».
        val pcm = executer(TOTAL, pas = 4)
        val positifs = pcm.count { it > 0 }
        val negatifs = pcm.count { it < 0 }
        assertTrue(
            positifs > 0 && negatifs > 0,
            "Signal figé : $positifs positifs, $negatifs négatifs",
        )
    }

    @Test
    fun `une FIFO vide ne fabrique pas de son`() {
        val pcm = Banc().apply { avancer(TOTAL, pas = 4) }.pcm()
        assertTrue(pcm.all { it == 0.toShort() }, "Sans FIFO alimentée, la sortie doit être muette")
    }

    @Test
    fun `le nombre d'echantillons produits ne depend pas du decoupage`() {
        // Le découpage ne doit pas seulement préserver les valeurs : il ne doit
        // pas non plus escamoter d'échantillon. Les pas non divisibles par la
        // période d'échantillonnage sont les plus révélateurs.
        val attendu = executer(TOTAL, pas = 4).size
        for (pas in listOf(1, 3, 7, 16, 512, TOTAL)) {
            assertEquals(attendu, executer(TOTAL, pas = pas).size, "pas de $pas cycles")
        }
    }

    @Test
    fun `un debordement tombant exactement sur un echantillon reste stable`() {
        // Période de 512 cycles : le débordement et l'instant d'échantillonnage
        // coïncident. C'est le cas limite de l'ordonnanceur, où les deux
        // échéances valent zéro en même temps ; il ne doit ni boucler
        // indéfiniment, ni perdre l'une des deux.
        fun run(pas: Int) = Banc(recharge = RECHARGE_ALIGNEE)
            .apply { remplirFifo(); avancer(TOTAL, pas) }.pcm()

        val reference = run(1)
        assertTrue(reference.any { it != 0.toShort() }, "Le cas aligné doit produire du son")
        for (pas in listOf(4, 512, TOTAL)) {
            assertContentEquals(reference, run(pas), "pas de $pas cycles")
        }
    }

    @Test
    fun `les deux canaux sur des timers differents restent entrelaces`() {
        // A sur timer 0, B sur timer 1, périodes différentes : le PCM doit
        // rester indépendant du découpage pour les deux canaux à la fois.
        fun run(pas: Int): ShortArray {
            val banc = Banc()
            val bus = banc.machine.bus
            bus.write16(SOUNDCNT_H, DS_A or DS_B_TIMER1)
            bus.write16(TM_RELOAD + 4, RECHARGE_B)
            bus.write16(TM_CONTROL + 4, TIMER_ACTIF)
            banc.remplirFifo(canal = 0)
            banc.remplirFifo(canal = 1)
            banc.avancer(TOTAL, pas)
            return banc.pcm()
        }

        val reference = run(4)
        assertTrue(reference.any { it != 0.toShort() }, "Les deux canaux doivent produire du son")
        assertContentEquals(reference, run(TOTAL))
    }

    @Test
    fun `les deux canaux sur le meme timer restent entrelaces`() {
        // Un seul débordement dépile alors les deux FIFO : les deux valeurs
        // changent au même instant et doivent être vues ensemble.
        fun run(pas: Int): ShortArray {
            val banc = Banc()
            val bus = banc.machine.bus
            bus.write16(SOUNDCNT_H, DS_A or DS_B_TIMER0)
            banc.remplirFifo(canal = 0)
            banc.remplirFifo(canal = 1)
            banc.avancer(TOTAL, pas)
            return banc.pcm()
        }

        val reference = run(4)
        assertTrue(reference.any { it != 0.toShort() }, "Les deux canaux doivent produire du son")
        assertContentEquals(reference, run(TOTAL))
    }

    @Test
    fun `la remise a zero d'une FIFO coupe le son sans le figer`() {
        // Bit 11 de `SOUNDCNT_H` : vide la FIFO A et remet son échantillon
        // courant à zéro. La sortie doit retomber à zéro et y rester, sans
        // conserver la dernière valeur dépilée.
        val banc = Banc().apply { remplirFifo(); avancer(TOTAL, pas = 4) }
        assertTrue(banc.pcm().any { it != 0.toShort() }, "Le son doit d'abord exister")

        banc.remplirFifo()
        banc.machine.bus.write16(SOUNDCNT_H, DS_A or B_SUR_TIMER1)
        assertEquals(0, banc.apu.fifoSize(0), "La FIFO A doit être vide après remise à zéro")

        banc.avancer(TOTAL, pas = 4)
        assertTrue(
            banc.pcm().all { it == 0.toShort() },
            "Après remise à zéro, la sortie doit être muette",
        )
    }

    @Test
    fun `couper puis rallumer l'audio en cours de bloc ne depend pas du decoupage`() {
        fun run(pas: Int): ShortArray {
            val banc = Banc().apply { remplirFifo(mots = 8) }
            banc.avancer(TOTAL / 2, pas)
            banc.machine.bus.write16(SOUNDCNT_X, 0x0000) // coupure générale
            banc.avancer(TOTAL / 2, pas)
            banc.machine.bus.write16(SOUNDCNT_X, 0x0080) // reprise
            banc.remplirFifo(mots = 8)
            banc.avancer(TOTAL / 2, pas)
            return banc.pcm()
        }

        val reference = run(4)
        assertTrue(reference.any { it != 0.toShort() }, "La reprise doit redonner du son")
        assertContentEquals(reference, run(TOTAL / 2))
    }

    @Test
    fun `le seuil de reapprovisionnement demande un bloc par depilement, sans doublon`() {
        // Sous le quart de sa capacité, la FIFO réclame un bloc au DMA. Une
        // requête manquée affame le canal ; une requête en trop fait avancer le
        // pointeur de lecture du jeu deux fois plus vite. Les deux s'entendent.
        val banc = Banc()
        val requetes = mutableListOf<Int>()
        banc.apu.onFifoRequest = { canal -> requetes += canal }
        // Le canal B reste sur le timer 1 (voir [B_SUR_TIMER1]) : les
        // débordements provoqués ici ne concernent que la FIFO A.

        banc.remplirFifo(mots = 8) // 32 octets : FIFO pleine
        assertEquals(32, banc.apu.fifoSize(0))

        // Au-dessus du seuil : aucune demande. Les quinze premiers dépilements
        // ramènent la FIFO de 32 à 17 octets.
        repeat(15) { banc.apu.onTimerOverflow(0) }
        assertEquals(17, banc.apu.fifoSize(0))
        assertEquals(emptyList(), requetes, "Une FIFO bien remplie ne doit rien demander")

        // Le dépilement qui fait passer à 16 franchit le seuil.
        banc.apu.onTimerOverflow(0)
        assertEquals(16, banc.apu.fifoSize(0))
        assertEquals(listOf(0), requetes, "Le franchissement du seuil doit demander un bloc")

        // Sous le seuil, chaque dépilement redemande — une fois, pas deux.
        banc.apu.onTimerOverflow(0)
        assertEquals(15, banc.apu.fifoSize(0))
        assertEquals(listOf(0, 0), requetes)

        // Réapprovisionnement : quatre mots, soit seize octets, sans demande.
        requetes.clear()
        banc.remplirFifo(mots = 4)
        assertEquals(31, banc.apu.fifoSize(0))
        assertEquals(emptyList(), requetes, "Un remplissage ne demande rien de lui-même")

        // Repassée au-dessus du seuil, la FIFO se tait de nouveau.
        repeat(14) { banc.apu.onTimerOverflow(0) }
        assertEquals(17, banc.apu.fifoSize(0))
        assertEquals(emptyList(), requetes)
    }

    @Test
    fun `un etat restaure reprend un flux identique`() {
        // L'unité audio ne fait pas partie de l'état sauvegardé : ce sont les
        // timers qui portent la phase du flux. Une restauration doit rendre à
        // l'ordonnanceur une échéance saine — sans quoi la boucle bornée par
        // événement n'a plus de borne.
        val banc = Banc().apply { remplirFifo(); avancer(1024, pas = 4) }
        val etatTimers = banc.machine.timers.exportState()

        fun rejouer(pas: Int): ShortArray {
            val repris = Banc()
            repris.machine.timers.importState(etatTimers)
            assertTrue(
                repris.machine.timers.cyclesUntilNextOverflow() in 1..MAX_PERIODE,
                "Échéance incohérente après restauration",
            )
            repris.remplirFifo()
            repris.avancer(TOTAL, pas)
            return repris.pcm()
        }

        val reference = rejouer(4)
        assertTrue(reference.any { it != 0.toShort() }, "Le flux repris doit porter du son")
        assertContentEquals(reference, rejouer(TOTAL))
    }

    private companion object {
        /** 4096 cycles CPU : huit trames stéréo à 512 cycles l'échantillon. */
        const val TOTAL = 4096

        /** Période maximale d'un timer de prédiviseur 1, en cycles CPU. */
        const val MAX_PERIODE = 0x1_0000

        /**
         * Débordement tous les 96 cycles : plusieurs par échantillon audio, et
         * surtout une période non commensurable avec les 512 cycles qui séparent
         * deux échantillons. Une période de 64 cycles ferait retomber la grille
         * d'échantillonnage toujours sur la même moitié de l'alternance, et le
         * signal paraîtrait figé pour une raison de repliement, sans rapport
         * avec l'entrelacement qu'on cherche à vérifier.
         */
        const val RECHARGE = 0x1_0000 - 96

        /** Débordement tous les 512 cycles : exactement la période d'échantillon. */
        const val RECHARGE_ALIGNEE = 0x1_0000 - 512

        /** Période du second canal, distincte de [RECHARGE] et non alignée. */
        const val RECHARGE_B = 0x1_0000 - 176

        /** Bit 7 de `TMxCNT_H` : timer actif, prédiviseur 1:1. */
        const val TIMER_ACTIF = 0x0080

        /**
         * `SOUNDCNT_H`, canal Direct Sound A : volume plein (bit 2), sorties
         * droite et gauche (bits 8 et 9), timer 0 (bit 10 à zéro) et remise à
         * zéro de la FIFO (bit 11, effet unique à l'écriture).
         */
        const val DS_A = 0x0B04

        /** Canal B, mêmes réglages (bits 3, 12, 13, 15), cadencé par le timer 0. */
        const val DS_B_TIMER0 = 0xB008

        /** Canal B cadencé par le timer 1 (bit 14). */
        const val DS_B_TIMER1 = 0xF008

        /**
         * Bit 14 seul : le canal B, muet et inutilisé, est rattaché au timer 1.
         * Sans cela il partagerait le timer 0 du canal A et chaque débordement
         * dépilerait aussi sa FIFO vide — un bruit de fond dans les compteurs de
         * lecture à vide et de requêtes de réapprovisionnement.
         */
        const val B_SUR_TIMER1 = 0x4000

        const val SOUNDCNT_H = 0x0400_0082
        const val SOUNDCNT_X = 0x0400_0084
        const val FIFO_A = 0x0400_00A0
        const val FIFO_B = 0x0400_00A4
        const val TM_RELOAD = 0x0400_0100
        const val TM_CONTROL = 0x0400_0102

        const val MOT_POSITIF = 0x7F7F7F7F
        val MOT_NEGATIF = 0x81818181.toInt()

        const val TAMPON = 4096
    }
}
