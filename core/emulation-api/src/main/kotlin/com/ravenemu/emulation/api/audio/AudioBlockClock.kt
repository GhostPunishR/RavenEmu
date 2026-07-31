package com.ravenemu.emulation.api.audio

/**
 * Mesure l'intervalle réel entre deux livraisons de blocs audio.
 *
 * Le temps de calcul du moteur ne suffit pas : le rendu, les rappels de
 * plateforme et l'ordonnancement s'ajoutent avant la livraison suivante.
 * Sous-estimer cet intervalle de quelques dixièmes de milliseconde finit par
 * vider même un tampon correctement prérempli.
 */
class AudioBlockClock(
    private val minimumPeriodNanos: Long,
    private val maximumPeriodMultiplier: Long = DEFAULT_MAXIMUM_PERIOD_MULTIPLIER,
) {
    init {
        require(minimumPeriodNanos > 0L) { "Période minimale invalide" }
        require(maximumPeriodMultiplier >= 1L) { "Multiplicateur maximal invalide" }
    }

    private val maximumPeriodNanos = saturatedMultiply(
        minimumPeriodNanos,
        maximumPeriodMultiplier,
    )
    private var previousMarkNanos: Long? = null

    /**
     * Enregistre l'instant [nowNanos] et retourne la durée que le nouveau bloc
     * doit couvrir. Le premier bloc utilise la période native.
     */
    fun mark(nowNanos: Long): Long {
        val previous = previousMarkNanos
        previousMarkNanos = nowNanos
        if (previous == null) return minimumPeriodNanos
        val elapsed = nowNanos - previous
        if (elapsed <= 0L) return minimumPeriodNanos
        return elapsed.coerceIn(minimumPeriodNanos, maximumPeriodNanos)
    }

    fun reset() {
        previousMarkNanos = null
    }

    private fun saturatedMultiply(left: Long, right: Long): Long {
        if (left > Long.MAX_VALUE / right) return Long.MAX_VALUE
        return left * right
    }

    private companion object {
        const val DEFAULT_MAXIMUM_PERIOD_MULTIPLIER = 4L
    }
}
