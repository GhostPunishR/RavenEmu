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
    /** `DISPCNT` : mode, plans actifs, sprites, mode de projection, fenêtres. */
    val dispcnt: Int,
    /** `BG0CNT` à `BG3CNT` : priorité et blocs de chaque plan. */
    val bg0Control: Int,
    val bg1Control: Int,
    val bg2Control: Int,
    val bg3Control: Int,
    /** `BLDCNT` : couches sources et cibles du mélange, et son mode. */
    val blendControl: Int,
    /**
     * Pixels produits par chaque couche à la trame précédente, indexés `BG0`…
     * `BG3` puis `OBJ`. Vide si le comptage n'est pas activé.
     */
    val layerPixels: IntArray,
    /** `BG2X` et `BG2Y` en pixels : point de référence du plan affine. */
    val bg2ReferenceX: Int,
    val bg2ReferenceY: Int,
    /** `BG2PA` et `BG2PD` en virgule fixe 8.8 : `0x0100` = échelle 1. */
    val bg2ScaleX: Int,
    val bg2ScaleY: Int,
    /** Écritures du jeu vers la matrice `BG2PA`–`BG2PD`. */
    val bg2MatrixWrites: Int,
    /** Écritures du jeu vers le point de référence `BG2X`/`BG2Y`. */
    val bg2ReferenceWrites: Int,
    /**
     * Appels du BIOS passés par le jeu, indexés par numéro. Vide si le relevé
     * n'est pas tenu.
     */
    val swiCounts: IntArray,
    /** Millisecondes passées à composer l'affichage, trame écoulée. */
    val ppuMillis: Double,
    /** Millisecondes passées en transferts DMA, trame écoulée. */
    val dmaMillis: Double,
    /** Millisecondes passées à mixer l'audio, trame écoulée. */
    val apuMillis: Double,
) {
    /** Priorité déclarée du plan [bg] (0 = le plus proche). */
    fun bgPriority(bg: Int): Int = bgControl(bg) and 0x3

    /** `true` si le plan [bg] est activé dans `DISPCNT`. */
    fun bgEnabled(bg: Int): Boolean = dispcnt and (1 shl (8 + bg)) != 0

    private fun bgControl(bg: Int): Int = when (bg) {
        0 -> bg0Control
        1 -> bg1Control
        2 -> bg2Control
        else -> bg3Control
    }

    /** `true` si une anomalie au moins a été relevée depuis le chargement. */
    val hasAnomalies: Boolean
        get() = unsupportedSwiCount > 0 ||
            undefinedInstructionCount > 0 ||
            unsupportedAccessCount > 0 ||
            missingInterruptCount > 0 ||
            decompressionErrorCount > 0
}
