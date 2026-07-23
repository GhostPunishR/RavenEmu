package com.ravenemu.core.gba.ppu

import com.ravenemu.core.gba.memory.GbaBus

/**
 * Unité graphique de la Game Boy Advance — **premier lot** : produit un
 * framebuffer 240 × 160 ARGB 8888 rempli d'une **couleur unie**, celle de
 * l'arrière-plan (entrée 0 de la palette BG, format BGR555).
 *
 * Les modes graphiques 0 à 5, les arrière-plans, les sprites, les fenêtres, la
 * mosaïque et le mélange alpha sont différés aux lots suivants. Le renderer
 * Android reçoit un framebuffer ARGB sans rien connaître de ces détails.
 */
class GbaPpu(private val bus: GbaBus) {

    /** Dernière trame produite, en ARGB 8888. */
    val frame = IntArray(SCREEN_WIDTH * SCREEN_HEIGHT)

    /** Rend une trame : lit la couleur d'arrière-plan et remplit l'image. */
    fun renderFrame() {
        val backdrop = bus.read16(BACKDROP_ADDRESS)
        frame.fill(bgr555ToArgb(backdrop))
    }

    companion object {
        const val SCREEN_WIDTH = 240
        const val SCREEN_HEIGHT = 160

        /** Palette BG, couleur 0 : arrière-plan affiché quand rien n'est dessiné. */
        private const val BACKDROP_ADDRESS = 0x0500_0000

        /** Convertit une couleur BGR555 (15 bits) en ARGB 8888 opaque. */
        fun bgr555ToArgb(color: Int): Int {
            val r5 = color and 0x1F
            val g5 = (color ushr 5) and 0x1F
            val b5 = (color ushr 10) and 0x1F
            val r8 = (r5 shl 3) or (r5 ushr 2)
            val g8 = (g5 shl 3) or (g5 ushr 2)
            val b8 = (b5 shl 3) or (b5 ushr 2)
            return (0xFF shl 24) or (r8 shl 16) or (g8 shl 8) or b8
        }
    }
}
