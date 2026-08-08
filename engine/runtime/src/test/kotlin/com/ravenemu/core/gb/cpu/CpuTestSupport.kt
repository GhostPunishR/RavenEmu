package com.ravenemu.core.gb.cpu

import com.ravenemu.core.gb.InterruptController
import com.ravenemu.core.gb.memory.Bus

/** Mémoire plate 64 KiB pour tester le CPU sans machine complète. */
class FlatBus : Bus {
    val memory = IntArray(0x10000)

    override fun read(address: Int): Int = memory[address and 0xFFFF]

    override fun write(address: Int, value: Int) {
        memory[address and 0xFFFF] = value and 0xFF
    }
}

/** Banc d'essai : CPU + bus plat + contrôleur d'interruptions. */
class CpuHarness {
    val bus = FlatBus()
    val interrupts = InterruptController().apply { interruptFlags = 0 }
    val cpu = Cpu(bus, interrupts)

    /** Charge un programme à 0x0100 (PC initial) et retourne le banc. */
    fun program(vararg bytes: Int): CpuHarness {
        for ((i, value) in bytes.withIndex()) {
            bus.memory[0x0100 + i] = value and 0xFF
        }
        return this
    }

    /** Exécute [count] instructions et retourne le total de T-cycles. */
    fun run(count: Int = 1): Int {
        var cycles = 0
        repeat(count) { cycles += cpu.step() }
        return cycles
    }
}
