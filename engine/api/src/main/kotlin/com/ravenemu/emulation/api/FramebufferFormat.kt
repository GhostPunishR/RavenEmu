package com.ravenemu.emulation.api

/**
 * Format des données écrites par [EmulatorCore.runFrame] dans le framebuffer.
 *
 * - [ARGB_8888] : couleurs finales, prêtes à l'affichage.
 * - [INDEXED_4] : niveaux logiques `0..3` d'un écran monochrome à quatre
 *   niveaux (Game Boy). Le moteur ne produit **aucune couleur** ; c'est le
 *   renderer qui applique un profil d'écran (palette visuelle) au moment de
 *   l'affichage.
 */
enum class FramebufferFormat {
    ARGB_8888,
    INDEXED_4,
}
