package com.ravenemu.emulation.api

/**
 * Caractéristiques vidéo d'un moteur : dimensions natives du framebuffer et
 * cadence de rafraîchissement théorique de la console émulée.
 */
data class VideoSpec(
    val width: Int,
    val height: Int,
    /** Fréquence native de la console en hertz (ex. 59,7275 pour la Game Boy). */
    val refreshRateHz: Double,
) {
    val pixelCount: Int get() = width * height
}

/**
 * Caractéristiques audio d'un moteur. Les échantillons produits par
 * [EmulatorCore.readAudio] sont des PCM 16 bits signés entrelacés.
 */
data class AudioSpec(
    val sampleRateHz: Int,
    val channelCount: Int,
)
