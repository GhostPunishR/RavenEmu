package com.ravenemu.core.gba.state

import com.ravenemu.core.gba.KotlinGbaCore
import com.ravenemu.core.gba.SyntheticRom
import com.ravenemu.emulation.api.SaveStateException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame

class GbaStateTest {

    private fun loadedCore(): KotlinGbaCore {
        val core = KotlinGbaCore()
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
        // La restauration remplace la machine : c'est la nouvelle qu'il faut
        // interroger, l'ancienne ayant été abandonnée avec ses valeurs altérées.
        val restored = core.machine!!
        assertEquals(0x1234_5678, restored.bus.read32(0x0200_0000))
        assertEquals(0xABCD_EF01.toInt(), restored.cpu.state.regs[5])
    }

    @Test
    fun `sauvegarde puis restauration retablit le PPU et les peripheriques`() {
        val core = loadedCore()
        val machine = core.machine!!
        machine.ppu.tick(1_000)
        machine.interrupts.enable = 0x1234
        machine.interrupts.flags = 0x0055
        machine.interrupts.masterEnable = true
        machine.timers.onReloadWrite(0, 0xFFFC)
        machine.timers.onControlWrite(0, 0x0080)
        machine.timers.tick(2)
        val state = core.saveState()

        machine.ppu.tick(50_000)
        machine.ppu.frame.fill(0x1234_5678)
        machine.interrupts.enable = 0
        machine.interrupts.flags = 0
        machine.interrupts.masterEnable = false
        machine.timers.reset()
        core.loadState(state)

        assertContentEquals(state, core.saveState())
    }

    @Test
    fun `une restauration valide remplace la machine active`() {
        val core = loadedCore()
        val avant = core.machine!!
        core.loadState(core.saveState())
        assertNotSame(avant, core.machine, "La machine restaurée doit être la nouvelle instance")
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

        val other = KotlinGbaCore()
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
