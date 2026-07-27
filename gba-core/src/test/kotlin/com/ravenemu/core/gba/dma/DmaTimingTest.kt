package com.ravenemu.core.gba.dma

import com.ravenemu.core.gba.GbaMachine
import com.ravenemu.core.gba.SyntheticRom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Un transfert DMA prend le bus : il coûte des cycles et arrête le processeur.
 * Sans cela, un jeu qui recopie une trame entière par DMA paraît infiniment
 * rapide et tout son cadencement dérive.
 */
class DmaTimingTest {

    private fun machine() = GbaMachine(SyntheticRom.build())

    /** Arme le canal 0 : [count] mots de [source] vers [dest]. */
    private fun armChannel0(
        m: GbaMachine,
        source: Int,
        dest: Int,
        count: Int,
        word32: Boolean = true,
        control: Int = 0,
    ) {
        m.bus.write32(0x0400_00B0, source)
        m.bus.write32(0x0400_00B4, dest)
        m.bus.write16(0x0400_00B8, count)
        val width = if (word32) 0x0400 else 0
        m.bus.write16(0x0400_00BA, 0x8000 or width or control)
    }

    @Test
    fun `un transfert immediat consomme des cycles`() {
        val m = machine()
        assertFalse(m.dma.isActive)
        armChannel0(m, source = 0x0300_0000, dest = 0x0300_1000, count = 4)
        assertTrue(m.dma.isActive, "le contrôleur doit retenir le bus")
        // IWRAM : un cycle par accès. Prise du bus (2) + 4 mots x 2 accès.
        assertEquals(2 + 4 * 2, m.dma.pendingCycles)
        assertEquals(2 + 4 * 2, m.dma.takePendingCycles())
        assertEquals(0, m.dma.pendingCycles, "les cycles ne sont facturés qu'une fois")
        assertFalse(m.dma.isActive)
    }

    @Test
    fun `le cout depend des regions source et destination`() {
        val iwram = machine()
        armChannel0(iwram, source = 0x0300_0000, dest = 0x0300_1000, count = 8)
        val iwramCost = iwram.dma.takePendingCycles()

        val ewram = machine()
        armChannel0(ewram, source = 0x0200_0000, dest = 0x0200_1000, count = 8)
        val ewramCost = ewram.dma.takePendingCycles()

        // EWRAM : six cycles par accès de mot au lieu d'un seul.
        assertEquals(2 + 8 * 2, iwramCost)
        assertEquals(2 + 8 * 2 * 6, ewramCost)
        assertTrue(ewramCost > iwramCost)
    }

    @Test
    fun `les mots suivant le premier sont facturés en acces sequentiel`() {
        val m = machine()
        // Source en ROM zone 0, WAITCNT par défaut : premier mot 8 cycles, mots
        // suivants 6. Destination en IWRAM : un cycle dans tous les cas.
        armChannel0(m, source = 0x0800_0000, dest = 0x0300_0000, count = 4)
        val expected = 2 + (8 + 1) + 3 * (6 + 1)
        assertEquals(expected, m.dma.takePendingCycles())
    }

    @Test
    fun `WAITCNT reduit le cout d'un transfert depuis la cartouche`() {
        val m = machine()
        m.bus.write16(0x0400_0204, 0x0008 or 0x0010) // zone 0 au plus rapide
        armChannel0(m, source = 0x0800_0000, dest = 0x0300_0000, count = 4)
        // Mot non séquentiel : 2 + 1 + 1 = 4 cycles ; séquentiel : 1 + 1 + 1 = 3.
        assertEquals(2 + (5 + 1) + 3 * (4 + 1), m.dma.takePendingCycles())
    }

    @Test
    fun `un transfert 16 bits coute moins cher qu'un transfert 32 bits`() {
        val half = machine()
        armChannel0(half, 0x0200_0000, 0x0200_1000, count = 4, word32 = false)
        val halfCost = half.dma.takePendingCycles()

        val word = machine()
        armChannel0(word, 0x0200_0000, 0x0200_1000, count = 4, word32 = true)
        val wordCost = word.dma.takePendingCycles()

        assertEquals(2 + 4 * 2 * 3, halfCost, "EWRAM 16 bits : trois cycles par accès")
        assertEquals(2 + 4 * 2 * 6, wordCost, "EWRAM 32 bits : six cycles par accès")
    }

    @Test
    fun `le processeur est arrete pendant le transfert`() {
        // Programme : arme un DMA immédiat puis écrit un témoin. Sur une trame
        // entière, le témoin est écrit, mais le temps du DMA a bien été consommé
        // par les périphériques.
        val m = machine()
        // Un gros transfert en EWRAM, coûteux en cycles.
        armChannel0(m, source = 0x0200_0000, dest = 0x0202_0000, count = 4096)
        val cost = m.dma.pendingCycles
        assertTrue(cost > 10_000, "un transfert de 4096 mots doit coûter cher : $cost")

        val vcountBefore = m.ppu.vcount
        m.runFrame(cost) // exactement le temps du DMA
        assertEquals(0, m.dma.pendingCycles, "les cycles doivent être écoulés")
        assertTrue(
            m.ppu.vcount != vcountBefore,
            "l'affichage doit avoir avancé pendant que le CPU était arrêté",
        )
        assertEquals(
            0x0800_0000,
            m.cpu.state.regs[15],
            "le processeur n'a pas exécuté d'instruction",
        )
    }

