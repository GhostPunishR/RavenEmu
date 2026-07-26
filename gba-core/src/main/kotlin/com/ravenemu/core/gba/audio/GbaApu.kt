package com.ravenemu.core.gba.audio

/**
 * Unité audio de la Game Boy Advance.
 *
 * Elle réunit deux familles de sources :
 * - les **quatre canaux PSG** hérités de la Game Boy (deux ondes carrées, une
 *   table d'onde, un bruit), cadencés par un séquenceur de trames à 512 Hz ;
 * - les **deux canaux Direct Sound** (A et B), qui rejouent des échantillons
 *   PCM 8 bits signés dépilés d'une **FIFO** de 32 octets. Chaque canal est
 *   cadencé par le timer 0 ou 1 : à chaque débordement du timer choisi, un
 *   échantillon est consommé. Quand la FIFO se vide, l'APU demande son
 *   réapprovisionnement par DMA ([onFifoRequest]).
 *
 * Le mixage produit du PCM stéréo 16 bits à [SAMPLE_RATE_HZ], drainé par
 * `readSamples`. L'horloge audio vaut le quart de l'horloge CPU du GBA.
 */
class GbaApu {

    internal val square1 = SquareChannel(hasSweep = true)
    internal val square2 = SquareChannel(hasSweep = false)
    internal val wave = WaveChannel()
    internal val noise = NoiseChannel()

    /** Registres de contrôle du mixage (`SOUNDCNT_L/H/X`). */
    var controlL = 0
    var controlH = 0
    var masterEnable = false

    /**
     * Demande de réapprovisionnement d'une FIFO (`0` = A, `1` = B), à câbler sur
     * le DMA en mode son.
     */
    var onFifoRequest: ((Int) -> Unit)? = null

    /**
     * Rappel de chronométrage du mixage, branché par la machine quand la mesure
     * est active. Nul, il ne coûte qu'une comparaison de référence par lot.
     */
    var onBatchNanos: ((Long) -> Unit)? = null

    private val fifoA = FifoBuffer()
    private val fifoB = FifoBuffer()
    private val fifoEmptyReads = IntArray(2)
    private var directSampleA = 0
    private var directSampleB = 0

    // Cadencement.
    private var cpuRemainder = 0
    private var sampleTimer = 0
    private var sequencerTimer = 0
    private var sequencerStep = 0

    // Niveaux PSG de l'échantillon courant, réutilisés d'un échantillon à
    // l'autre : le mixage tourne 32 768 fois par seconde et ne doit rien allouer.
    private val psgOutputs = IntArray(4)

    // Tampon circulaire de sortie (stéréo entrelacé).
    private val ring = ShortArray(BUFFER_SAMPLES)
    private var writeIndex = 0
    private var readIndex = 0
    private var available = 0

    /** Avance l'APU de [cpuCycles] cycles CPU. */
    fun tick(cpuCycles: Int) {
        // L'horloge audio est le quart de l'horloge CPU.
        cpuRemainder += cpuCycles
        // Traitement par lots : appelé après chaque instruction, ce point d'entrée
        // recevait des incréments de quelques cycles et payait le coût d'appel de
        // quatre oscillateurs pour presque rien. Le résultat est identique — la
        // boucle ci-dessous découpe de toute façon aux frontières d'échantillon,
        // et rien ne lit l'état de l'unité entre deux instructions — seul le
        // moment du calcul change.
        if (cpuRemainder < MIN_BATCH_CPU_CYCLES) return
        var audioCycles = cpuRemainder shr 2
        if (audioCycles <= 0) return
        cpuRemainder -= audioCycles shl 2
        val start = if (onBatchNanos != null) System.nanoTime() else 0L

        // On n'avance jamais au-delà du prochain échantillon : les canaux et le
        // point d'échantillonnage restent ainsi entrelacés, sinon toute une
        // salve d'échantillons observerait le même état des oscillateurs.
        while (audioCycles > 0) {
            val step = minOf(audioCycles, CYCLES_PER_SAMPLE - sampleTimer)
            square1.tick(step)
            square2.tick(step)
            wave.tick(step)
            noise.tick(step)

            sequencerTimer += step
            while (sequencerTimer >= SEQUENCER_PERIOD) {
                sequencerTimer -= SEQUENCER_PERIOD
                clockSequencer()
            }

            sampleTimer += step
            if (sampleTimer >= CYCLES_PER_SAMPLE) {
                sampleTimer -= CYCLES_PER_SAMPLE
                emitSample()
            }
            audioCycles -= step
        }
        onBatchNanos?.invoke(System.nanoTime() - start)
    }

