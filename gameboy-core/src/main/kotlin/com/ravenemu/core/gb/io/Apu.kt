package com.ravenemu.core.gb.io

import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * APU Game Boy : deux canaux à onde carrée (balayage de fréquence sur le
 * canal 1, enveloppe de volume), canal à table d'onde, canal de bruit LFSR,
 * séquenceur de trames à 512 Hz (longueur 256 Hz, balayage 128 Hz, enveloppe
 * 64 Hz), mixage stéréo NR50/NR51 et extinction NR52.
 *
 * Les échantillons stéréo PCM 16 bits sont produits à [SAMPLE_RATE_HZ]
 * (4 194 304 / 128 : la division est exacte) dans un tampon circulaire
 * consommé par le même thread via [readSamples] — le contrat mono-thread du
 * moteur évite tout verrou.
 *
 * Limites documentées : comportements obscurs non émulés (mode « zombie » de
 * l'enveloppe, corruption de la Wave RAM à la relecture pendant la lecture,
 * horloges de longueur supplémentaires au déclenchement sur front du
 * séquenceur). Sans incidence sur la musique des jeux commerciaux courants.
 */
class Apu {

    // ---- Canal à onde carrée (1 et 2) ----

    private class SquareChannel(private val hasSweep: Boolean) {
        var enabled = false
        var dacEnabled = false

        var duty = 0
        var lengthCounter = 0
        var lengthEnabled = false

        var envelopeInitial = 0
        var envelopeAdd = false
        var envelopePeriod = 0
        var volume = 0
        var envelopeTimer = 0

        var frequency = 0
        var timer = 2048 * 4
        var dutyPosition = 0

        var sweepPeriod = 0
        var sweepNegate = false
        var sweepShift = 0
        var sweepTimer = 0
        var sweepEnabled = false
        var shadowFrequency = 0

        fun step(cycles: Int) {
            timer -= cycles
            while (timer <= 0) {
                timer += (2048 - frequency) * 4
                dutyPosition = (dutyPosition + 1) and 7
            }
        }

        fun output(): Int =
            if (enabled && dacEnabled && DUTY_TABLE[duty][dutyPosition] == 1) volume else 0

        /** Contribution DAC centrée (-15..15), 0 si le DAC est éteint. */
        fun dacOutput(): Int = if (dacEnabled) output() * 2 - 15 else 0

        fun clockLength() {
            if (lengthEnabled && lengthCounter > 0) {
                lengthCounter--
                if (lengthCounter == 0) enabled = false
            }
        }

        fun clockEnvelope() {
            if (envelopePeriod == 0) return
            if (--envelopeTimer <= 0) {
                envelopeTimer = envelopePeriod
                volume = if (envelopeAdd) minOf(15, volume + 1) else maxOf(0, volume - 1)
            }
        }

        fun clockSweep() {
            if (!hasSweep || !sweepEnabled) return
            if (--sweepTimer <= 0) {
                sweepTimer = if (sweepPeriod != 0) sweepPeriod else 8
                if (sweepPeriod != 0) {
                    val next = nextSweepFrequency()
                    if (next <= 2047 && sweepShift != 0) {
                        shadowFrequency = next
                        frequency = next
                        nextSweepFrequency() // second contrôle de débordement
                    }
                }
            }
        }

        private fun nextSweepFrequency(): Int {
            val delta = shadowFrequency shr sweepShift
            val next = if (sweepNegate) shadowFrequency - delta else shadowFrequency + delta
            if (next > 2047) enabled = false
            return next
        }

        fun trigger() {
            enabled = dacEnabled
            if (lengthCounter == 0) lengthCounter = 64
            timer = (2048 - frequency) * 4
            volume = envelopeInitial
            envelopeTimer = envelopePeriod
            if (hasSweep) {
                shadowFrequency = frequency
                sweepTimer = if (sweepPeriod != 0) sweepPeriod else 8
                sweepEnabled = sweepPeriod != 0 || sweepShift != 0
                if (sweepShift != 0) nextSweepFrequency()
            }
        }

