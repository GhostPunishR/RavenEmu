package com.ravenemu.emulation.api.audio

/**
 * Ajuste progressivement la durée d'un bloc audio lorsque l'émulation ne peut
 * pas encore tenir la cadence native.
 *
 * Une échelle de 1 conserve le débit original. Une échelle inférieure produit
 * davantage de trames de sortie et évite les trous dans le périphérique audio.
 * La correction est lissée et bornée afin de rester stable entre deux trames.
 */
class AudioRateController(
    private val sourceRateHz: Int,
    private val minimumScale: Double = DEFAULT_MINIMUM_SCALE,
    private val smoothing: Double = DEFAULT_SMOOTHING,
) {
    init {
        require(sourceRateHz > 0) { "Débit source invalide" }
        require(minimumScale in 0.5..1.0) { "Échelle minimale invalide" }
        require(smoothing > 0.0 && smoothing <= 1.0) { "Lissage invalide" }
    }

    var scale: Double = 1.0
        private set

    /**
     * Calcule l'échelle à appliquer à [inputFrames] pour couvrir
     * [targetDurationNanos]. Une cible plus courte que le bloc natif ne
     * provoque aucune accélération.
     */
    fun update(inputFrames: Int, targetDurationNanos: Long): Double {
        if (inputFrames <= 0 || targetDurationNanos <= 0L) return scale

        val nativeDurationNanos =
            inputFrames.toDouble() * NANOS_PER_SECOND / sourceRateHz
        val requested = (nativeDurationNanos / targetDurationNanos)
            .coerceIn(minimumScale, 1.0)

        scale += (requested - scale) * smoothing
        if (scale > NATIVE_SNAP_THRESHOLD) scale = 1.0
        return scale
    }

    fun reset() {
        scale = 1.0
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000.0
        const val DEFAULT_MINIMUM_SCALE = 0.80
        const val DEFAULT_SMOOTHING = 0.35
        const val NATIVE_SNAP_THRESHOLD = 0.999
    }
}
