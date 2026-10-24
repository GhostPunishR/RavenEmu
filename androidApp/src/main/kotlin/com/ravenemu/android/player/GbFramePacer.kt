package com.ravenemu.android.player

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
 * ### Calée sur l'écran
 *
 * Quand [nextWaitNanos] reçoit les synchronisations de l'écran et que celui-ci
 * bat à un multiple de la cadence du jeu (voir [DisplayCadence]), chaque trame
 * dure exactement n synchronisations. Son échéance est placée pour que l'image
 * soit prête au milieu de l'intervalle entre deux synchronisations : l'écart
 * le plus grand possible avec chacune, pour qu'aucune image ne passe de l'une
 * à l'autre. Le temps d'une trame est mesuré au fil du jeu pour cela.
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
    private var frameStartNanos = -1L
    private var workNanos = 0L

    /** Durée d'une trame : celle de la console, ou n synchronisations quand elle est calée. */
    var framePeriodNanos = periodNanos
        private set

    /**
     * Vitesse du jeu par rapport à la console : un hors calage, 1,0046 calé sur
     * un écran à 60 Hz pour 59,73 images par seconde.
     */
    val speed: Double get() = periodNanos.toDouble() / framePeriodNanos

    /** Repart de l'instant [now] : à appeler au démarrage et après une pause. */
    fun reset(now: Long) {
        dueNanos = now
        started = true
        consecutiveSkips = 0
        frameStartNanos = -1L
    }

    /** Avant d'émuler la trame : son image doit-elle être affichée ? */
    fun shouldRender(now: Long): Boolean {
        if (!started) reset(now)
        frameStartNanos = now
        val late = now - dueNanos > framePeriodNanos
        if (late && consecutiveSkips < maxConsecutiveSkips) {
            consecutiveSkips++
            return false
        }
        consecutiveSkips = 0
        return true
    }

    /**
     * Après la trame : nanosecondes à attendre avant la suivante, zéro si la
     * session est en retard et doit enchaîner. [vsyncNanos] est la dernière
     * synchronisation connue de l'écran et [displayPeriodNanos] sa période ;
     * 0 quand elles sont inconnues, et la trame suit alors la console.
     */
    fun nextWaitNanos(now: Long, vsyncNanos: Long = 0L, displayPeriodNanos: Long = 0L): Long {
        if (!started) reset(now)
        if (frameStartNanos >= 0L) {
            // Moyenne glissante sur huit trames : un pic isolé ne déplace pas l'échéance.
            workNanos += ((now - frameStartNanos).coerceIn(0L, maxLagNanos) - workNanos) / 8
            frameStartNanos = -1L
        }
        val vsyncs = if (vsyncNanos > 0L) DisplayCadence.vsyncsPerFrame(periodNanos, displayPeriodNanos) else 0
        framePeriodNanos = if (vsyncs > 0) vsyncs * displayPeriodNanos else periodNanos
        dueNanos += framePeriodNanos
        if (vsyncs > 0) {
            // Grille des départs dont l'image est prête au milieu d'un intervalle.
            val anchor = vsyncNanos + displayPeriodNanos / 2 - workNanos
            val steps = Math.floorDiv(dueNanos - anchor + displayPeriodNanos / 2, displayPeriodNanos)
            dueNanos = anchor + steps * displayPeriodNanos
        }
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
