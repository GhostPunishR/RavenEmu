package com.ravenemu.core.gba.diag

/**
 * Photographie de l'état du moteur à un instant donné, destinée à une surcouche
 * de débogage. Toutes les valeurs sont de simples nombres : la couche
 * applicative les met en forme sans rien connaître de l'intérieur du moteur.
 *
 * Le temps par trame et le nombre d'images par seconde ne figurent pas ici :
 * seul l'appelant sait à quelle cadence il fait tourner le moteur.
 */
data class GbaDebugSnapshot(
    /** Instructions exécutées pendant la trame précédente. */
    val instructionsPerFrame: Int,
    /** Compteur de programme courant. */
    val programCounter: Int,
    /** `true` si le processeur exécute du Thumb, `false` en ARM. */
    val thumb: Boolean,
    /** `true` si le processeur est en pause (`Halt` ou `IntrWait`). */
    val halted: Boolean,
    /** Numéro du dernier appel logiciel du BIOS, ou -1. */
    val lastSwi: Int,
    /** Masque de la dernière interruption levée, ou 0. */
    val lastInterruptMask: Int,
    /** Ligne d'affichage courante (`VCOUNT`). */
    val vcount: Int,
    /** Canal DMA du dernier transfert, ou -1. */
    val lastDmaChannel: Int,
    /** `true` si un transfert DMA retient encore le bus. */
    val dmaActive: Boolean,
    /** Octets en attente dans la FIFO Direct Sound A. */
    val fifoASize: Int,
    /** Octets en attente dans la FIFO Direct Sound B. */
    val fifoBSize: Int,
    /** Lectures à vide de la FIFO Direct Sound A. */
    val fifoAEmptyReads: Int,
    /** Lectures à vide de la FIFO Direct Sound B. */
    val fifoBEmptyReads: Int,
    /** Sous-alimentations du tampon de sortie du moteur. */
    val audioUnderruns: Int,
    /** Nombre d'appels logiciels non pris en charge rencontrés. */
    val unsupportedSwiCount: Int,
    /** Nombre d'instructions indéfinies rencontrées. */
    val undefinedInstructionCount: Int,
    /** Nombre d'accès mémoire hors plan rencontrés. */
    val unsupportedAccessCount: Int,
    /** Nombre d'attentes d'interruption jugées bloquées. */
    val missingInterruptCount: Int,
    /** Nombre d'erreurs de décompression rencontrées. */
    val decompressionErrorCount: Int,
    /** Adresse du premier accès hors du plan mémoire, ou 0. */
    val firstUnsupportedAddress: Int,
    /** Millisecondes passées à composer l'affichage, trame écoulée. */
    val ppuMillis: Double,
    /** Millisecondes passées en transferts DMA, trame écoulée. */
    val dmaMillis: Double,
    /** Millisecondes passées à mixer l'audio, trame écoulée. */
    val apuMillis: Double,
) {
    /** `true` si une anomalie au moins a été relevée depuis le chargement. */
    val hasAnomalies: Boolean
        get() = unsupportedSwiCount > 0 ||
            undefinedInstructionCount > 0 ||
            unsupportedAccessCount > 0 ||
            missingInterruptCount > 0 ||
            decompressionErrorCount > 0
}