        fun reset() {
            enabled = false
            dacEnabled = false
            duty = 0
            lengthCounter = 0
            lengthEnabled = false
            envelopeInitial = 0
            envelopeAdd = false
            envelopePeriod = 0
            volume = 0
            envelopeTimer = 0
            frequency = 0
            dutyPosition = 0
            sweepPeriod = 0
            sweepNegate = false
            sweepShift = 0
            sweepTimer = 0
            sweepEnabled = false
            shadowFrequency = 0
        }

        fun saveState(out: DataOutputStream) {
            out.writeBoolean(enabled)
            out.writeBoolean(dacEnabled)
            out.writeInt(duty)
            out.writeInt(lengthCounter)
            out.writeBoolean(lengthEnabled)
            out.writeInt(envelopeInitial)
            out.writeBoolean(envelopeAdd)
            out.writeInt(envelopePeriod)
            out.writeInt(volume)
            out.writeInt(envelopeTimer)
            out.writeInt(frequency)
            out.writeInt(timer)
            out.writeInt(dutyPosition)
            out.writeInt(sweepPeriod)
            out.writeBoolean(sweepNegate)
            out.writeInt(sweepShift)
            out.writeInt(sweepTimer)
            out.writeBoolean(sweepEnabled)
            out.writeInt(shadowFrequency)
        }

        fun loadState(input: DataInputStream) {
            enabled = input.readBoolean()
            dacEnabled = input.readBoolean()
            duty = input.readInt()
            lengthCounter = input.readInt()
            lengthEnabled = input.readBoolean()
            envelopeInitial = input.readInt()
            envelopeAdd = input.readBoolean()
            envelopePeriod = input.readInt()
            volume = input.readInt()
            envelopeTimer = input.readInt()
            frequency = input.readInt()
            timer = input.readInt()
            dutyPosition = input.readInt()
            sweepPeriod = input.readInt()
            sweepNegate = input.readBoolean()
            sweepShift = input.readInt()
            sweepTimer = input.readInt()
            sweepEnabled = input.readBoolean()
            shadowFrequency = input.readInt()
        }
    }

    // ---- Canal à table d'onde ----

    private class WaveChannel {
        val waveRam = ByteArray(16)

        var enabled = false
        var dacEnabled = false
        var lengthCounter = 0
        var lengthEnabled = false
        var volumeCode = 0
        var frequency = 0
        var timer = 2048 * 2
        var position = 0
        var sampleBuffer = 0

        fun step(cycles: Int) {
            timer -= cycles
            while (timer <= 0) {
                timer += (2048 - frequency) * 2
                position = (position + 1) and 31
                val byte = waveRam[position shr 1].toInt() and 0xFF
                sampleBuffer = if (position and 1 == 0) byte shr 4 else byte and 0x0F
            }
        }

        fun output(): Int {
            if (!enabled || !dacEnabled) return 0
            return when (volumeCode) {
                0 -> 0
                1 -> sampleBuffer
                2 -> sampleBuffer shr 1
                else -> sampleBuffer shr 2
            }
        }

        fun dacOutput(): Int = if (dacEnabled) output() * 2 - 15 else 0

        fun clockLength() {
            if (lengthEnabled && lengthCounter > 0) {
                lengthCounter--
                if (lengthCounter == 0) enabled = false
            }
        }

        fun trigger() {
            enabled = dacEnabled
            if (lengthCounter == 0) lengthCounter = 256
            timer = (2048 - frequency) * 2
            position = 0
        }

        fun reset() {
            enabled = false
            dacEnabled = false
            lengthCounter = 0
            lengthEnabled = false
            volumeCode = 0
            frequency = 0
            position = 0
            sampleBuffer = 0
        }

