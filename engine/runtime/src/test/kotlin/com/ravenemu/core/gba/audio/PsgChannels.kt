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
    private var accumulator = 0

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

    /**
     * Avance le canal en **cumulant sa sortie le temps qu'elle dure**.
     *
     * Le compteur est découpé aux frontières du rapport cyclique, et chaque
     * tranche verse sa valeur pondérée par sa durée. C'est ce cumul, divisé par
     * la fenêtre d'un échantillon, qui devient l'échantillon. Prendre la valeur
     * qui se trouve là à l'instant du prélèvement replierait dans l'audible
     * toutes les harmoniques au-dessus de la moitié du débit de sortie — et un
     * signal carré en produit en quantité.
     */
    fun tick(cycles: Int) {
        if (!enabled) return
        val period = maxOf(1, (2048 - frequency) * 4)
        var remaining = cycles
        while (remaining > 0) {
            // Un compteur à plat est ramené à une période : sans cela une
            // tranche de longueur nulle ferait tourner la boucle sans avancer.
            if (timer <= 0) timer = period
            val slice = minOf(timer, remaining)
            accumulator += output() * slice
            timer -= slice
            remaining -= slice
            if (timer <= 0) {
                timer = period
                dutyStep = (dutyStep + 1) and 7
            }
        }
    }

    /**
     * Rend le cumul de la fenêtre écoulée et le remet à zéro.
     *
     * Le cumul sort **tel quel**, sans division : c'est le mélangeur qui divise,
     * une seule fois, à la fin. Tout reste ainsi en entiers exacts, sans arrondi
     * intermédiaire — ce que le portage C++ fait à l'identique, et c'est à cette
     * condition que la comparaison trame par trame des deux a un sens.
     */
    fun drainAccumulator(): Int {
        val total = accumulator
        accumulator = 0
        return total
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
        if (enabled && (DUTY_MASKS[duty] ushr dutyStep) and 1 != 0) envelope.volume else 0

    fun reset() {
        enabled = false
        timer = 0
        dutyStep = 0
        lengthCounter = 0
        accumulator = 0
    }

    private companion object {
        /**
         * Motifs de rapport cyclique, un bit par pas (bit 0 = premier pas). Un
         * masque entier évite le double déréférencement d'un tableau de tableaux
         * dans le mixage, appelé pour chaque échantillon.
         */
        val DUTY_MASKS = intArrayOf(
            0b1000_0000, // 12,5 % : 0,0,0,0,0,0,0,1
            0b1000_0001, // 25 %   : 1,0,0,0,0,0,0,1
            0b1110_0001, // 50 %   : 1,0,0,0,0,1,1,1
            0b0111_1110, // 75 %   : 0,1,1,1,1,1,1,0
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
    private var accumulator = 0

    fun trigger() {
        enabled = dacEnabled
        if (lengthCounter == 0) lengthCounter = 256
        timer = (2048 - frequency) * 2
        position = 0
    }

    /** Même cumul que les canaux carrés, pour la même raison. */
    fun tick(cycles: Int) {
        if (!enabled) return
        val period = maxOf(1, (2048 - frequency) * 2)
        var remaining = cycles
        while (remaining > 0) {
            if (timer <= 0) timer = period
            val slice = minOf(timer, remaining)
            accumulator += output() * slice
            timer -= slice
            remaining -= slice
            if (timer <= 0) {
                timer = period
                position = (position + 1) and 31
            }
        }
    }

    /**
     * Rend le cumul de la fenêtre écoulée et le remet à zéro.
     *
     * Le cumul sort **tel quel**, sans division : c'est le mélangeur qui divise,
     * une seule fois, à la fin. Tout reste ainsi en entiers exacts, sans arrondi
     * intermédiaire — ce que le portage C++ fait à l'identique, et c'est à cette
     * condition que la comparaison trame par trame des deux a un sens.
     */
    fun drainAccumulator(): Int {
        val total = accumulator
        accumulator = 0
        return total
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
        accumulator = 0
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
    private var accumulator = 0
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
        return maxOf(1, divisor shl shiftClock)
    }

    /**
     * Avance le registre à décalage en cumulant chaque état le temps qu'il dure.
     *
     * C'est le canal où le repliement s'entend le plus : son spectre monte
     * jusqu'à la fréquence de décalage, très au-dessus de la moitié du débit de
     * sortie. Prélevé sans moyenne, il rend un souffle métallique au lieu d'un
     * bruit plat.
     */
    fun tick(cycles: Int) {
        if (!enabled) return
        val period = period()
        var remaining = cycles
        while (remaining > 0) {
            if (timer <= 0) timer = period
            val slice = minOf(timer, remaining)
            accumulator += output() * slice
            timer -= slice
            remaining -= slice
            if (timer <= 0) {
                timer = period
                val feedback = (lfsr and 1) xor ((lfsr ushr 1) and 1)
                lfsr = (lfsr ushr 1) or (feedback shl 14)
                if (widthMode) {
                    lfsr = (lfsr and 0x40.inv()) or (feedback shl 6)
                }
            }
        }
    }

    /**
     * Rend le cumul de la fenêtre écoulée et le remet à zéro.
     *
     * Le cumul sort **tel quel**, sans division : c'est le mélangeur qui divise,
     * une seule fois, à la fin. Tout reste ainsi en entiers exacts, sans arrondi
     * intermédiaire — ce que le portage C++ fait à l'identique, et c'est à cette
     * condition que la comparaison trame par trame des deux a un sens.
     */
    fun drainAccumulator(): Int {
        val total = accumulator
        accumulator = 0
        return total
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
        accumulator = 0
    }
}
