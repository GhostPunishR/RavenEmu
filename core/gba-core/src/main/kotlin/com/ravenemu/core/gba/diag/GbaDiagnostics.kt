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

    /**
     * Adresse du **premier** accès hors du plan mémoire, ou 0.
     *
     * Un compteur seul dit qu'il se passe quelque chose ; l'adresse dit quoi.
     * La première suffit : ces incidents arrivent presque toujours en rafale,
     * au même endroit, et c'est celui-là qu'il faut retrouver dans le programme.
     */
    var firstUnsupportedAddress = 0
        private set

    /** À appeler **avant** [report], le décompte servant à repérer la première. */
    fun recordUnsupportedAccess(address: Int) {
        if (counts[Event.UNSUPPORTED_ACCESS.ordinal] == 0) firstUnsupportedAddress = address
    }

    /**
     * Écritures vers `BG2PA`–`BG2PD`, la matrice du plan affine.
     *
     * Un plan affine activé dont la matrice reste nulle ne dessine rien. Ce
     * décompte distingue les deux causes possibles, qui appellent des
     * corrections opposées : à zéro, le jeu n'a jamais tenté de l'écrire et
     * c'est son déroulement qui diverge ; non nul avec une matrice pourtant
     * nulle, c'est l'écriture elle-même qui se perd.
     */
    var bg2MatrixWrites = 0
        private set

    /** Écritures vers `BG2X`/`BG2Y`, le point de référence du plan affine. */
    var bg2ReferenceWrites = 0
        private set

    fun recordBg2MatrixWrite() {
        bg2MatrixWrites++
    }

    fun recordBg2ReferenceWrite() {
        bg2ReferenceWrites++
    }

    /** Cycles écoulés dans l'attente d'interruption en cours. */
    var waitCycles = 0
        private set

    /**
     * Chronométrage des sous-systèmes. Désactivé par défaut : chaque relevé
     * coûte deux lectures d'horloge, négligeables aux quelques milliers d'appels
     * par trame concernés, mais inutiles hors mesure.
     */
    var measuringTime = false

    /** Nanosecondes passées à composer les lignes de l'affichage, trame écoulée. */
    var ppuNanosLastFrame = 0L
        private set

    /** Nanosecondes passées en transferts DMA, trame écoulée. */
    var dmaNanosLastFrame = 0L
        private set

    /** Nanosecondes passées à mixer l'audio, trame écoulée. */
    var apuNanosLastFrame = 0L
        private set

    private var ppuNanos = 0L
    private var dmaNanos = 0L
    private var apuNanos = 0L

    fun addPpuNanos(nanos: Long) {
        ppuNanos += nanos
    }

    fun addDmaNanos(nanos: Long) {
        dmaNanos += nanos
    }

    fun addApuNanos(nanos: Long) {
        apuNanos += nanos
    }

    fun onInstruction() {
        instructionsThisFrame++
    }

    /** À appeler au début de chaque trame : bascule le compteur d'instructions. */
    fun beginFrame() {
        instructionsLastFrame = instructionsThisFrame
        instructionsThisFrame = 0
        ppuNanosLastFrame = ppuNanos
        dmaNanosLastFrame = dmaNanos
        apuNanosLastFrame = apuNanos
        ppuNanos = 0
        dmaNanos = 0
        apuNanos = 0
    }

    /**
     * Appels du BIOS effectivement passés par le jeu, indexés par numéro.
     *
     * Le dernier appel ne dit presque rien : c'est celui qui revient le plus
     * souvent qui l'emporte au moment du relevé. Le relevé complet, lui, montre
     * quels services le jeu emploie réellement — et surtout lesquels il n'emploie
     * jamais, ce qui trahit une branche de son code qui n'est pas prise.
     */
    private val swiCounts = IntArray(SWI_RANGE)

    fun swiCount(number: Int): Int = swiCounts[number]

    fun onSwi(number: Int) {
        lastSwi = number
        if (number in swiCounts.indices) swiCounts[number]++
    }

    fun onInterrupt(mask: Int) {
        lastInterruptMask = mask
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
        swiCounts.fill(0)
        lastInterruptMask = 0
        firstUnsupportedAddress = 0
        bg2MatrixWrites = 0
        bg2ReferenceWrites = 0
        waitCycles = 0
        measuringTime = false
        ppuNanos = 0
        dmaNanos = 0
        apuNanos = 0
        ppuNanosLastFrame = 0
        dmaNanosLastFrame = 0
        apuNanosLastFrame = 0
    }

    companion object {
        /** Numéros d'appel BIOS suivis : 0x00 à 0x2F couvre tout ce qu'un jeu emploie. */
        const val SWI_RANGE = 0x30

        /** Nombre maximal de signalements transmis par catégorie. */
        const val MAX_REPORTS_PER_EVENT = 8

        /** Au-delà de deux trames d'attente, l'interruption est considérée absente. */
        const val STUCK_WAIT_CYCLES = 2 * 280_896
    }
}