    /** Séquenceur de trames : longueurs (256 Hz), enveloppes (64 Hz), balayage (128 Hz). */
    private fun clockSequencer() {
        when (sequencerStep) {
            0, 4 -> clockLengths()
            2, 6 -> {
                clockLengths()
                square1.clockSweep()
            }
            7 -> {
                square1.envelope.clock()
                square2.envelope.clock()
                noise.envelope.clock()
            }
        }
        sequencerStep = (sequencerStep + 1) and 7
    }

    private fun clockLengths() {
        square1.clockLength()
        square2.clockLength()
        wave.clockLength()
        noise.clockLength()
    }

    /**
     * Débordement d'un timer : les canaux Direct Sound cadencés par ce timer
     * consomment un échantillon de leur FIFO.
     */
    fun onTimerOverflow(timerIndex: Int) {
        if (timerIndex > 1) return
        if (((controlH ushr 10) and 1) == timerIndex) popFifo(0)
        if (((controlH ushr 14) and 1) == timerIndex) popFifo(1)
    }

    private fun popFifo(channel: Int) {
        val fifo = if (channel == 0) fifoA else fifoB
        if (fifo.size == 0) fifoEmptyReads[channel]++
        val sample = fifo.pop()
        if (channel == 0) directSampleA = sample else directSampleB = sample
        // Sous le quart de sa capacité, la FIFO réclame un nouveau bloc.
        if (fifo.size <= REFILL_THRESHOLD) onFifoRequest?.invoke(channel)
    }

    /** Empile [byteCount] octets d'échantillons dans une FIFO (`0` = A, `1` = B). */
    fun pushFifo(channel: Int, value: Int, byteCount: Int) {
        val fifo = if (channel == 0) fifoA else fifoB
        for (i in 0 until byteCount) fifo.push((value ushr (i * 8)).toByte())
    }

    /** Écriture 32 bits dans une FIFO (`FIFO_A` en 0x0A0, `FIFO_B` en 0x0A4). */
    fun writeFifo(channel: Int, value: Int) = pushFifo(channel, value, 4)

    /**
     * Écriture d'un registre audio 16 bits, l'[offset] étant relatif à la base
     * des registres d'E/S. La RAM d'onde est traitée comme une **banque unique**
     * de 32 échantillons (le double banc du GBA n'est pas émulé : limite
     * documentée).
     */
    fun writeRegister(offset: Int, value: Int) {
        when (offset) {
            0x60 -> square1.writeSweep(value and 0xFF)
            0x62 -> writeSquareDuty(square1, value)
            0x64 -> writeSquareFrequency(square1, value)
            0x68 -> writeSquareDuty(square2, value)
            0x6C -> writeSquareFrequency(square2, value)
            0x70 -> {
                wave.dacEnabled = value and 0x80 != 0
                if (!wave.dacEnabled) wave.enabled = false
            }
            0x72 -> {
                wave.lengthCounter = 256 - (value and 0xFF)
                wave.volumeCode = (value ushr 13) and 0x3
            }
            0x74 -> {
                wave.frequency = value and 0x7FF
                wave.lengthEnabled = value and 0x4000 != 0
                if (value and 0x8000 != 0) wave.trigger()
            }
            0x78 -> {
                noise.lengthCounter = 64 - (value and 0x3F)
                noise.envelope.writeRegister((value ushr 8) and 0xFF)
                if (!noise.envelope.dacEnabled()) noise.enabled = false
            }
            0x7C -> {
                noise.writePolynomial(value and 0xFF)
                noise.lengthEnabled = value and 0x4000 != 0
                if (value and 0x8000 != 0) noise.trigger()
            }
            0x80 -> controlL = value
            0x82 -> {
                controlH = value
                // Les bits 11 et 15 réinitialisent les FIFO A et B.
                if (value and 0x0800 != 0) resetFifo(0)
                if (value and 0x8000 != 0) resetFifo(1)
            }
            0x84 -> {
                masterEnable = value and 0x80 != 0
                if (!masterEnable) {
                    square1.reset()
                    square2.reset()
                    wave.reset()
                    noise.reset()
                }
            }
            in 0x90..0x9F -> {
                // Deux échantillons de 4 bits par octet, poids fort en premier.
                val index = (offset - 0x90) * 2
                wave.waveRam[index] = (value ushr 4) and 0xF
                wave.waveRam[index + 1] = value and 0xF
                wave.waveRam[index + 2] = (value ushr 12) and 0xF
                wave.waveRam[index + 3] = (value ushr 8) and 0xF
            }
        }
    }