        fun saveState(out: DataOutputStream) {
            out.write(waveRam)
            out.writeBoolean(enabled)
            out.writeBoolean(dacEnabled)
            out.writeInt(lengthCounter)
            out.writeBoolean(lengthEnabled)
            out.writeInt(volumeCode)
            out.writeInt(frequency)
            out.writeInt(timer)
            out.writeInt(position)
            out.writeInt(sampleBuffer)
        }

        fun loadState(input: DataInputStream) {
            input.readFully(waveRam)
            enabled = input.readBoolean()
            dacEnabled = input.readBoolean()
            lengthCounter = input.readInt()
            lengthEnabled = input.readBoolean()
            volumeCode = input.readInt()
            frequency = input.readInt()
            timer = input.readInt()
            position = input.readInt()
            sampleBuffer = input.readInt()
        }
    }

    // ---- Canal de bruit ----

    private class NoiseChannel {
        var enabled = false
        var dacEnabled = false
        var lengthCounter = 0
        var lengthEnabled = false

        var envelopeInitial = 0
        var envelopeAdd = false
        var envelopePeriod = 0
        var volume = 0
        var envelopeTimer = 0

        var divisorCode = 0
        var widthMode7 = false
        var clockShift = 0
        var lfsr = 0x7FFF
        var timer = 8

        private fun period(): Int = DIVISORS[divisorCode] shl clockShift

        fun step(cycles: Int) {
            timer -= cycles
            while (timer <= 0) {
                timer += period()
                val feedback = (lfsr xor (lfsr shr 1)) and 1
                lfsr = (lfsr shr 1) or (feedback shl 14)
                if (widthMode7) {
                    lfsr = (lfsr and (1 shl 6).inv()) or (feedback shl 6)
                }
            }
        }

        fun output(): Int =
            if (enabled && dacEnabled && (lfsr and 1) == 0) volume else 0

        fun dacOutput(): Int = if (dacEnabled) output() * 2 - 15 else 0

        fun clockLength() {
            if (lengthEnabled && lengthCounter > 0) {
                lengthCounter--
                if (lengthCounter == 0) enabled = false
            }
        }

        fun clockEnvelope() {
            if (envelopePeriod == 0) return
            if (--envelopeTimer <= 0) {
                envelopeTimer = envelopePeriod
                volume = if (envelopeAdd) minOf(15, volume + 1) else maxOf(0, volume - 1)
            }
        }

        fun trigger() {
            enabled = dacEnabled
            if (lengthCounter == 0) lengthCounter = 64
            timer = period()
            volume = envelopeInitial
            envelopeTimer = envelopePeriod
            lfsr = 0x7FFF
        }

        fun reset() {
            enabled = false
            dacEnabled = false
            lengthCounter = 0
            lengthEnabled = false
            envelopeInitial = 0
            envelopeAdd = false
            envelopePeriod = 0
            volume = 0
            envelopeTimer = 0
            divisorCode = 0
            widthMode7 = false
            clockShift = 0
            lfsr = 0x7FFF
        }

        fun saveState(out: DataOutputStream) {
            out.writeBoolean(enabled)
            out.writeBoolean(dacEnabled)
            out.writeInt(lengthCounter)
            out.writeBoolean(lengthEnabled)
            out.writeInt(envelopeInitial)
            out.writeBoolean(envelopeAdd)
            out.writeInt(envelopePeriod)
            out.writeInt(volume)
            out.writeInt(envelopeTimer)
            out.writeInt(divisorCode)
            out.writeBoolean(widthMode7)
            out.writeInt(clockShift)
            out.writeInt(lfsr)
            out.writeInt(timer)
        }

        fun loadState(input: DataInputStream) {
            enabled = input.readBoolean()
            dacEnabled = input.readBoolean()
            lengthCounter = input.readInt()
            lengthEnabled = input.readBoolean()
            envelopeInitial = input.readInt()
            envelopeAdd = input.readBoolean()
            envelopePeriod = input.readInt()
            volume = input.readInt()
            envelopeTimer = input.readInt()
            divisorCode = input.readInt()
            widthMode7 = input.readBoolean()
            clockShift = input.readInt()
            lfsr = input.readInt()
            timer = input.readInt()
        }
    }

