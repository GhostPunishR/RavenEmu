package com.ravenemu.core.gba

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GbaFrameTimingTest {

    @Test
    fun `le depassement de cycles ne s accumule pas entre les trames`() {
        // Cette ROM n'utilise ni DMA ni pause : seule une instruction peut
        // dépasser la frontière, avec un coût très inférieur à cette borne.
        val maximumSingleStepOvershoot = 64
        val machine = GbaMachine(SyntheticRom.build())

        repeat(120) { frame ->
            machine.runFrame()
            val ppuState = machine.ppu.stateFields()
            assertEquals(0, ppuState[0], "trame ${frame + 1} au-delà de sa frontière")
            assertTrue(
                ppuState[1] in 0 until maximumSingleStepOvershoot,
                "dépassement accumulé à la trame ${frame + 1} : ${ppuState[1]} cycles",
            )
        }
    }

    @Test
    fun `un DMA long s arrete a la frontiere de trame`() {
        val machine = GbaMachine(SyntheticRom.build())
        val cyclesToFrame = machine.ppu.cyclesUntilNextFrame()
        val pcBefore = machine.cpu.state.regs[15]

        machine.bus.write32(0x0400_00D4, 0x0200_0000)
        machine.bus.write32(0x0400_00D8, 0x0202_0000)
        machine.bus.write16(0x0400_00DC, 32_768)
        machine.bus.write16(0x0400_00DE, 0x8400) // canal 3, immédiat, 32 bits

        val pendingBefore = machine.dma.pendingCycles
        assertTrue(pendingBefore > cyclesToFrame, "le DMA de test doit dépasser une trame")

        machine.runFrame()

        val ppuState = machine.ppu.stateFields()
        assertEquals(0, ppuState[0], "le DMA a dépassé la frontière de trame")
        assertEquals(0, ppuState[1], "le DMA a dépassé la frontière de trame")
        assertEquals(
            pendingBefore - cyclesToFrame,
            machine.dma.pendingCycles,
            "les cycles DMA restants doivent être conservés",
        )
        assertEquals(pcBefore, machine.cpu.state.regs[15], "le CPU a repris le bus avant la fin du DMA")
    }
}
