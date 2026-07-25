package com.ravenemu.core.gba.audio

/**
 * Canaux PSG hérités de la Game Boy, réimplémentés pour la Game Boy Advance.
 *
 * Ils tournent sur l'horloge audio de 4,194 MHz (le quart de l'horloge CPU du
 * GBA) : les périodes et le séquenceur de trames suivent donc les mêmes
 * formules que sur Game Boy. Chaque canal produit un niveau `0..15` ; le mixage
 * et la mise à l'échelle sont réalisés par [GbaApu].
 *
 * Ce fichier est écrit pour `gba-core` et n'introduit **aucune dépendance**
 * vers `gameboy-core`, qui reste autonome.
 */

/** Enveloppe de volume commune aux canaux carrés et au bruit. */
internal class Envelope {
    var initialVolume = 0
    var increasing = false
    var period = 0

    var volume = 0
        private set
    private var timer = 0

    fun trigger() {
        volume = initialVolume
        timer = period
    }

    fun clock() {
        if (period == 0) return
        if (timer > 0) timer--
        if (timer != 0) return
        timer = period
        if (increasing && volume < 15) volume++
        else if (!increasing && volume > 0) volume--
    }

    fun writeRegister(value: Int) {
        initialVolume = (value ushr 4) and 0xF
        increasing = value and 0x08 != 0
        period = value and 0x07
        // Un DAC éteint (volume nul et sens décroissant) coupe le canal.
        volume = initialVolume
    }

    /** `true` si le convertisseur du canal est alimenté. */
    fun dacEnabled(): Boolean = initialVolume != 0 || increasing
}

/** Canal à onde carrée, avec balayage de fréquence optionnel (canal 1). */
internal class SquareChannel(private val hasSweep: Boolean) {

    var enabled = false
    var duty = 2
    var frequency = 0
    var lengthCounter = 0
    var lengthEnabled = false
    val envelope = Envelope()

    private var timer = 0
    private var dutyStep = 0

    // Balayage (canal 1 uniquement).
    private var sweepPeriod = 0
    private var sweepNegate = false
    private var sweepShift = 0
    private var sweepTimer = 0
    private var sweepShadow = 0
    private var sweepActive = false

    fun trigger() {
        enabled = envelope.dacEnabled()
        if (lengthCounter == 0) lengthCounter = 64
        timer = (2048 - frequency) * 4
        envelope.trigger()
        if (hasSweep) {
            sweepShadow = frequency
            sweepTimer = if (sweepPeriod == 0) 8 else sweepPeriod
            sweepActive = sweepPeriod != 0 || sweepShift != 0
            if (sweepShift != 0) computeSweep(apply = false)
        }
    }

    fun writeSweep(value: Int) {
        sweepPeriod = (value ushr 4) and 0x7
        sweepNegate = value and 0x08 != 0
        sweepShift = value and 0x07
    }

    fun tick(cycles: Int) {
        if (!enabled) return
        timer -= cycles
        while (timer <= 0) {
            timer += (2048 - frequency) * 4
            dutyStep = (dutyStep + 1) and 7
        }
    }

    fun clockLength() {
        if (!lengthEnabled || lengthCounter == 0) return
        lengthCounter--
        if (lengthCounter == 0) enabled = false
    }

    fun clockSweep() {
        if (!hasSweep || !sweepActive) return
        if (sweepTimer > 0) sweepTimer--
        if (sweepTimer != 0) return
        sweepTimer = if (sweepPeriod == 0) 8 else sweepPeriod
        if (sweepPeriod != 0) computeSweep(apply = true)
    }

    private fun computeSweep(apply: Boolean) {
        val delta = sweepShadow shr sweepShift
        val next = if (sweepNegate) sweepShadow - delta else sweepShadow + delta
        if (next > 2047) {
            enabled = false
            return
        }
        if (apply && sweepShift != 0) {
            sweepShadow = next
            frequency = next
        }
    }