    // ---- État global ----

    private val square1 = SquareChannel(hasSweep = true)
    private val square2 = SquareChannel(hasSweep = false)
    private val wave = WaveChannel()
    private val noise = NoiseChannel()

    private var powerOn = true
    private var nr50 = 0x77
    private var nr51 = 0xF3

    /** Registres bruts pour restituer les valeurs écrites à la lecture. */
    private val rawRegisters = IntArray(0x30)

    private var frameTimer = FRAME_SEQUENCER_PERIOD
    private var frameStep = 0
    private var sampleTimer = CYCLES_PER_SAMPLE

    private val ring = ShortArray(RING_CAPACITY)
    private var ringRead = 0
    private var ringWrite = 0
    private var ringCount = 0

    init {
        // Valeurs post-boot DMG des registres de mixage.
        rawRegisters[0x14] = 0x77
        rawRegisters[0x15] = 0xF3
    }

    // ---- Horloge ----

    fun tick(cycles: Int) {
        if (powerOn) {
            square1.step(cycles)
            square2.step(cycles)
            wave.step(cycles)
            noise.step(cycles)
            frameTimer -= cycles
            while (frameTimer <= 0) {
                frameTimer += FRAME_SEQUENCER_PERIOD
                clockFrameSequencer()
            }
        }
        // L'échantillonneur tourne même APU éteint : le silence produit
        // maintient la cadence de la sortie audio.
        sampleTimer -= cycles
        while (sampleTimer <= 0) {
            sampleTimer += CYCLES_PER_SAMPLE
            emitSample()
        }
    }

    private fun clockFrameSequencer() {
        when (frameStep) {
            0, 4 -> clockLengths()
            2, 6 -> {
                clockLengths()
                square1.clockSweep()
            }
            7 -> {
                square1.clockEnvelope()
                square2.clockEnvelope()
                noise.clockEnvelope()
            }
        }
        frameStep = (frameStep + 1) and 7
    }

    private fun clockLengths() {
        square1.clockLength()
        square2.clockLength()
        wave.clockLength()
        noise.clockLength()
    }

    private fun emitSample() {
        var left = 0
        var right = 0
        if (powerOn) {
            val c1 = square1.dacOutput()
            val c2 = square2.dacOutput()
            val c3 = wave.dacOutput()
            val c4 = noise.dacOutput()
            if (nr51 and 0x10 != 0) left += c1
            if (nr51 and 0x20 != 0) left += c2
            if (nr51 and 0x40 != 0) left += c3
            if (nr51 and 0x80 != 0) left += c4
            if (nr51 and 0x01 != 0) right += c1
            if (nr51 and 0x02 != 0) right += c2
            if (nr51 and 0x04 != 0) right += c3
            if (nr51 and 0x08 != 0) right += c4
            left *= (((nr50 shr 4) and 0x07) + 1) * MIX_GAIN
            right *= ((nr50 and 0x07) + 1) * MIX_GAIN
        }
        pushSample(left.toShort(), right.toShort())
    }

    private fun pushSample(left: Short, right: Short) {
        if (ringCount > ring.size - 2) {
            // Tampon plein : on abandonne les plus anciens échantillons.
            ringRead = (ringRead + 2) and (ring.size - 1)
            ringCount -= 2
        }
        ring[ringWrite] = left
        ring[(ringWrite + 1) and (ring.size - 1)] = right
        ringWrite = (ringWrite + 2) and (ring.size - 1)
        ringCount += 2
    }

    /**
     * Copie les échantillons disponibles (paires L/R entrelacées) vers
     * [dest] et retourne le nombre de valeurs copiées (toujours pair).
     */
    fun readSamples(dest: ShortArray): Int {
        var copied = 0
        val max = dest.size and 0x01.inv()
        while (copied < max && ringCount > 0) {
            dest[copied++] = ring[ringRead]
            ringRead = (ringRead + 1) and (ring.size - 1)
            ringCount--
        }
        return copied
    }

