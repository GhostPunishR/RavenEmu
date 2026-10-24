package com.ravenemu.android.player

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Calage de la cadence du jeu sur celle de l'écran.
 *
 * ### Le problème
 *
 * Un minuteur à la cadence de la console ne tombe jamais juste sur l'écran.
 * À 59,73 images par seconde sur un écran à 60 Hz, une image reste affichée
 * deux fois toutes les 3,7 s environ, ce qui se voit sur un défilement. Un
 * écran à 120 Hz fait de même toutes les 1,9 s, et un écran à 90 Hz alterne
 * sans cesse une et deux synchronisations par image.
 *
 * ### La solution
 *
 * Quand l'écran bat à un multiple de la cadence du jeu, à [MAX_SKEW] près, le
 * lecteur émule exactement une image toutes les n synchronisations, calées
 * sur elles. Le jeu tourne alors un peu plus vite ou plus lentement que la
 * console (0,46 % plus vite à 60 Hz). Le son suit, car la sortie audio reçoit
 * cette vitesse : l'écart de hauteur reste inaudible.
 *
 * C'est l'idée du « vsync + contrôle dynamique du débit » de RetroArch,
 * écrite pour RavenEmu. Fonctions pures, sans horloge ni Android.
 */
object DisplayCadence {
    /** Écart maximal entre la cadence du jeu et celle de l'écran pour s'y caler. */
    const val MAX_SKEW = 0.05

    /** Période, en nanosecondes, d'une fréquence en hertz ; 0 si elle n'a pas de sens. */
    fun periodNanos(hertz: Double): Long =
        if (hertz > 0.0 && hertz.isFinite()) (1_000_000_000.0 / hertz).roundToLong() else 0L

    /**
     * Synchronisations d'écran par image du jeu : 1 à 60 Hz, 2 à 120 Hz. Rend 0
     * quand l'écran ne s'y prête pas, à 90 Hz par exemple.
     */
    fun vsyncsPerFrame(gamePeriodNanos: Long, displayPeriodNanos: Long): Int {
        if (gamePeriodNanos <= 0L || displayPeriodNanos <= 0L) return 0
        val count = (gamePeriodNanos.toDouble() / displayPeriodNanos).roundToLong().coerceAtLeast(1L)
        val skew = abs(count * displayPeriodNanos - gamePeriodNanos).toDouble() / gamePeriodNanos
        return if (skew <= MAX_SKEW && count <= Int.MAX_VALUE) count.toInt() else 0
    }

    /**
     * Fréquence d'écran à demander pour un jeu à [gameFps], parmi [rates] : les
     * fréquences vers lesquelles l'écran passe sans couper l'image. On garde
     * [current] si le jeu s'y cale ; sinon on prend la plus haute qui convient
     * (120 Hz plutôt que 60 Hz pour un écran à 90 Hz), qui affiche plus tôt.
     * Rend null quand aucune ne convient : l'écran reste tel quel.
     *
     * Demander même la fréquence courante n'est pas inutile : elle empêche le
     * système de passer à 90 Hz au moindre toucher, comme le font certains
     * téléphones.
     */
    fun preferredRefreshRate(rates: Collection<Float>, current: Float, gameFps: Double): Float? {
        val game = periodNanos(gameFps)
        fun fits(rate: Float) = vsyncsPerFrame(game, periodNanos(rate.toDouble())) > 0
        if (fits(current)) return current
        return rates.filter(::fits).maxOrNull()
    }
}
