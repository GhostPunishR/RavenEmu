package com.ravenemu.emulation.api.audio

/**
 * Maintient l'état de préremplissage d'une sortie audio en flux.
 *
 * Une piste démarrée avant de contenir assez d'échantillons consomme son premier
 * bloc pendant que le suivant est encore calculé. Elle reste alors au bord de la
 * rupture même si le tampon alloué est grand. Ce contrôleur retarde le démarrage
 * jusqu'au seuil demandé et recommence le préremplissage après une rupture.
 */
class AudioBufferPrimer(
    private val startThresholdSamples: Int,
) {
    init {
        require(startThresholdSamples > 0) { "Seuil de préremplissage invalide" }
    }

    var queuedSamples: Int = 0
        private set

    var playbackStarted: Boolean = false
        private set

    private var observedUnderruns: Int = 0

    /**
     * Enregistre [sampleCount] échantillons entrelacés écrits dans la piste.
     * Retourne vrai une seule fois, lorsque la lecture peut commencer.
     */
    fun onSamplesQueued(sampleCount: Int): Boolean {
        if (sampleCount <= 0 || playbackStarted) return false
        queuedSamples = (queuedSamples.toLong() + sampleCount)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        if (queuedSamples < startThresholdSamples) return false
        playbackStarted = true
        return true
    }

    /**
     * Observe le compteur cumulatif de la plateforme. Retourne vrai lorsqu'une
     * nouvelle rupture impose d'arrêter la piste et de la préremplir à nouveau.
     */
    fun onUnderrunCount(totalUnderruns: Int): Boolean {
        val normalized = totalUnderruns.coerceAtLeast(0)
        val increased = normalized > observedUnderruns
        observedUnderruns = maxOf(observedUnderruns, normalized)
        if (!increased || !playbackStarted) return false
        playbackStarted = false
        queuedSamples = 0
        return true
    }

    /** Repart avec une piste vide et le compteur actuel de la plateforme. */
    fun reset(currentUnderruns: Int = 0) {
        queuedSamples = 0
        playbackStarted = false
        observedUnderruns = currentUnderruns.coerceAtLeast(0)
    }
}
