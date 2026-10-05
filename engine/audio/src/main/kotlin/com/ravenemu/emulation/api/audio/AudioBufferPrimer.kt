package com.ravenemu.emulation.api.audio

/**
 * Maintient l'état de préremplissage d'une sortie audio en flux.
 *
 * Une piste démarrée avant de contenir assez d'échantillons consomme son premier
 * bloc pendant que le suivant est encore calculé. Elle reste alors au bord de la
 * rupture même si le tampon alloué est grand. Ce contrôleur retarde donc le
 * démarrage jusqu'au seuil demandé.
 *
 * ### Une rupture ne déclenche plus de nouveau préremplissage
 *
 * Elle le faisait, et c'était le mauvais réflexe. Arrêter la piste, la vider et
 * repréremplir jette l'avance déjà calculée — jusqu'à huit trames vidéo — puis
 * impose six trames de silence avant de rejouer : autour de cent millisecondes
 * de blanc, garanties, pour réparer une interruption qui en durait quelques-unes.
 *
 * Ne rien faire est strictement meilleur. Après une rupture la file est vide,
 * mais la piste continue de jouer ce qui arrive, et l'écriture bloquante ne
 * bloque que sur une file **pleine** : en dessous, elle rend la main
 * immédiatement. Le thread d'émulation calcule une trame en quelques
 * millisecondes et en produit seize de son, si bien que la réserve se reconstitue
 * bien plus vite que le temps réel, en quelques blocs, sans que la piste
 * s'arrête une seule fois. Il n'existe pas de cas où le vidage produise moins de
 * silence que l'attente.
 *
 * Le préremplissage garde donc son seul rôle utile : le démarrage, et la reprise
 * après un vidage volontaire ([reset], sur pause ou arrêt de session).
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

    /** Repart avec une piste vide, après un vidage volontaire. */
    fun reset() {
        queuedSamples = 0
        playbackStarted = false
    }
}