    @Test
    fun `un transfert VBlank reste declenche par l'affichage`() {
        val m = machine()
        m.bus.write32(0x0300_0100, 0x1234_5678)
        m.bus.write32(0x0400_00B0, 0x0300_0100)
        m.bus.write32(0x0400_00B4, 0x0300_0200)
        m.bus.write16(0x0400_00B8, 1)
        m.bus.write16(0x0400_00BA, 0x8000 or 0x0400 or 0x1000) // VBlank
        m.dma.takePendingCycles()
        assertEquals(0, m.bus.read32(0x0300_0200))

        m.ppu.tick(161 * 1232) // franchit le début du VBlank
        assertEquals(0x1234_5678, m.bus.read32(0x0300_0200))
        assertTrue(m.dma.pendingCycles > 0, "le transfert VBlank coûte aussi des cycles")
    }

    @Test
    fun `un transfert HBlank reste declenche par l'affichage`() {
        val m = machine()
        m.bus.write32(0x0300_0100, 0x0BAD_F00D)
        m.bus.write32(0x0400_00B0, 0x0300_0100)
        m.bus.write32(0x0400_00B4, 0x0300_0200)
        m.bus.write16(0x0400_00B8, 1)
        m.bus.write16(0x0400_00BA, 0x8000 or 0x0400 or 0x2000) // HBlank
        m.dma.takePendingCycles()

        m.ppu.tick(1000) // dépasse le début du HBlank de la ligne 0
        assertEquals(0x0BAD_F00D, m.bus.read32(0x0300_0200))
        assertTrue(m.dma.pendingCycles > 0)
    }

    @Test
    fun `la repetition conserve le canal arme et recharge la destination`() {
        val m = machine()
        for (i in 0 until 4) m.bus.write32(0x0300_0100 + i * 4, 0x1000 + i)
        m.bus.write32(0x0400_00B0, 0x0300_0100)
        m.bus.write32(0x0400_00B4, 0x0300_0200)
        m.bus.write16(0x0400_00B8, 2)
        // Répétition, VBlank, 32 bits, destination en incrément + rechargement.
        m.bus.write16(0x0400_00BA, 0x8000 or 0x0400 or 0x1000 or 0x0200 or (3 shl 5))
        m.dma.takePendingCycles()

        m.ppu.tick(161 * 1232) // premier VBlank
        assertEquals(0x1000, m.bus.read32(0x0300_0200))
        assertEquals(0x1001, m.bus.read32(0x0300_0204))
        assertTrue(
            m.bus.read16(0x0400_00BA) and 0x8000 != 0,
            "le canal reste armé pour la répétition",
        )
        m.dma.takePendingCycles()

        m.ppu.tick(228 * 1232) // VBlank suivant
        // La source a progressé, la destination est revenue à son point de départ.
        assertEquals(0x1002, m.bus.read32(0x0300_0200))
        assertEquals(0x1003, m.bus.read32(0x0300_0204))
    }

    @Test
    fun `un transfert immediat desarme le canal`() {
        val m = machine()
        armChannel0(m, 0x0300_0000, 0x0300_1000, count = 1)
        assertEquals(
            0,
            m.bus.read16(0x0400_00BA) and 0x8000,
            "un transfert immédiat ne se répète pas",
        )
    }

    @Test
    fun `le reapprovisionnement d'une FIFO coute des cycles`() {
        val m = machine()
        for (i in 0 until 4) m.bus.write32(0x0200_0000 + i * 4, 0x4040_4040)
        m.bus.write32(0x0400_00BC, 0x0200_0000)
        m.bus.write32(0x0400_00C0, 0x0400_00A0)
        m.bus.write16(0x0400_00C4, 4)
        m.bus.write16(0x0400_00C6, 0x8000 or 0x3000 or 0x0400) // mode spécial
        m.dma.takePendingCycles()

        m.dma.triggerSoundFifo(0)
        assertEquals(16, m.apu.fifoSize(0), "quatre mots transférés")
        // Source en EWRAM (6 cycles par mot), destination E/S (1 cycle).
        assertEquals(2 + 4 * (6 + 1), m.dma.takePendingCycles())
    }

    @Test
    fun `les cycles dus survivent a un aller-retour d'etat instantane`() {
        val core = com.ravenemu.core.gba.GbaCore()
        core.loadRom(SyntheticRom.build())
        val m = core.machine!!
        armChannel0(m, 0x0200_0000, 0x0200_1000, count = 16)
        val expected = m.dma.pendingCycles
        assertTrue(expected > 0)

        val state = core.saveState()
        m.dma.takePendingCycles()
        assertEquals(0, m.dma.pendingCycles)

        core.loadState(state)
        // La restauration remplace la machine active.
        assertEquals(expected, core.machine!!.dma.pendingCycles)
    }
}
