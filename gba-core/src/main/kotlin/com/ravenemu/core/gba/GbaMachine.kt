package com.ravenemu.core.gba

import com.ravenemu.core.gba.cartridge.GbaCartridge
import com.ravenemu.core.gba.cpu.Arm7Tdmi
import com.ravenemu.core.gba.dma.DmaController
import com.ravenemu.core.gba.interrupt.GbaInterruptController
import com.ravenemu.core.gba.memory.GbaBus
import com.ravenemu.core.gba.ppu.GbaPpu
import com.ravenemu.core.gba.timer.GbaTimers

/**
 * Machine Game Boy Advance complète, reconstruite à chaque chargement de ROM :
 * cartouche, bus mémoire, CPU ARM7TDMI, unité graphique, contrôleur
 * d'interruptions, timers et DMA.
 *
 * Le CPU démarre au point d'entrée fixe de la cartouche (`0x0800_0000`), à
 * l'état laissé par le BIOS (mode système, exécution ARM), sans nécessiter de
 * BIOS Nintendo.
 */
class GbaMachine(rom: ByteArray) {

    val cartridge: GbaCartridge = GbaCartridge.create(rom)
    val bus: GbaBus = GbaBus(cartridge)
    val ppu: GbaPpu = GbaPpu(bus)
    val interrupts: GbaInterruptController = GbaInterruptController()
    val timers: GbaTimers = GbaTimers(interrupts)
    val dma: DmaController = DmaController(bus, interrupts)
    val cpu: Arm7Tdmi = Arm7Tdmi(bus)

    init {
        bus.ppu = ppu
        bus.interrupts = interrupts
        bus.timers = timers
        bus.dma = dma
        ppu.interrupts = interrupts
        ppu.dma = dma
        cpu.reset(ROM_ENTRY_POINT)
    }

    /**
     * Exécute [cycles] cycles CPU en faisant avancer l'affichage et les timers à
     * la même cadence. Avant chaque instruction, une interruption en attente et
     * autorisée (drapeau `I` du CPU dégagé) provoque l'exception IRQ (vecteur
     * `0x18`, traité par le BIOS/HLE).
     */
    fun runFrame(cycles: Int) {
        var elapsed = 0
        while (elapsed < cycles) {
            if (interrupts.pending() && !cpu.state.irqDisabled) {
                cpu.raiseException(
                    com.ravenemu.core.gba.cpu.CpuState.MODE_IRQ,
                    Arm7Tdmi.VECTOR_IRQ,
                    cpu.state.regs[15] + 4,
                )
            }
            val consumed = cpu.step()
            ppu.tick(consumed)
            timers.tick(consumed)
            elapsed += consumed
        }
    }

    companion object {
        /** Adresse du premier octet de la ROM cartouche, où le CPU démarre. */
        const val ROM_ENTRY_POINT = 0x0800_0000
    }
}
