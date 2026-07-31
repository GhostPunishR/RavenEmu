package com.ravenemu.core.gba

import com.ravenemu.core.gba.audio.GbaApu
import com.ravenemu.core.gba.bios.GbaBios
import com.ravenemu.core.gba.cartridge.GbaCartridge
import com.ravenemu.core.gba.cpu.Arm7Tdmi
import com.ravenemu.core.gba.dma.DmaController
import com.ravenemu.core.gba.interrupt.GbaInterruptController
import com.ravenemu.core.gba.memory.GbaBus
import com.ravenemu.core.gba.ppu.GbaPpu
import com.ravenemu.core.gba.save.GbaSaveType
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
class GbaMachine(rom: ByteArray, forcedSaveType: GbaSaveType? = null) {

    val cartridge: GbaCartridge = GbaCartridge.create(rom, forcedSaveType)
    val bus: GbaBus = GbaBus(cartridge)
    val ppu: GbaPpu = GbaPpu(bus)
    val interrupts: GbaInterruptController = GbaInterruptController()
    val timers: GbaTimers = GbaTimers(interrupts)
    val dma: DmaController = DmaController(bus, interrupts)
    val apu: GbaApu = GbaApu()
    val cpu: Arm7Tdmi = Arm7Tdmi(bus)
    val bios: GbaBios = GbaBios(cpu, bus)

    /** Compteurs et signalements du moteur (surcouche de débogage, bancs d'essai). */
    val diagnostics: com.ravenemu.core.gba.diag.GbaDiagnostics get() = bus.diagnostics

    init {
        bus.ppu = ppu
        bus.interrupts = interrupts
        bus.timers = timers
        bus.dma = dma
        bus.apu = apu
        ppu.interrupts = interrupts
        ppu.dma = dma
        // Les timers cadencent les canaux Direct Sound, qui réclament à leur
        // tour leur réapprovisionnement au DMA.
        timers.onOverflow = apu::onTimerOverflow
        apu.onFifoRequest = dma::triggerSoundFifo
        cpu.swiHandler = bios
        // Le gestionnaire du BIOS mémorise les sources survenues : c'est ce que
        // consultent IntrWait et VBlankIntrWait.
        interrupts.onRequest = { mask ->
            bios.onInterruptRaised(mask)
            diagnostics.onInterrupt(mask)
        }
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
        diagnostics.beginFrame()
        while (elapsed < cycles) {
            // Le DMA est prioritaire sur le processeur : tant qu'il occupe le
            // bus, seuls les périphériques avancent.
            val dmaCycles = dma.takePendingCycles()
            if (dmaCycles > 0) {
                advancePeripherals(dmaCycles)
                elapsed += dmaCycles
                continue
            }
            // CPU en pause (SWI Halt/IntrWait) : on avance les périphériques
            // jusqu'à ce qu'une interruption soit levée, puis on réveille.
            if (cpu.state.halted) {
                advancePeripherals(HALT_STEP)
                elapsed += HALT_STEP
                // Une attente qui s'éternise signale une interruption qui ne
                // vient pas : c'est le symptôme d'un jeu figé.
                diagnostics.onWaitStep(HALT_STEP, bios.waitState?.interruptMask ?: 0)
                if (bios.shouldResume()) {
                    cpu.state.halted = false
                    diagnostics.onWaitResolved()
                    // Le processeur reprend le bus après une pause.
                    bus.breakAccessSequence()
                }
                continue
            }
            if (interrupts.pending() && !cpu.state.irqDisabled) {
                cpu.raiseException(
                    com.ravenemu.core.gba.cpu.CpuState.MODE_IRQ,
                    Arm7Tdmi.VECTOR_IRQ,
                    cpu.state.regs[15] + 4,
                )
            }
            val consumed = cpu.step()
            diagnostics.onInstruction()
            advancePeripherals(consumed)
            elapsed += consumed
        }
    }

    /**
     * Avance affichage, timers et audio de [cycles] cycles CPU, **sans jamais
     * franchir d'un seul bond un instant observable**.
     *
     * Les timers et l'unité audio ne sont pas indépendants : un débordement de
     * timer fait consommer un octet d'une FIFO Direct Sound, et cette valeur est
     * ensuite lue au moment d'échantillonner. Avancer les deux d'un gros bloc
     * traitait tous les débordements d'abord, puis produisait tous les
     * échantillons ensuite : ceux-ci n'observaient alors que la dernière valeur
     * dépilée, et tout le contenu intermédiaire du bloc était perdu.
     *
     * Le pas est donc borné par la plus proche des deux échéances. En pratique
     * l'appelant avance de quelques cycles à la fois et la boucle ne tourne
     * qu'une fois ; elle ne se scinde que sur les gros blocs, ceux des DMA.
     *
     * `internal` plutôt que `private` : c'est le point d'entrée que les tests
     * d'entrelacement pilotent directement, avec des découpages choisis. Une
     * copie de cette boucle dans un banc d'essai ne verrouillerait rien.
     */
    internal fun advancePeripherals(cycles: Int) {
        var remaining = cycles
        while (remaining > 0) {
            val step = minOf(
                remaining,
                timers.cyclesUntilNextOverflow(),
                apu.cyclesUntilNextSample(),
            ).coerceAtLeast(1)
            ppu.tick(step)
            timers.tick(step)
            apu.tick(step)
            remaining -= step
        }
    }

    companion object {
        /** Adresse du premier octet de la ROM cartouche, où le CPU démarre. */
        const val ROM_ENTRY_POINT = 0x0800_0000

        /** Pas d'avancement des périphériques pendant une pause CPU. */
        private const val HALT_STEP = 64
    }
}
