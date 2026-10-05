package com.ravenemu.core.gba.dma

import com.ravenemu.core.gba.interrupt.GbaInterruptController
import com.ravenemu.core.gba.interrupt.Interrupt
import com.ravenemu.core.gba.memory.GbaBus

/**
 * Quatre canaux DMA de la Game Boy Advance (registres 0x0400_00B0…).
 *
 * Chaque transfert progresse en trois types de phases : prise du bus, lecture,
 * puis écriture. La boucle machine écoule le coût de la phase avant d'appliquer
 * son effet ; le CPU reste bloqué jusqu'à la dernière écriture.
 *
 * Les déclenchements immédiat, VBlank, HBlank et FIFO Direct Sound sont pris en
 * charge. Plusieurs canaux déclenchés ensemble sont servis par priorité
 * croissante ; une requête plus prioritaire suspend le transfert courant à la
 * prochaine frontière d'accès, puis celui-ci reprend sans perdre sa phase.
 */
class DmaController(
    private val bus: GbaBus,
    private val interrupts: GbaInterruptController,
) {
    private val sourceAddress = IntArray(4)
    private val destAddress = IntArray(4)

    private var activeChannel = -1
    private var currentDestination = 0
    private var remainingWords = 0
    private var activeControl = 0
    private var phase = PHASE_IDLE
    private var phaseCycles = 0
    private var latchedValue = 0
    private var sequential = false
    private var fifoTransfer = false
    private var pendingRequests = 0
    private val suspended = Array(4) { SuspendedTransfer() }

    /** Canal ayant terminé le dernier transfert, ou -1 (diagnostic). */
    var lastChannel = -1
        private set

    /** `true` tant que le DMA retient le bus. */
    val isActive: Boolean get() = activeChannel >= 0

    /** Cycles avant le prochain effet DMA observable. */
    fun cyclesUntilEvent(): Int = if (isActive) phaseCycles else 0

    /**
     * Écoule [cycles] cycles de la phase active. L'appelant borne normalement
     * cette valeur avec [cyclesUntilEvent] pour entrelacer les périphériques.
     */
    fun tick(cycles: Int) {
        if (!isActive || cycles <= 0) return
        phaseCycles -= minOf(cycles, phaseCycles)
        if (phaseCycles == 0) {
            completePhase()
            if (isActive) preemptIfNeeded()
        }
    }

    /** Écriture du registre de contrôle `DMAxCNT_H` : arme ou déclenche le canal. */
    fun onControlWrite(channel: Int, control: Int) {
        val bit = 1 shl channel
        if (control and ENABLE == 0) {
            pendingRequests = pendingRequests and bit.inv()
            if (activeChannel != channel) suspended[channel].clear()
            return
        }
        sourceAddress[channel] = readIoWord(SAD[channel]) and ADDRESS_MASK
        destAddress[channel] = readIoWord(DAD[channel]) and ADDRESS_MASK
        if (timing(control) == TIMING_IMMEDIATE) requestTransfer(channel)
    }

    fun triggerVBlank() = triggerTiming(TIMING_VBLANK)

    fun triggerHBlank() = triggerTiming(TIMING_HBLANK)

    /** Demande quatre mots vers la FIFO A (0) ou B (1). */
    fun triggerSoundFifo(fifoChannel: Int) {
        val fifoAddress = if (fifoChannel == 0) FIFO_A_ADDRESS else FIFO_B_ADDRESS
        for (channel in 1..2) {
            val control = readIoHalf(CNT_H[channel])
            if (control and ENABLE == 0 || timing(control) != TIMING_SPECIAL) continue
            if (readIoWord(DAD[channel]) and ADDRESS_MASK != fifoAddress) continue
            requestTransfer(channel)
        }
    }

    fun exportState(): IntArray {
        val result = IntArray(STATE_WORDS)
        sourceAddress.copyInto(result)
        destAddress.copyInto(result, destinationOffset = 4)
        result[8] = activeChannel
        result[9] = currentDestination
        result[10] = remainingWords
        result[11] = activeControl
        result[12] = phase
        result[13] = phaseCycles
        result[14] = latchedValue
        result[15] = if (sequential) 1 else 0
        result[16] = if (fifoTransfer) 1 else 0
        result[17] = pendingRequests
        for (channel in 0 until 4) {
            val transfer = suspended[channel]
            val offset = SUSPENDED_OFFSET + channel * SUSPENDED_TRANSFER_WORDS
            result[offset] = transfer.currentDestination
            result[offset + 1] = transfer.remainingWords
            result[offset + 2] = transfer.control
            result[offset + 3] = transfer.phase
            result[offset + 4] = transfer.phaseCycles
            result[offset + 5] = transfer.latchedValue
            result[offset + 6] = if (transfer.sequential) 1 else 0
            result[offset + 7] = if (transfer.fifo) 1 else 0
        }
        return result
    }

    fun importState(data: IntArray) {
        require(data.size == STATE_WORDS) { "État DMA GBA invalide" }
        val restoredChannel = data[8]
        val restoredPhase = data[12]
        val restoredRequests = data[17]
        val activeChannelValid = restoredChannel in 0..3
        val restoredSuspended = Array(4) { SuspendedTransfer() }
        var suspendedMask = 0
        var invalidSuspended = false
        for (channel in 0 until 4) {
            val offset = SUSPENDED_OFFSET + channel * SUSPENDED_TRANSFER_WORDS
            val storedPhase = data[offset + 3]
            if (storedPhase == PHASE_IDLE) {
                if ((0 until SUSPENDED_TRANSFER_WORDS).any { data[offset + it] != 0 }) {
                    invalidSuspended = true
                }
                continue
            }

            val control = data[offset + 2]
            val storedSequential = data[offset + 6]
            val storedFifo = data[offset + 7]
            if (
                channel == restoredChannel ||
                (activeChannelValid && channel < restoredChannel) ||
                storedPhase !in PHASE_STARTUP..PHASE_WRITE ||
                data[offset + 1] !in 1..MAX_WORDS ||
                data[offset + 4] <= 0 ||
                control and ENABLE == 0 ||
                storedSequential !in 0..1 ||
                storedFifo !in 0..1 ||
                (timing(control) == TIMING_SPECIAL) != (storedFifo != 0) ||
                restoredRequests and (1 shl channel) != 0
            ) {
                invalidSuspended = true
            }
            restoredSuspended[channel].apply {
                currentDestination = data[offset]
                remainingWords = data[offset + 1]
                this.control = control
                phase = storedPhase
                phaseCycles = data[offset + 4]
                latchedValue = data[offset + 5]
                sequential = storedSequential != 0
                fifo = storedFifo != 0
            }
            suspendedMask = suspendedMask or (1 shl channel)
        }
        val invalidIdle =
            restoredChannel == -1 &&
                (restoredPhase != PHASE_IDLE ||
                    data[10] != 0 ||
                    data[13] != 0 ||
                    restoredRequests != 0 ||
                    suspendedMask != 0)
        val invalidActive =
            activeChannelValid &&
                (restoredPhase !in PHASE_STARTUP..PHASE_WRITE ||
                    data[10] !in 1..MAX_WORDS ||
                    data[13] <= 0 ||
                    data[11] and ENABLE == 0 ||
                    (timing(data[11]) == TIMING_SPECIAL) != (data[16] != 0) ||
                    restoredRequests and (1 shl restoredChannel) != 0)
        require(
            restoredChannel in -1..3 &&
                restoredRequests in 0..0x0F &&
                data[15] in 0..1 &&
                data[16] in 0..1 &&
                !invalidIdle &&
                !invalidActive &&
                !invalidSuspended,
        ) {
            "État DMA GBA invalide"
        }

        for (i in 0 until 4) {
            sourceAddress[i] = data[i]
            destAddress[i] = data[4 + i]
        }
        activeChannel = restoredChannel
        currentDestination = data[9]
        remainingWords = data[10]
        activeControl = data[11]
        phase = restoredPhase
        phaseCycles = data[13]
        latchedValue = data[14]
        sequential = data[15] != 0
        fifoTransfer = data[16] != 0
        pendingRequests = restoredRequests
        for (channel in 0 until 4) suspended[channel] = restoredSuspended[channel]
        if (isActive) bus.breakAccessSequence()
    }

    fun reset() {
        sourceAddress.fill(0)
        destAddress.fill(0)
        activeChannel = -1
        currentDestination = 0
        remainingWords = 0
        activeControl = 0
        phase = PHASE_IDLE
        phaseCycles = 0
        latchedValue = 0
        sequential = false
        fifoTransfer = false
        pendingRequests = 0
        suspended.forEach(SuspendedTransfer::clear)
        lastChannel = -1
    }

    private fun triggerTiming(requested: Int) {
        for (channel in 0 until 4) {
            val control = readIoHalf(CNT_H[channel])
            if (control and ENABLE != 0 && timing(control) == requested) requestTransfer(channel)
        }
    }

    private fun requestTransfer(channel: Int) {
        val bit = 1 shl channel
        if (activeChannel == channel || suspended[channel].isActive || pendingRequests and bit != 0) return
        pendingRequests = pendingRequests or bit
        startNextTransfer()
    }

    private fun startNextTransfer() {
        if (isActive) return
        while (true) {
            var channel = -1
            for (candidate in 0 until 4) {
                if (suspended[candidate].isActive || pendingRequests and (1 shl candidate) != 0) {
                    channel = candidate
                    break
                }
            }
            if (channel < 0) return

            val paused = suspended[channel]
            if (paused.isActive) {
                restoreTransfer(channel, paused)
                paused.clear()
                return
            }

            pendingRequests = pendingRequests and (1 shl channel).inv()
            val control = readIoHalf(CNT_H[channel])
            if (control and ENABLE == 0) continue
            startTransfer(channel, control)
            return
        }
    }

    private fun preemptIfNeeded() {
        for (channel in 0 until activeChannel) {
            val bit = 1 shl channel
            if (pendingRequests and bit == 0) continue
            pendingRequests = pendingRequests and bit.inv()
            val control = readIoHalf(CNT_H[channel])
            if (control and ENABLE == 0) continue
            suspendActiveTransfer()
            startTransfer(channel, control)
            return
        }
    }

    private fun suspendActiveTransfer() {
        suspended[activeChannel].apply {
            currentDestination = this@DmaController.currentDestination
            remainingWords = this@DmaController.remainingWords
            control = activeControl
            phase = this@DmaController.phase
            phaseCycles = this@DmaController.phaseCycles
            latchedValue = this@DmaController.latchedValue
            sequential = this@DmaController.sequential
            fifo = fifoTransfer
        }
    }

    private fun restoreTransfer(channel: Int, transfer: SuspendedTransfer) {
        activeChannel = channel
        currentDestination = transfer.currentDestination
        remainingWords = transfer.remainingWords
        activeControl = transfer.control
        phase = transfer.phase
        phaseCycles = transfer.phaseCycles
        latchedValue = transfer.latchedValue
        sequential = transfer.sequential
        fifoTransfer = transfer.fifo
    }

    private fun startTransfer(channel: Int, control: Int) {
        activeChannel = channel
        activeControl = control
        fifoTransfer = timing(control) == TIMING_SPECIAL
        remainingWords =
            if (fifoTransfer) FIFO_WORDS else wordCount(channel, readIoHalf(CNT_L[channel]))
        currentDestination = destAddress[channel]
        sequential = false
        latchedValue = 0
        phase = PHASE_STARTUP
        phaseCycles = STARTUP_CYCLES

        val eeprom = bus.eeprom()
        if (
            eeprom != null &&
            ((sourceAddress[channel] ushr 24) == 0x0D || (currentDestination ushr 24) == 0x0D)
        ) {
            eeprom.hintTransferLength(remainingWords)
        }
        bus.breakAccessSequence()
    }

    private fun completePhase() {
        when (phase) {
            PHASE_STARTUP -> {
                phase = PHASE_READ
                phaseCycles = accessCycles(sourceAddress[activeChannel], transferSize(), sequential)
            }
            PHASE_READ -> completeRead()
            PHASE_WRITE -> completeWrite()
        }
    }

    private fun completeRead() {
        val size = transferSize()
        latchedValue = timedAccess {
            if (size == 4) bus.read32(sourceAddress[activeChannel])
            else bus.read16(sourceAddress[activeChannel])
        }
        sourceAddress[activeChannel] += delta((activeControl ushr 7) and 3, size)
        phase = PHASE_WRITE
        phaseCycles = accessCycles(currentDestination, size, sequential)
    }

    private fun completeWrite() {
        val size = transferSize()
        timedAccess {
            if (size == 4) bus.write32(currentDestination, latchedValue)
            else bus.write16(currentDestination, latchedValue)
        }
        val destinationControl =
            if (fifoTransfer) DEST_FIXED else (activeControl ushr 5) and 3
        currentDestination += delta(destinationControl, size)
        remainingWords--
        if (remainingWords > 0) {
            sequential = true
            phase = PHASE_READ
            phaseCycles = accessCycles(sourceAddress[activeChannel], size, sequential)
        } else {
            finishTransfer()
        }
    }

    private inline fun <T> timedAccess(block: () -> T): T {
        val measure = bus.diagnostics.measuringTime
        val started = if (measure) System.nanoTime() else 0L
        val result = block()
        bus.takeWaitCycles()
        if (measure) bus.diagnostics.addDmaNanos(System.nanoTime() - started)
        return result
    }

    private class SuspendedTransfer {
        var currentDestination = 0
        var remainingWords = 0
        var control = 0
        var phase = PHASE_IDLE
        var phaseCycles = 0
        var latchedValue = 0
        var sequential = false
        var fifo = false

        val isActive: Boolean get() = phase != PHASE_IDLE

        fun clear() {
            currentDestination = 0
            remainingWords = 0
            control = 0
            phase = PHASE_IDLE
            phaseCycles = 0
            latchedValue = 0
            sequential = false
            fifo = false
        }
    }

    private fun finishTransfer() {
        val channel = activeChannel
        val destinationControl = (activeControl ushr 5) and 3
        if (!fifoTransfer && destinationControl != DEST_INCREMENT_RELOAD) {
            destAddress[channel] = currentDestination
        }
        if (activeControl and IRQ_ON_END != 0) interrupts.request(Interrupt.DMA0 + channel)
        val repeat = activeControl and REPEAT != 0
        if (!fifoTransfer && (!repeat || timing(activeControl) == TIMING_IMMEDIATE)) {
            val cleared = activeControl and ENABLE.inv()
            bus.io[CNT_H[channel] + 1] = ((cleared ushr 8) and 0xFF).toByte()
        }

        lastChannel = channel
        activeChannel = -1
        currentDestination = 0
        remainingWords = 0
        activeControl = 0
        phase = PHASE_IDLE
        phaseCycles = 0
        latchedValue = 0
        sequential = false
        fifoTransfer = false
        bus.breakAccessSequence()
        startNextTransfer()
    }

    private fun transferSize(): Int =
        if (fifoTransfer || activeControl and WORD_32 != 0) 4 else 2

    private fun accessCycles(address: Int, size: Int, sequential: Boolean): Int =
        1 + bus.timing.waitStates(address, size, sequential)

    private fun delta(control: Int, size: Int): Int = when (control) {
        0, DEST_INCREMENT_RELOAD -> size
        1 -> -size
        else -> 0
    }

    private fun wordCount(channel: Int, raw: Int): Int {
        val mask = if (channel == 3) 0xFFFF else 0x3FFF
        val value = raw and mask
        return if (value == 0) mask + 1 else value
    }

    private fun timing(control: Int): Int = (control ushr 12) and 3

    private fun readIoHalf(offset: Int): Int =
        (bus.io[offset].toInt() and 0xFF) or ((bus.io[offset + 1].toInt() and 0xFF) shl 8)

    private fun readIoWord(offset: Int): Int =
        readIoHalf(offset) or (readIoHalf(offset + 2) shl 16)

    companion object {
        const val STATE_WORDS = 50

        private val SAD = intArrayOf(0xB0, 0xBC, 0xC8, 0xD4)
        private val DAD = intArrayOf(0xB4, 0xC0, 0xCC, 0xD8)
        private val CNT_L = intArrayOf(0xB8, 0xC4, 0xD0, 0xDC)
        private val CNT_H = intArrayOf(0xBA, 0xC6, 0xD2, 0xDE)

        private const val PHASE_IDLE = 0
        private const val PHASE_STARTUP = 1
        private const val PHASE_READ = 2
        private const val PHASE_WRITE = 3
        private const val SUSPENDED_OFFSET = 18
        private const val SUSPENDED_TRANSFER_WORDS = 8
        private const val TIMING_IMMEDIATE = 0
        private const val TIMING_VBLANK = 1
        private const val TIMING_HBLANK = 2
        private const val TIMING_SPECIAL = 3
        private const val DEST_FIXED = 2
        private const val DEST_INCREMENT_RELOAD = 3
        private const val ENABLE = 0x8000
        private const val IRQ_ON_END = 0x4000
        private const val WORD_32 = 0x0400
        private const val REPEAT = 0x0200
        private const val ADDRESS_MASK = 0x0FFF_FFFF
        private const val MAX_WORDS = 0x10000

        private const val FIFO_A_ADDRESS = 0x0400_00A0
        private const val FIFO_B_ADDRESS = 0x0400_00A4
        private const val FIFO_WORDS = 4
        private const val STARTUP_CYCLES = 2
    }
}
