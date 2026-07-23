package com.ravenemu.core.gba.dma

import com.ravenemu.core.gba.interrupt.GbaInterruptController
import com.ravenemu.core.gba.interrupt.Interrupt
import com.ravenemu.core.gba.memory.GbaBus

/**
 * Quatre canaux DMA de la Game Boy Advance (registres 0x0400_00B0…).
 *
 * Chaque canal copie de mémoire à mémoire, en mots de 16 ou 32 bits, avec
 * contrôle d'adresse source/destination (incrément, décrément, fixe), répétition
 * et interruption de fin. Le déclenchement pris en charge : **immédiat**,
 * **VBlank** et **HBlank**. Les modes spéciaux (FIFO son, capture vidéo) sont
 * différés (documentés).
 *
 * Les adresses et le nombre de mots sont lus dans les registres d'E/S (écrits
 * par le programme) ; l'écriture du registre de contrôle avec le bit
 * d'activation déclenche (ou arme) le transfert.
 */
class DmaController(
    private val bus: GbaBus,
    private val interrupts: GbaInterruptController,
) {
    // Adresses courantes verrouillées à l'activation.
    private val sourceAddress = IntArray(4)
    private val destAddress = IntArray(4)

    /** Écriture du registre de contrôle `DMAxCNT_H` : arme ou déclenche le canal. */
    fun onControlWrite(channel: Int, control: Int) {
        if (control and 0x8000 == 0) return // pas d'activation
        sourceAddress[channel] = readIoWord(SAD[channel]) and 0x0FFF_FFFF
        destAddress[channel] = readIoWord(DAD[channel]) and 0x0FFF_FFFF
        if (timing(control) == TIMING_IMMEDIATE) performTransfer(channel)
    }

    fun triggerVBlank() = triggerTiming(TIMING_VBLANK)

    fun triggerHBlank() = triggerTiming(TIMING_HBLANK)

    private fun triggerTiming(timing: Int) {
        for (channel in 0 until 4) {
            val control = readIoHalf(CNT_H[channel])
            if (control and 0x8000 != 0 && timing(control) == timing) performTransfer(channel)
        }
    }

    private fun performTransfer(channel: Int) {
        val control = readIoHalf(CNT_H[channel])
        val word32 = control and 0x0400 != 0
        val size = if (word32) 4 else 2
        val destControl = (control ushr 5) and 0x3
        val sourceControl = (control ushr 7) and 0x3
        val count = wordCount(channel, readIoHalf(CNT_L[channel]))

        var src = sourceAddress[channel]
        var dst = destAddress[channel]
        repeat(count) {
            if (word32) bus.write32(dst, bus.read32(src)) else bus.write16(dst, bus.read16(src))
            src += delta(sourceControl, size)
            dst += delta(destControl, size)
        }
        sourceAddress[channel] = src
        // Contrôle destination 3 = incrément + rechargement à la répétition.
        if (destControl != DEST_INCREMENT_RELOAD) destAddress[channel] = dst

        if (control and 0x4000 != 0) interrupts.request(Interrupt.DMA0 + channel)

        val repeat = control and 0x0200 != 0
        if (!repeat || timing(control) == TIMING_IMMEDIATE) {
            // Efface le bit d'activation dans le registre de contrôle.
            val cleared = control and 0x8000.inv()
            bus.io[CNT_H[channel] + 1] = ((cleared ushr 8) and 0xFF).toByte()
        }
    }

    private fun delta(control: Int, size: Int): Int = when (control) {
        0, 3 -> size    // incrément (3 = incrément + rechargement)
        1 -> -size      // décrément
        else -> 0       // fixe
    }

    private fun wordCount(channel: Int, raw: Int): Int {
        val mask = if (channel == 3) 0xFFFF else 0x3FFF
        val value = raw and mask
        return if (value == 0) mask + 1 else value
    }

    private fun timing(control: Int): Int = (control ushr 12) and 0x3

    private fun readIoHalf(offset: Int): Int =
        (bus.io[offset].toInt() and 0xFF) or ((bus.io[offset + 1].toInt() and 0xFF) shl 8)

    private fun readIoWord(offset: Int): Int =
        readIoHalf(offset) or (readIoHalf(offset + 2) shl 16)

    fun exportState(): IntArray = sourceAddress + destAddress

    fun importState(data: IntArray) {
        for (i in 0 until 4) {
            sourceAddress[i] = data[i]
            destAddress[i] = data[4 + i]
        }
    }

    private companion object {
        val SAD = intArrayOf(0xB0, 0xBC, 0xC8, 0xD4)
        val DAD = intArrayOf(0xB4, 0xC0, 0xCC, 0xD8)
        val CNT_L = intArrayOf(0xB8, 0xC4, 0xD0, 0xDC)
        val CNT_H = intArrayOf(0xBA, 0xC6, 0xD2, 0xDE)

        const val TIMING_IMMEDIATE = 0
        const val TIMING_VBLANK = 1
        const val TIMING_HBLANK = 2
        const val DEST_INCREMENT_RELOAD = 3
    }
}
