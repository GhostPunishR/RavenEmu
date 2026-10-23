package com.ravenemu.shared.player

/**
 * One decoded 0xAARRGGBB frame from RavenGB.
 * The C bridge guarantees the alpha channel and a 160 x 144 pixel viewport.
 * Keep the pixel buffer immutable by convention after publishing this object.
 */
class GbVideoFrame(val width: Int, val height: Int, val argb: IntArray) {
    init {
        require(width == 160 && height == 144) {
            "Ce lecteur est encore limité aux images Game Boy 160 x 144."
        }
        require(argb.size == width * height) { "Le framebuffer n'a pas la taille attendue." }
    }
}
