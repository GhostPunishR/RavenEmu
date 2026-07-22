package com.ravenemu.settings

/**
 * Palettes DMG prédéfinies (4 teintes ARGB, de la plus claire à la plus
 * sombre). Générées localement, aucun contenu tiers.
 */
object GameBoyPalettes {

    data class Palette(val key: String, val displayName: String, val colors: IntArray)

    val all: List<Palette> = listOf(
        Palette(
            "classic",
            "Vert classique",
            intArrayOf(0xFF9BBC0F.toInt(), 0xFF8BAC0F.toInt(), 0xFF306230.toInt(), 0xFF0F380F.toInt()),
        ),
        Palette(
            "pocket",
            "Gris Pocket",
            intArrayOf(0xFFC5CAA4.toInt(), 0xFF8C926B.toInt(), 0xFF4A5138.toInt(), 0xFF181818.toInt()),
        ),
        Palette(
            "mono",
            "Noir et blanc",
            intArrayOf(0xFFFFFFFF.toInt(), 0xFFAAAAAA.toInt(), 0xFF555555.toInt(), 0xFF000000.toInt()),
        ),
        Palette(
            "amber",
            "Ambre",
            intArrayOf(0xFFFFE0A0.toInt(), 0xFFD0A060.toInt(), 0xFF905020.toInt(), 0xFF401800.toInt()),
        ),
        Palette(
            "cyan",
            "Cyan",
            intArrayOf(0xFFE0FFFF.toInt(), 0xFF80C0D0.toInt(), 0xFF3070A0.toInt(), 0xFF102040.toInt()),
        ),
    )

    fun byKey(key: String): Palette = all.firstOrNull { it.key == key } ?: all.first()
}