    /** Nombre d'échantillons en attente (utilisé par les tests). */
    fun pendingSamples(): Int = ringCount

    // ---- Accès registres ----

    fun read(address: Int): Int {
        if (address in 0xFF30..0xFF3F) {
            return wave.waveRam[address - 0xFF30].toInt() and 0xFF
        }
        val offset = address - 0xFF10
        if (offset !in rawRegisters.indices) return 0xFF
        if (address == 0xFF26) {
            var status = if (powerOn) 0x80 else 0x00
            if (square1.enabled) status = status or 0x01
            if (square2.enabled) status = status or 0x02
            if (wave.enabled) status = status or 0x04
            if (noise.enabled) status = status or 0x08
            return status or 0x70
        }
        return rawRegisters[offset] or READ_MASKS[offset]
    }

    fun write(address: Int, value: Int) {
        if (address in 0xFF30..0xFF3F) {
            wave.waveRam[address - 0xFF30] = value.toByte()
            return
        }
        val offset = address - 0xFF10
        if (offset !in rawRegisters.indices) return
        if (!powerOn && address != 0xFF26) return // APU éteint : écritures ignorées
        rawRegisters[offset] = value and 0xFF

        when (address) {
            0xFF10 -> {
                square1.sweepPeriod = (value shr 4) and 0x07
                square1.sweepNegate = value and 0x08 != 0
                square1.sweepShift = value and 0x07
            }
            0xFF11 -> {
                square1.duty = (value shr 6) and 0x03
                square1.lengthCounter = 64 - (value and 0x3F)
            }
            0xFF12 -> {
                square1.envelopeInitial = (value shr 4) and 0x0F
                square1.envelopeAdd = value and 0x08 != 0
                square1.envelopePeriod = value and 0x07
                square1.dacEnabled = value and 0xF8 != 0
                if (!square1.dacEnabled) square1.enabled = false
            }
            0xFF13 -> square1.frequency = (square1.frequency and 0x700) or value
            0xFF14 -> {
                square1.frequency = (square1.frequency and 0xFF) or ((value and 0x07) shl 8)
                square1.lengthEnabled = value and 0x40 != 0
                if (value and 0x80 != 0) square1.trigger()
            }
            0xFF16 -> {
                square2.duty = (value shr 6) and 0x03
                square2.lengthCounter = 64 - (value and 0x3F)
            }
            0xFF17 -> {
                square2.envelopeInitial = (value shr 4) and 0x0F
                square2.envelopeAdd = value and 0x08 != 0
                square2.envelopePeriod = value and 0x07
                square2.dacEnabled = value and 0xF8 != 0
                if (!square2.dacEnabled) square2.enabled = false
            }
            0xFF18 -> square2.frequency = (square2.frequency and 0x700) or value
            0xFF19 -> {
                square2.frequency = (square2.frequency and 0xFF) or ((value and 0x07) shl 8)
                square2.lengthEnabled = value and 0x40 != 0
                if (value and 0x80 != 0) square2.trigger()
            }
            0xFF1A -> {
                wave.dacEnabled = value and 0x80 != 0
                if (!wave.dacEnabled) wave.enabled = false
            }
            0xFF1B -> wave.lengthCounter = 256 - value
            0xFF1C -> wave.volumeCode = (value shr 5) and 0x03
            0xFF1D -> wave.frequency = (wave.frequency and 0x700) or value
            0xFF1E -> {
                wave.frequency = (wave.frequency and 0xFF) or ((value and 0x07) shl 8)
                wave.lengthEnabled = value and 0x40 != 0
                if (value and 0x80 != 0) wave.trigger()
            }
            0xFF20 -> noise.lengthCounter = 64 - (value and 0x3F)
            0xFF21 -> {
                noise.envelopeInitial = (value shr 4) and 0x0F
                noise.envelopeAdd = value and 0x08 != 0
                noise.envelopePeriod = value and 0x07
                noise.dacEnabled = value and 0xF8 != 0
                if (!noise.dacEnabled) noise.enabled = false
            }
            0xFF22 -> {
                noise.clockShift = (value shr 4) and 0x0F
                noise.widthMode7 = value and 0x08 != 0
                noise.divisorCode = value and 0x07
            }
            0xFF23 -> {
                noise.lengthEnabled = value and 0x40 != 0
                if (value and 0x80 != 0) noise.trigger()
            }
            0xFF24 -> nr50 = value
            0xFF25 -> nr51 = value
            0xFF26 -> {
                val turnOn = value and 0x80 != 0
                if (powerOn && !turnOn) powerDown()
                if (!powerOn && turnOn) {
                    powerOn = true
                    frameStep = 0
                    frameTimer = FRAME_SEQUENCER_PERIOD
                }
            }
        }
    }

