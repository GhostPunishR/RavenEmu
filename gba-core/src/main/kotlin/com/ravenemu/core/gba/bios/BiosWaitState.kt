package com.ravenemu.core.gba.bios

/**
 * Attente d'interruption en cours, posée par les appels BIOS `IntrWait` et
 * `VBlankIntrWait`.
 *
 * Le BIOS ne se contente pas de mettre le processeur en pause : il attend
 * **une interruption précise**. Le gestionnaire d'interruption du jeu accumule
 * les sources survenues dans un mot de drapeaux propre au BIOS (à
 * `0x0300_7FF8`) ; l'appel ne rend la main que lorsqu'une source du masque
 * demandé y apparaît.
 *
 * @param interruptMask sources attendues (mêmes bits que `IE`/`IF`).
 * @param discardOldFlags `true` lorsque l'appelant a demandé d'oublier les
 *   interruptions déjà survenues (`R0 = 1`) et de n'attendre que les suivantes.
 */
data class BiosWaitState(
    val interruptMask: Int,
    val discardOldFlags: Boolean,
)
