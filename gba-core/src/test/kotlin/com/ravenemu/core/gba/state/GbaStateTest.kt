package com.ravenemu.core.gba.state

import com.ravenemu.core.gba.GbaCore
import com.ravenemu.core.gba.SyntheticRom
import com.ravenemu.emulation.api.SaveStateException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GbaStateTest {

    private fun loadedCore(): GbaCore {
        val core = GbaCore()
        core.loadRom(SyntheticRom.build(programWords = SyntheticRom.backdropProgram(0x1F)))
        return core
    }

    @Test
    fun `sauvegarde puis restauration retablit registres et memoire`() {
        val core = loadedCore()
        val machine = core.machine!!
        machine.bus.write32(0x0200_0000, 0x1234_5678)
        machine.cpu.state.regs[5] = 0xABCD_EF01.toInt()

        val state = core.saveState()

        // Altère l'état après la sauvegarde.
        machine.bus.write32(0x0200_0000, 0)
        machine.cpu.state.regs[5] = 0

        core.loadState(state)
        assertEquals(0x1234_5678, machine.bus.read32(0x0200_0000))
        assertEquals(0xABCD_EF01.toInt(), machine.cpu.state.regs[5])
    }

    @Test
    fun `sauvegarde puis restauration retablit le PPU complet`() {
        val core = loadedCore()
        val machine = core.machine!!
        machine.ppu.tick(1_000)
        val state = core.saveState()

        machine.ppu.tick(50_000)
        machine.ppu.frame.fill(0x1234_5678)
        core.loadState(state)

        assertContentEquals(state, core.saveState())
    }

    @Test
    fun `un etat tronque est rejete`() {
        val core = loadedCore()
        val state = core.saveState()
        assertFailsWith<SaveStateException> { core.loadState(state.copyOf(state.size / 2)) }
    }

    @Test
    fun `un magic invalide est rejete`() {
        val core = loadedCore()
        val state = core.saveState()
        state[0] = 0x00
        assertFailsWith<SaveStateException> { core.loadState(state) }
    }

    @Test
    fun `un etat issu d'une autre ROM est rejete`() {
        val core = loadedCore()
        val state = core.saveState()

        val other = GbaCore()
        other.loadRom(
            SyntheticRom.build(
                title = "AUTRE",
                programWords = SyntheticRom.backdropProgram(0x1F),
            )
        )
        assertFailsWith<SaveStateException> { other.loadState(state) }
    }

    @Test
    fun `un etat trop volumineux est rejete`() {
        val core = loadedCore()
        val tooBig = ByteArray((1 shl 20) + 1)
        assertFailsWith<SaveStateException> { core.loadState(tooBig) }
    }
}