    private fun powerDown() {
        powerOn = false
        square1.reset()
        square2.reset()
        wave.reset()
        noise.reset()
        nr50 = 0
        nr51 = 0
        rawRegisters.fill(0)
    }

    // ---- Sérialisation (le tampon d'échantillons n'est pas un état) ----

    fun saveState(out: DataOutputStream) {
        out.writeBoolean(powerOn)
        out.writeInt(nr50)
        out.writeInt(nr51)
        for (register in rawRegisters) out.writeInt(register)
        out.writeInt(frameTimer)
        out.writeInt(frameStep)
        out.writeInt(sampleTimer)
        square1.saveState(out)
        square2.saveState(out)
        wave.saveState(out)
        noise.saveState(out)
    }

    fun loadState(input: DataInputStream) {
        powerOn = input.readBoolean()
        nr50 = input.readInt()
        nr51 = input.readInt()
        for (i in rawRegisters.indices) rawRegisters[i] = input.readInt()
        frameTimer = input.readInt()
        frameStep = input.readInt()
        sampleTimer = input.readInt()
        square1.loadState(input)
        square2.loadState(input)
        wave.loadState(input)
        noise.loadState(input)
        ringRead = 0
        ringWrite = 0
        ringCount = 0
    }

    companion object {
        /** 4 194 304 / 128 = 32 768 Hz exactement. */
        const val SAMPLE_RATE_HZ = 32768
        const val CYCLES_PER_SAMPLE = 128
        const val FRAME_SEQUENCER_PERIOD = 8192

        /** ~4 trames d'avance maximum (paires stéréo), puissance de deux. */
        private const val RING_CAPACITY = 8192

        /** 4 canaux × 15 × 8 (volume maître) × gain = plafond < 32767. */
        private const val MIX_GAIN = 64

        private val DUTY_TABLE = arrayOf(
            intArrayOf(0, 0, 0, 0, 0, 0, 0, 1), // 12,5 %
            intArrayOf(1, 0, 0, 0, 0, 0, 0, 1), // 25 %
            intArrayOf(1, 0, 0, 0, 0, 1, 1, 1), // 50 %
            intArrayOf(0, 1, 1, 1, 1, 1, 1, 0), // 75 %
        )

        private val DIVISORS = intArrayOf(8, 16, 32, 48, 64, 80, 96, 112)

        /** Bits non câblés lus à 1, indexés par offset depuis 0xFF10. */
        private val READ_MASKS = intArrayOf(
            0x80, 0x3F, 0x00, 0xFF, 0xBF, // NR10-NR14
            0xFF, 0x3F, 0x00, 0xFF, 0xBF, // (FF15) NR21-NR24
            0x7F, 0xFF, 0x9F, 0xFF, 0xBF, // NR30-NR34
            0xFF, 0xFF, 0x00, 0x00, 0xBF, // (FF1F) NR41-NR44
            0x00, 0x00, 0x70, // NR50-NR52
            0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, // FF27-FF2F
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // Wave RAM (non utilisé)
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        )
    }
}
