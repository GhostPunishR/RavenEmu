package com.ravenemu.android

/**
 * Cadence du lecteur : quand lancer la trame suivante, et faut-il l'afficher.
 *
 * ### Ce que le lecteur doit tenir
 *
 * Le son est produit par le moteur au rythme des trames émulées. Si la
 * session émule moins de trames que la console par seconde, la sortie audio
 * manque d'échantillons et se coupe, quel que soit son réglage. Le temps
 * d'émulation passe donc avant l'image.
 *
 * ### Rattraper plutôt qu'abandonner
 *
 * L'échéance de chaque trame avance d'une période exacte, en nanosecondes.
 * Une trame en retard est rattrapée par la suivante, sans attente. Le retard
 * n'est abandonné qu'au-delà de [maxLagNanos] (cent millisecondes, comme
 * RavenEmu 0.8.1) : abandonner dès une période de retard, c'est perdre le son
 * de cette trame au moindre à-coup du téléphone.
 *
 * ### Sauter l'image, jamais l'émulation
 *
 * Une trame qui démarre avec plus d'une période de retard est émulée et
 * entendue normalement, mais son image n'est ni convertie ni affichée. Au plus
 * [maxConsecutiveSkips] images de suite sont sautées, pour que l'écran ne
 * reste jamais figé.
 *
 * Classe pure, sans horloge propre : chaque appel reçoit l'instant présent.
 */
class GbFramePacer(
    private val periodNanos: Long,
    private val maxLagNanos: Long = DEFAULT_MAX_LAG_NANOS,
    private val maxConsecutiveSkips: Int = DEFAULT_MAX_CONSECUTIVE_SKIPS,
) {
    init {
        require(periodNanos > 0) { "Période de trame invalide." }
        require(maxLagNanos >= periodNanos) { "Retard maximal inférieur à une période." }
        require(maxConsecutiveSkips >= 0) { "Nombre d'images sautées invalide." }
    }

    private var dueNanos = 0L
    private var started = false
    private var consecutiveSkips = 0

    /** Repart de l'instant [now] : à appeler au démarrage et après une pause. */
    fun reset(now: Long) {
        dueNanos = now
        started = true
        consecutiveSkips = 0
    }

    /** Avant d'émuler la trame : son image doit-elle être affichée ? */
    fun shouldRender(now: Long): Boolean {
        if (!started) reset(now)
        val late = now - dueNanos > periodNanos
        if (late && consecutiveSkips < maxConsecutiveSkips) {
            consecutiveSkips++
            return false
        }
        consecutiveSkips = 0
        return true
    }

    /**
     * Après la trame : nanosecondes à attendre avant la suivante, zéro si la
     * session est en retard et doit enchaîner.
     */
    fun nextWaitNanos(now: Long): Long {
        if (!started) reset(now)
        dueNanos += periodNanos
        val wait = dueNanos - now
        if (wait < -maxLagNanos) {
            // Retard trop grand pour être rattrapé : on repart d'ici.
            dueNanos = now
            return 0L
        }
        return wait.coerceAtLeast(0L)
    }

    companion object {
        const val DEFAULT_MAX_LAG_NANOS = 100_000_000L
        const val DEFAULT_MAX_CONSECUTIVE_SKIPS = 3
    }
}