    private fun writeSquareDuty(channel: SquareChannel, value: Int) {
        channel.lengthCounter = 64 - (value and 0x3F)
        channel.duty = (value ushr 6) and 0x3
        channel.envelope.writeRegister((value ushr 8) and 0xFF)
        if (!channel.envelope.dacEnabled()) channel.enabled = false
    }

    private fun writeSquareFrequency(channel: SquareChannel, value: Int) {
        channel.frequency = value and 0x7FF
        channel.lengthEnabled = value and 0x4000 != 0
        if (value and 0x8000 != 0) channel.trigger()
    }

    /** Vide la FIFO indiquée (bits de réinitialisation de `SOUNDCNT_H`). */
    fun resetFifo(channel: Int) {
        if (channel == 0) {
            fifoA.clear()
            directSampleA = 0
        } else {
            fifoB.clear()
            directSampleB = 0
        }
    }

    private fun emitSample() {
        if (!masterEnable) {
            push(0, 0)
            return
        }
        // --- PSG ---
        psgOutputs[0] = square1.output()
        psgOutputs[1] = square2.output()
        psgOutputs[2] = wave.output()
        psgOutputs[3] = noise.output()
        var psgLeft = 0
        var psgRight = 0
        for (i in 0 until 4) {
            val level = psgOutputs[i]
            if (level == 0) continue
            if (controlL and (1 shl (8 + i)) != 0) psgLeft += level
            if (controlL and (1 shl (12 + i)) != 0) psgRight += level
        }
        // Volumes maîtres gauche/droite (0..7) puis proportion PSG du mixage.
        // Le ratio est tabulé en virgule fixe 8 bits : 25/50/100 % s'expriment
        // exactement en 64/128/256, ce qui remplace une division par un décalage.
        psgLeft *= ((controlL ushr 4) and 0x7) + 1
        psgRight *= (controlL and 0x7) + 1
        val psgRatio = PSG_RATIO_Q8[controlH and 0x3]
        psgLeft = (psgLeft * PSG_SCALE * psgRatio) shr 8
        psgRight = (psgRight * PSG_SCALE * psgRatio) shr 8

        // --- Direct Sound ---
        val volumeA = if (controlH and 0x04 != 0) 2 else 1
        val volumeB = if (controlH and 0x08 != 0) 2 else 1
        val directA = directSampleA * DIRECT_SCALE * volumeA
        val directB = directSampleB * DIRECT_SCALE * volumeB
        var left = psgLeft
        var right = psgRight
        if (controlH and 0x0200 != 0) left += directA
        if (controlH and 0x0100 != 0) right += directA
        if (controlH and 0x2000 != 0) left += directB
        if (controlH and 0x1000 != 0) right += directB

        push(clamp(left), clamp(right))
    }

    private fun clamp(value: Int): Int = value.coerceIn(-32768, 32767)

    private fun push(left: Int, right: Int) {
        if (available >= BUFFER_SAMPLES) return // l'appelant ne draine pas : on abandonne
        ring[writeIndex] = left.toShort()
        writeIndex = (writeIndex + 1) and BUFFER_MASK
        ring[writeIndex] = right.toShort()
        writeIndex = (writeIndex + 1) and BUFFER_MASK
        available += 2
    }

