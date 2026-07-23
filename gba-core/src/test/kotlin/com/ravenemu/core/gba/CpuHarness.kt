package com.ravenemu.core.gba

import com.ravenemu.core.gba.cartridge.GbaCartridge
import com.ravenemu.core.gba.cpu.Arm7Tdmi
import com.ravenemu.core.gba.memory.GbaBus

/**
 * Banc d'essai du CPU pour les tests d'instructions : monte un [Arm7Tdmi] sur un
 * bus réel, charge des instructions en IWRAM (mémoire inscriptible) et les
 * exécute. Les instructions sont fournies déjà encodées (mots ARM / demi-mots
 * Thumb), afin que les tests soient indépendants de la logique de décodage.
 */
class CpuHarness {
    private val bus = GbaBus(GbaCartridge.create(SyntheticRom.build()))
    val cpu = Arm7Tdmi(bus)

    init {
        cpu.reset(IWRAM_BASE)
    }

    /** Charge des instructions ARM en IWRAM et positionne le PC dessus. */
    fun loadArm(vararg words: Int, at: Int = IWRAM_BASE) {
        words.forEachIndexed { i, w -> bus.write32(at + i * 4, w) }
        cpu.state.regs[15] = at
        cpu.state.thumb = false
    }

    /** Charge des instructions Thumb en IWRAM et positionne le PC dessus. */
    fun loadThumb(vararg halfwords: Int, at: Int = IWRAM_BASE) {
        halfwords.forEachIndexed { i, h -> bus.write16(at + i * 2, h) }
        cpu.state.regs[15] = at
        cpu.state.thumb = true
    }

    fun step(count: Int = 1) {
        repeat(count) { cpu.step() }
    }

    fun reg(index: Int): Int = cpu.state.regs[index]

    fun setReg(index: Int, value: Int) {
        cpu.state.regs[index] = value
    }

    fun writeWord(address: Int, value: Int) = bus.write32(address, value)

    fun readWord(address: Int): Int = bus.read32(address)

    companion object {
        const val IWRAM_BASE = 0x0300_0000
    }
}
