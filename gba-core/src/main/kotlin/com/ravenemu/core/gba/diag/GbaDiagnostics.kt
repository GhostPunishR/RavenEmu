package com.ravenemu.core.gba.diag

/**
 * Compteurs et événements de diagnostic du moteur Game Boy Advance.
 *
 * Deux usages :
 * - **mesure** : nombre d'instructions par trame, dernier appel logiciel, dernière
 *   interruption, sous-alimentations audio — de quoi alimenter une surcouche de
 *   débogage ou un banc d'essai ;
 * - **signalement** : les situations qu'un jeu ne devrait pas provoquer (appel
 *   logiciel non pris en charge, instruction indéfinie, accès mémoire hors plan,
 *   attente d'une interruption qui ne vient pas, flux compressé invalide).
 *
 * La journalisation est **volontairement bridée** : chaque catégorie d'événement
 * n'est transmise que [MAX_REPORTS_PER_EVENT] fois, alors que son compteur
 * continue de progresser. Un jeu qui déclenche une instruction indéfinie dans une
 * boucle ne noie donc pas le journal, mais son compteur révèle l'ampleur du
 * problème. Le détail textuel n'est construit que si quelqu'un écoute et que le
 * quota n'est pas épuisé.
 *
 * Cette classe reste indépendante d'Android : elle n'expose qu'un rappel, que la
 * couche applicative branche sur son propre journal, en Debug uniquement.
 */
class GbaDiagnostics {

    /** Catégories d'anomalies signalées par le moteur. */
    enum class Event {
        /** Appel logiciel du BIOS non implémenté. */
        UNSUPPORTED_SWI,

        /** Motif d'instruction non reconnu par un décodeur. */
        UNDEFINED_INSTRUCTION,

        /** Accès à une adresse hors du plan mémoire. */
        UNSUPPORTED_ACCESS,

        /** Attente d'une interruption qui ne survient pas. */
        MISSING_INTERRUPT,

        /** Flux compressé incohérent ou tronqué. */
        DECOMPRESSION_ERROR,
    }

    /** Rappel de journalisation, branché par la couche applicative. */
    var onEvent: ((Event, String) -> Unit)? = null

    private val counts = IntArray(Event.entries.size)
    private val reported = IntArray(Event.entries.size)

    // ---- Mesures ----

    /** Instructions exécutées depuis le début de la trame courante. */
    var instructionsThisFrame = 0
        private set

    /** Instructions exécutées pendant la trame précédente. */
    var instructionsLastFrame = 0
        private set

    /** Numéro du dernier appel logiciel traité, ou -1. */
    var lastSwi = -1
        private set

    /** Masque de la dernière interruption levée, ou 0. */
    var lastInterruptMask = 0
        private set

    /** Nombre de fois où l'audio a été réclamé alors qu'aucun échantillon n'était prêt. */
    var audioUnderruns = 0
        private set

    /** Cycles écoulés dans l'attente d'interruption en cours. */
    var waitCycles = 0
        private set

    fun onInstruction() {
        instructionsThisFrame++
    }

    /** À appeler au début de chaque trame : bascule le compteur d'instructions. */
    fun beginFrame() {
        instructionsLastFrame = instructionsThisFrame
        instructionsThisFrame = 0
    }

    fun onSwi(number: Int) {
        lastSwi = number
    }

    fun onInterrupt(mask: Int) {
        lastInterruptMask = mask
    }

    fun onAudioUnderrun() {
        audioUnderruns++
    }

    /**
     * Suit la durée d'une pause en attente d'interruption. Passé un seuil de deux
     * trames, l'attente est signalée une fois : c'est le symptôme d'un jeu bloqué
     * parce qu'une source d'interruption n'est jamais levée.
     */
    fun onWaitStep(cycles: Int, mask: Int) {
        val before = waitCycles
        waitCycles += cycles
        // Signalé au moment précis du franchissement du seuil, donc une seule
        // fois par attente.
        if (before < STUCK_WAIT_CYCLES && waitCycles >= STUCK_WAIT_CYCLES) {
            report(Event.MISSING_INTERRUPT) {
                "attente de l'interruption 0x${mask.toString(16)} depuis $waitCycles cycles"
            }
        }
    }

    /** L'attente est terminée : le compteur repart de zéro. */
    fun onWaitResolved() {
        waitCycles = 0
    }

    // ---- Signalement ----

    /**
     * Enregistre un événement. [detail] n'est évalué que si l'événement est
     * effectivement journalisé.
     */
    fun report(event: Event, detail: () -> String) {
        counts[event.ordinal]++
        val listener = onEvent ?: return
        if (reported[event.ordinal] >= MAX_REPORTS_PER_EVENT) return
        reported[event.ordinal]++
        listener(event, detail())
    }

    /** Nombre total d'occurrences d'un événement, journalisées ou non. */
    fun count(event: Event): Int = counts[event.ordinal]

    /** `true` si au moins une anomalie a été relevée. */
    fun hasAnomalies(): Boolean = counts.any { it > 0 }

    fun reset() {
        counts.fill(0)
        reported.fill(0)
        instructionsThisFrame = 0
        instructionsLastFrame = 0
        lastSwi = -1
        lastInterruptMask = 0
        audioUnderruns = 0
        waitCycles = 0
    }

    companion object {
        /** Nombre maximal de signalements transmis par catégorie. */
        const val MAX_REPORTS_PER_EVENT = 8

        /** Au-delà de deux trames d'attente, l'interruption est considérée absente. */
        const val STUCK_WAIT_CYCLES = 2 * 280_896
    }
}