    /**
     * Sous-alimentations constatées : nombre de fois où de l'audio a été réclamé
     * alors qu'aucun échantillon n'était prêt. C'est ce qui s'entend comme un
     * craquement ou un blanc.
     */
    var underruns = 0
        private set

    /** Copie au plus `dest.size` échantillons disponibles et retourne leur nombre. */
    fun readSamples(dest: ShortArray): Int {
        if (available == 0 && dest.isNotEmpty()) underruns++
        val count = minOf(dest.size, available)
        for (i in 0 until count) {
            dest[i] = ring[readIndex]
            readIndex = (readIndex + 1) and BUFFER_MASK
        }
        available -= count
        return count
    }

    fun reset() {
        square1.reset()
        square2.reset()
        wave.reset()
        noise.reset()
        fifoA.clear()
        fifoB.clear()
        directSampleA = 0
        directSampleB = 0
        controlL = 0
        controlH = 0
        masterEnable = false
        writeIndex = 0
        readIndex = 0
        available = 0
        underruns = 0
        fifoEmptyReads.fill(0)
    }

    /** Nombre d'octets en attente dans une FIFO (diagnostic et tests). */
    fun fifoSize(channel: Int): Int = if (channel == 0) fifoA.size else fifoB.size

    /** Lectures effectuées alors que la FIFO demandée était vide. */
    fun fifoEmptyReads(channel: Int): Int = fifoEmptyReads[channel]

    /** File d'attente circulaire de 32 octets d'échantillons signés. */
    private class FifoBuffer {
        private val data = ByteArray(CAPACITY)
        private var head = 0
        private var tail = 0
        var size = 0
            private set

        fun push(value: Byte) {
            if (size >= CAPACITY) return // pleine : l'octet est perdu
            data[tail] = value
            tail = (tail + 1) and MASK
            size++
        }

        /** Dépile un échantillon signé ; retourne 0 si la file est vide. */
        fun pop(): Int {
            if (size == 0) return 0
            val value = data[head].toInt()
            head = (head + 1) and MASK
            size--
            return value
        }

        fun clear() {
            head = 0
            tail = 0
            size = 0
        }

        companion object {
            const val CAPACITY = 32
            private const val MASK = CAPACITY - 1
        }
    }

    companion object {
        /** Cadence des échantillons produits, en hertz. */
        const val SAMPLE_RATE_HZ = 32768

        /** 4 194 304 / 32 768 = 128 cycles audio par échantillon. */
        private const val CYCLES_PER_SAMPLE = 128

        /** Séquenceur de trames à 512 Hz. */
        private const val SEQUENCER_PERIOD = 8192

        /**
         * Cycles CPU accumulés avant de faire tourner les oscillateurs. Un quart
         * d'échantillon : bien trop court pour s'entendre, assez long pour éviter
         * un appel par instruction.
         */
        private const val MIN_BATCH_CPU_CYCLES = 4 * CYCLES_PER_SAMPLE / 4

        /** Tampon de sortie : environ un dixième de seconde en stéréo. */
        private const val BUFFER_SAMPLES = 8192

        /** [BUFFER_SAMPLES] est une puissance de deux : le modulo est un masque. */
        private const val BUFFER_MASK = BUFFER_SAMPLES - 1

        /** Seuil de réapprovisionnement d'une FIFO, en octets. */
        private const val REFILL_THRESHOLD = 16

        /**
         * Proportion du mixage réservée aux canaux PSG (`SOUNDCNT_H` bits 0-1),
         * en virgule fixe 8 bits : 25 %, 50 %, 100 %, 100 %.
         */
        private val PSG_RATIO_Q8 = intArrayOf(64, 128, 256, 256)

        // Facteurs d'échelle vers le PCM 16 bits, choisis pour éviter la
        // saturation lorsque toutes les sources jouent simultanément.
        private const val PSG_SCALE = 24
        private const val DIRECT_SCALE = 32
    }
}