    /** Niveau courant `0..15`. */
    fun output(): Int =
        if (enabled && DUTY_TABLE[duty][dutyStep] != 0) envelope.volume else 0

    fun reset() {
        enabled = false
        timer = 0
        dutyStep = 0
        lengthCounter = 0
    }

    private companion object {
        val DUTY_TABLE = arrayOf(
            intArrayOf(0, 0, 0, 0, 0, 0, 0, 1), // 12,5 %
            intArrayOf(1, 0, 0, 0, 0, 0, 0, 1), // 25 %
            intArrayOf(1, 0, 0, 0, 0, 1, 1, 1), // 50 %
            intArrayOf(0, 1, 1, 1, 1, 1, 1, 0), // 75 %
        )
    }
}

/** Canal à table d'onde : 32 échantillons de 4 bits en RAM d'onde. */
internal class WaveChannel {

    var enabled = false
    var dacEnabled = false
    var frequency = 0
    var lengthCounter = 0
    var lengthEnabled = false
    /** Volume : 0 = muet, 1 = 100 %, 2 = 50 %, 3 = 25 %. */
    var volumeCode = 0

    val waveRam = IntArray(32)

    private var timer = 0
    private var position = 0

    fun trigger() {
        enabled = dacEnabled
        if (lengthCounter == 0) lengthCounter = 256
        timer = (2048 - frequency) * 2
        position = 0
    }

    fun tick(cycles: Int) {
        if (!enabled) return
        timer -= cycles
        while (timer <= 0) {
            timer += (2048 - frequency) * 2
            position = (position + 1) and 31
        }
    }

    fun clockLength() {
        if (!lengthEnabled || lengthCounter == 0) return
        lengthCounter--
        if (lengthCounter == 0) enabled = false
    }

    fun output(): Int {
        if (!enabled || !dacEnabled) return 0
        val sample = waveRam[position]
        return when (volumeCode) {
            0 -> 0
            1 -> sample
            2 -> sample shr 1
            else -> sample shr 2
        }
    }

    fun reset() {
        enabled = false
        timer = 0
        position = 0
        lengthCounter = 0
    }
}

/** Canal de bruit : registre à décalage à rétroaction linéaire. */
internal class NoiseChannel {

    var enabled = false
    var lengthCounter = 0
    var lengthEnabled = false
    val envelope = Envelope()

    private var shiftClock = 0
    private var widthMode = false
    private var divisorCode = 0
    private var timer = 0
    private var lfsr = 0x7FFF

    fun trigger() {
        enabled = envelope.dacEnabled()
        if (lengthCounter == 0) lengthCounter = 64
        timer = period()
        lfsr = 0x7FFF
        envelope.trigger()
    }

    fun writePolynomial(value: Int) {
        shiftClock = (value ushr 4) and 0xF
        widthMode = value and 0x08 != 0
        divisorCode = value and 0x07
    }

    private fun period(): Int {
        val divisor = if (divisorCode == 0) 8 else divisorCode * 16
        return divisor shl shiftClock
    }

    fun tick(cycles: Int) {
        if (!enabled) return
        timer -= cycles
        while (timer <= 0) {
            timer += period()
            val feedback = (lfsr and 1) xor ((lfsr ushr 1) and 1)
            lfsr = (lfsr ushr 1) or (feedback shl 14)
            if (widthMode) {
                lfsr = (lfsr and 0x40.inv()) or (feedback shl 6)
            }
        }
    }

    fun clockLength() {
        if (!lengthEnabled || lengthCounter == 0) return
        lengthCounter--
        if (lengthCounter == 0) enabled = false
    }

    fun output(): Int =
        if (enabled && (lfsr and 1) == 0) envelope.volume else 0

    fun reset() {
        enabled = false
        timer = 0
        lfsr = 0x7FFF
        lengthCounter = 0
    }
}
