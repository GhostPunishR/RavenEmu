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
 *
 * ### Coût en temps
 * Un transfert n'est pas gratuit : le contrôleur prend le bus, le CPU s'arrête,
 * et chaque mot coûte une lecture puis une écriture dont le prix dépend de la
 * région visée (voir [com.ravenemu.core.gba.memory.MemoryTiming]). Le premier
 * mot est facturé en accès non séquentiel, les suivants en séquentiel. Ces
 * cycles sont accumulés dans [pendingCycles], que la boucle de la machine
 * consomme en arrêtant le processeur — un jeu qui recopie une trame par DMA voit
 * donc réellement son code s'interrompre.
 *
 * ### Limite documentée
 * Les mots sont copiés d'un bloc au moment du déclenchement, puis le temps
 * correspondant est écoulé. Aucun autre maître du bus n'observant l'état
 * intermédiaire, le résultat est identique à celui d'un transfert entrelacé ;
 * seule la position exacte de chaque mot dans la ligne d'affichage diffère.
 */
class DmaController(
    private val bus: GbaBus,
    private val interrupts: GbaInterruptController,
) {
    // Adresses courantes verrouillées à l'activation.
    private val sourceAddress = IntArray(4)
    private val destAddress = IntArray(4)

    /**
     * Cycles dus par les transferts déjà effectués et pas encore écoulés. Tant
     * qu'ils ne sont pas consommés, le CPU n'a pas la main.
     */
    var pendingCycles = 0
        private set

    /** Canal ayant réalisé le dernier transfert, ou -1 (diagnostic). */
    var lastChannel = -1
        private set

    /** `true` si le contrôleur retient encore le bus. */
    val isActive: Boolean get() = pendingCycles > 0

    /** Récupère et remet à zéro les cycles dus par le contrôleur. */
    fun takePendingCycles(): Int {
        val value = pendingCycles
        pendingCycles = 0
        return value
    }

    /** Écriture du registre de contrôle `DMAxCNT_H` : arme ou déclenche le canal. */
    fun onControlWrite(channel: Int, control: Int) {
        if (control and 0x8000 == 0) return // pas d'activation
        sourceAddress[channel] = readIoWord(SAD[channel]) and 0x0FFF_FFFF
        destAddress[channel] = readIoWord(DAD[channel]) and 0x0FFF_FFFF
        if (timing(control) == TIMING_IMMEDIATE) performTransfer(channel)
    }

    fun triggerVBlank() = triggerTiming(TIMING_VBLANK)

    fun triggerHBlank() = triggerTiming(TIMING_HBLANK)

    /**
     * Réapprovisionnement d'une FIFO Direct Sound (`0` = A, `1` = B) : le canal
     * DMA 1 ou 2 armé en mode spécial et pointant sur la bonne FIFO transfère
     * quatre mots de 32 bits, sans faire progresser l'adresse de destination.
     */
    fun triggerSoundFifo(fifoChannel: Int) {
        val fifoAddress = if (fifoChannel == 0) FIFO_A_ADDRESS else FIFO_B_ADDRESS
        for (channel in 1..2) {
            val control = readIoHalf(CNT_H[channel])
            if (control and 0x8000 == 0 || timing(control) != TIMING_SPECIAL) continue
            if (readIoWord(DAD[channel]) and 0x0FFF_FFFF != fifoAddress) continue
            val sourceControl = (control ushr 7) and 0x3
            var src = sourceAddress[channel]
            var cycles = STARTUP_CYCLES
            for (word in 0 until FIFO_WORDS) {
                cycles += accessCycles(src, 4, sequential = word > 0)
                cycles += accessCycles(fifoAddress, 4, sequential = word > 0)
                bus.write32(fifoAddress, bus.read32(src))
                src += delta(sourceControl, 4)
            }
            sourceAddress[channel] = src
            finishTransfer(channel, cycles)
            if (control and 0x4000 != 0) interrupts.request(Interrupt.DMA0 + channel)
        }
    }

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

        // Les accès EEPROM sont sériels : la longueur du transfert révèle la
        // largeur d'adresse attendue par le jeu.
        val eeprom = bus.eeprom()
        if (eeprom != null && ((src ushr 24) == 0x0D || (dst ushr 24) == 0x0D)) {
            eeprom.hintTransferLength(count)
        }
        // Prise du bus, puis un couple lecture/écriture par mot : le premier est
        // facturé en accès non séquentiel, les suivants en séquentiel.
        var cycles = STARTUP_CYCLES
        for (word in 0 until count) {
            val sequential = word > 0
            cycles += accessCycles(src, size, sequential)
            cycles += accessCycles(dst, size, sequential)
            if (word32) bus.write32(dst, bus.read32(src)) else bus.write16(dst, bus.read16(src))
            src += delta(sourceControl, size)
            dst += delta(destControl, size)
        }
        sourceAddress[channel] = src
        // Contrôle destination 3 = incrément + rechargement à la répétition.
        if (destControl != DEST_INCREMENT_RELOAD) destAddress[channel] = dst

        finishTransfer(channel, cycles)
        if (control and 0x4000 != 0) interrupts.request(Interrupt.DMA0 + channel)

        val repeat = control and 0x0200 != 0
        if (!repeat || timing(control) == TIMING_IMMEDIATE) {
            // Efface le bit d'activation dans le registre de contrôle.
            val cleared = control and 0x8000.inv()
            bus.io[CNT_H[channel] + 1] = ((cleared ushr 8) and 0xFF).toByte()
        }
    }

    /**
     * Coût d'un accès du DMA à [address], en cycles complets : un cycle de base
     * plus les temps d'attente de la région.
     */
    private fun accessCycles(address: Int, size: Int, sequential: Boolean): Int =
        1 + bus.timing.waitStates(address, size, sequential)

    /**
     * Clôt un transfert : les cycles sont mis à la charge du contrôleur, et le
     * bus repart en accès non séquentiel puisque le CPU le récupère. La
     * facturation faite par le bus pendant la copie est écartée pour ne pas être
     * imputée deux fois — une fois ici, une fois à l'instruction en cours.
     */
    private fun finishTransfer(channel: Int, cycles: Int) {
        bus.takeWaitCycles()
        bus.breakAccessSequence()
        pendingCycles += cycles
        lastChannel = channel
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

    fun exportState(): IntArray = sourceAddress + destAddress + intArrayOf(pendingCycles)

    fun importState(data: IntArray) {
        for (i in 0 until 4) {
            sourceAddress[i] = data[i]
            destAddress[i] = data[4 + i]
        }
        pendingCycles = data[8]
    }

    fun reset() {
        sourceAddress.fill(0)
        destAddress.fill(0)
        pendingCycles = 0
        lastChannel = -1
    }

    private companion object {
        val SAD = intArrayOf(0xB0, 0xBC, 0xC8, 0xD4)
        val DAD = intArrayOf(0xB4, 0xC0, 0xCC, 0xD8)
        val CNT_L = intArrayOf(0xB8, 0xC4, 0xD0, 0xDC)
        val CNT_H = intArrayOf(0xBA, 0xC6, 0xD2, 0xDE)

        const val TIMING_IMMEDIATE = 0
        const val TIMING_VBLANK = 1
        const val TIMING_HBLANK = 2
        const val TIMING_SPECIAL = 3
        const val DEST_INCREMENT_RELOAD = 3

        const val FIFO_A_ADDRESS = 0x0400_00A0
        const val FIFO_B_ADDRESS = 0x0400_00A4

        /** Un réapprovisionnement transfère quatre mots (16 octets). */
        const val FIFO_WORDS = 4

        /** Cycles de prise du bus, avant le premier mot transféré. */
        const val STARTUP_CYCLES = 2
    }
}
