package com.ravenemu.core.gba.dma

import com.ravenemu.core.gba.GbaMachine
import com.ravenemu.core.gba.KotlinGbaCore
import com.ravenemu.core.gba.SyntheticRom
import com.ravenemu.core.gba.interrupt.Interrupt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Vérifie le coût, la progression et les déclenchements des quatre canaux DMA. */
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

    /** Vérifie que le transfert se termine exactement au dernier cycle attendu. */
    private fun assertDuration(m: GbaMachine, cycles: Int) {
        val pc = m.cpu.state.regs[15]
        m.runFrame(cycles - 1)
        assertTrue(m.dma.isActive, "le DMA s'est terminé avant $cycles cycles")
        m.runFrame(1)
        assertFalse(m.dma.isActive, "le DMA dépasse $cycles cycles")
        assertEquals(pc, m.cpu.state.regs[15], "le CPU a avancé pendant le DMA")
    }

    @Test
    fun `un transfert immediat consomme des cycles`() {
        val m = machine()
        assertFalse(m.dma.isActive)
        armChannel0(m, source = 0x0300_0000, dest = 0x0300_1000, count = 4)
        assertTrue(m.dma.isActive, "le contrôleur doit retenir le bus")
        // IWRAM : prise du bus (2) + quatre mots × lecture/écriture d'un cycle.
        assertDuration(m, 2 + 4 * 2)
    }

    @Test
    fun `le cout depend des regions source et destination`() {
        val iwram = machine()
        armChannel0(iwram, source = 0x0300_0000, dest = 0x0300_1000, count = 8)
        assertDuration(iwram, 2 + 8 * 2)

        val ewram = machine()
        armChannel0(ewram, source = 0x0200_0000, dest = 0x0200_1000, count = 8)
        // EWRAM : six cycles par accès de mot au lieu d'un seul.
        assertDuration(ewram, 2 + 8 * 2 * 6)
    }

    @Test
    fun `les mots suivant le premier sont factures en acces sequentiel`() {
        val m = machine()
        // ROM zone 0 : premier mot 8 cycles, suivants 6. IWRAM : un cycle.
        armChannel0(m, source = 0x0800_0000, dest = 0x0300_0000, count = 4)
        assertDuration(m, 2 + (8 + 1) + 3 * (6 + 1))
    }

    @Test
    fun `WAITCNT reduit le cout d un transfert depuis la cartouche`() {
        val m = machine()
        m.bus.write16(0x0400_0204, 0x0008 or 0x0010)
        armChannel0(m, source = 0x0800_0000, dest = 0x0300_0000, count = 4)
        assertDuration(m, 2 + (5 + 1) + 3 * (4 + 1))
    }

    @Test
    fun `un transfert 16 bits coute moins cher qu un transfert 32 bits`() {
        val half = machine()
        armChannel0(half, 0x0200_0000, 0x0200_1000, count = 4, word32 = false)
        assertDuration(half, 2 + 4 * 2 * 3)

        val word = machine()
        armChannel0(word, 0x0200_0000, 0x0200_1000, count = 4, word32 = true)
        assertDuration(word, 2 + 4 * 2 * 6)
    }

    @Test
    fun `les effets et l interruption suivent les cycles ecoules`() {
        val m = machine()
        val source = 0x0300_0100
        val dest = 0x0300_0200
        m.bus.write32(source, 0x1234_5678)
        m.bus.write32(source + 4, 0x0BAD_F00D)
        armChannel0(m, source, dest, count = 2, control = 0x4000)

        assertEquals(0, m.bus.read32(dest), "le premier mot est visible au déclenchement")
        assertEquals(0, m.interrupts.flags and (1 shl Interrupt.DMA0))
        assertTrue(m.bus.read16(0x0400_00BA) and 0x8000 != 0)

        m.runFrame(4) // prise du bus, lecture et écriture du premier mot
        assertEquals(0x1234_5678, m.bus.read32(dest))
        assertEquals(0, m.bus.read32(dest + 4), "le second mot est visible trop tôt")
        assertTrue(m.dma.isActive)
        assertEquals(0, m.interrupts.flags and (1 shl Interrupt.DMA0))

        m.runFrame(2)
        assertEquals(0x0BAD_F00D, m.bus.read32(dest + 4))
        assertFalse(m.dma.isActive)
        assertTrue(m.interrupts.flags and (1 shl Interrupt.DMA0) != 0)
        assertEquals(0, m.bus.read16(0x0400_00BA) and 0x8000)
    }

    @Test
    fun `le processeur est arrete pendant le transfert`() {
        val m = machine()
        armChannel0(m, source = 0x0200_0000, dest = 0x0202_0000, count = 4096)
        val cost = 2 + 4096 * 2 * 6
        val vcountBefore = m.ppu.vcount
        val pcBefore = m.cpu.state.regs[15]

        m.runFrame(cost)

        assertFalse(m.dma.isActive)
        assertTrue(m.ppu.vcount != vcountBefore, "l'affichage doit avancer pendant le DMA")
        assertEquals(pcBefore, m.cpu.state.regs[15], "le CPU a exécuté une instruction")
    }

    @Test
    fun `un transfert VBlank reste declenche par l affichage`() {
        val m = machine()
        m.bus.write32(0x0300_0100, 0x1234_5678)
        m.bus.write32(0x0400_00B0, 0x0300_0100)
        m.bus.write32(0x0400_00B4, 0x0300_0200)
        m.bus.write16(0x0400_00B8, 1)
        m.bus.write16(0x0400_00BA, 0x8000 or 0x0400 or 0x1000)
        assertFalse(m.dma.isActive)

        m.ppu.tick(161 * 1232)
        assertTrue(m.dma.isActive)
        assertEquals(0, m.bus.read32(0x0300_0200), "la copie VBlank est visible trop tôt")
        m.runFrame(4)
        assertEquals(0x1234_5678, m.bus.read32(0x0300_0200))
    }

    @Test
    fun `un transfert HBlank reste declenche par l affichage`() {
        val m = machine()
        m.bus.write32(0x0300_0100, 0x0BAD_F00D)
        m.bus.write32(0x0400_00B0, 0x0300_0100)
        m.bus.write32(0x0400_00B4, 0x0300_0200)
        m.bus.write16(0x0400_00B8, 1)
        m.bus.write16(0x0400_00BA, 0x8000 or 0x0400 or 0x2000)

        m.ppu.tick(1000)
        assertTrue(m.dma.isActive)
        assertEquals(0, m.bus.read32(0x0300_0200), "la copie HBlank est visible trop tôt")
        m.runFrame(4)
        assertEquals(0x0BAD_F00D, m.bus.read32(0x0300_0200))
    }

    @Test
    fun `la repetition conserve le canal arme et recharge la destination`() {
        val m = machine()
        for (i in 0 until 4) m.bus.write32(0x0300_0100 + i * 4, 0x1000 + i)
        m.bus.write32(0x0400_00B0, 0x0300_0100)
        m.bus.write32(0x0400_00B4, 0x0300_0200)
        m.bus.write16(0x0400_00B8, 2)
        m.bus.write16(0x0400_00BA, 0x8000 or 0x0400 or 0x1000 or 0x0200 or (3 shl 5))

        m.ppu.tick(161 * 1232)
        m.runFrame(6)
        assertEquals(0x1000, m.bus.read32(0x0300_0200))
        assertEquals(0x1001, m.bus.read32(0x0300_0204))
        assertTrue(m.bus.read16(0x0400_00BA) and 0x8000 != 0)

        m.ppu.tick(228 * 1232)
        m.runFrame(6)
        assertEquals(0x1002, m.bus.read32(0x0300_0200))
        assertEquals(0x1003, m.bus.read32(0x0300_0204))
    }

    @Test
    fun `un transfert immediat se desarme seulement a la fin`() {
        val m = machine()
        armChannel0(m, 0x0300_0000, 0x0300_1000, count = 1)
        assertTrue(m.bus.read16(0x0400_00BA) and 0x8000 != 0)
        m.runFrame(4)
        assertEquals(0, m.bus.read16(0x0400_00BA) and 0x8000)
    }

    @Test
    fun `le reapprovisionnement d une FIFO progresse mot par mot`() {
        val m = machine()
        for (i in 0 until 4) m.bus.write32(0x0200_0000 + i * 4, 0x4040_4040)
        m.bus.write32(0x0400_00BC, 0x0200_0000)
        m.bus.write32(0x0400_00C0, 0x0400_00A0)
        m.bus.write16(0x0400_00C4, 4)
        m.bus.write16(0x0400_00C6, 0x8000 or 0x3000 or 0x0400)

        m.dma.triggerSoundFifo(0)
        assertEquals(0, m.apu.fifoSize(0), "la FIFO a été remplie au déclenchement")
        m.runFrame(29)
        assertEquals(12, m.apu.fifoSize(0), "seuls trois mots doivent être écrits")
        assertTrue(m.dma.isActive)
        m.runFrame(1)
        assertEquals(16, m.apu.fifoSize(0))
        assertFalse(m.dma.isActive)
        assertTrue(m.bus.read16(0x0400_00C6) and 0x8000 != 0, "le canal FIFO doit rester armé")
    }

    @Test
    fun `une requete DMA moins prioritaire attend la fin du canal actif`() {
        val m = machine()
        m.bus.write32(0x0300_0100, 0x1111_1111)
        m.bus.write32(0x0300_0110, 0x3333_3333)

        armChannel0(m, 0x0300_0100, 0x0300_0200, count = 1)
        m.bus.write32(0x0400_00D4, 0x0300_0110)
        m.bus.write32(0x0400_00D8, 0x0300_0210)
        m.bus.write16(0x0400_00DC, 1)
        m.bus.write16(0x0400_00DE, 0x8400)

        m.dma.importState(m.dma.exportState())
        m.runFrame(4)
        assertEquals(0x1111_1111, m.bus.read32(0x0300_0200))
        assertEquals(0, m.bus.read32(0x0300_0210))
        assertTrue(m.dma.isActive)

        m.runFrame(4)
        assertEquals(0x3333_3333, m.bus.read32(0x0300_0210))
        assertFalse(m.dma.isActive)
    }

    @Test
    fun `un DMA son prioritaire suspend puis restitue le bus`() {
        val m = machine()
        for (i in 0 until 4) m.bus.write32(0x0200_0000 + i * 4, 0x4040_4040)
        m.bus.write32(0x0300_0100, 0x3333_3333)

        m.bus.write32(0x0400_00BC, 0x0200_0000)
        m.bus.write32(0x0400_00C0, 0x0400_00A0)
        m.bus.write16(0x0400_00C4, 4)
        m.bus.write16(0x0400_00C6, 0xB400)

        m.bus.write32(0x0400_00D4, 0x0300_0100)
        m.bus.write32(0x0400_00D8, 0x0300_0200)
        m.bus.write16(0x0400_00DC, 1)
        m.bus.write16(0x0400_00DE, 0x8400)
        m.dma.triggerSoundFifo(0)

        m.runFrame(2)
        m.dma.importState(m.dma.exportState())
        m.runFrame(30)

        assertEquals(16, m.apu.fifoSize(0))
        assertEquals(0, m.bus.read32(0x0300_0200))
        assertTrue(m.dma.isActive, "le transfert suspendu doit reprendre")

        m.runFrame(2)
        assertEquals(0x3333_3333, m.bus.read32(0x0300_0200))
        assertFalse(m.dma.isActive)
    }

    @Test
    fun `une lecture DMA en cours survit a l etat instantane`() {
        val core = KotlinGbaCore()
        core.loadRom(SyntheticRom.build())
        val m = core.machine!!
        val source = 0x0300_0100
        val dest = 0x0300_0200
        m.bus.write32(source, 0x1234_5678)
        armChannel0(m, source, dest, count = 1, control = 0x4000)
        m.runFrame(3) // prise du bus et lecture, écriture encore en attente
        assertEquals(0, m.bus.read32(dest))

        val state = core.saveState()
        core.loadState(state)
        val restored = core.machine!!
        restored.bus.write32(source, 0)
        restored.runFrame(1)

        assertEquals(0x1234_5678, restored.bus.read32(dest))
        assertFalse(restored.dma.isActive)
        assertTrue(restored.interrupts.flags and (1 shl Interrupt.DMA0) != 0)
    }
}
