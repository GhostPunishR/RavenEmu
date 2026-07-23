package com.ravenemu.emulation.api.display

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Réglages d'affichage avancés appliqués **après** la colorisation, comme
 * post-traitement de la sortie ARGB — sans jamais modifier les niveaux produits
 * par le PPU (voir AD-12).
 *
 * Trois réglages indépendants :
 * - **luminosité** [brightness] (`-100..100`, `0` = neutre) : décalage additif
 *   appliqué à chaque canal ;
 * - **contraste** [contrast] (`-100..100`, `0` = neutre) : étirement autour du
 *   gris moyen (formule photographique classique) ;
 * - **correction colorimétrique LCD** [lcdColorCorrection] : **simulation
 *   calibrable** — jamais des valeurs officielles — de la désaturation et du
 *   gamma d'un écran LCD réfléchissant. Elle rapproche les couleurs brutes
 *   (surtout celles, très vives, d'une Game Boy Color) de l'aspect plus mat
 *   d'un panneau réel. Sans intérêt sur un rendu déjà monochrome.
 *
 * Aucun effet par défaut : `DisplayAdjustments()` est l'identité. Les tables de
 * correspondance sont précalculées à la construction pour un coût par pixel
 * réduit à quelques accès mémoire et multiplications entières.
 *
 * Classe pure JVM, indépendante d'Android et testable directement.
 */
class DisplayAdjustments(
    val brightness: Int = 0,
    val contrast: Int = 0,
    val lcdColorCorrection: Boolean = false,
) {
    private val clampedBrightness = brightness.coerceIn(-100, 100)
    private val clampedContrast = contrast.coerceIn(-100, 100)

    /** Aucun réglage actif : la sortie est identique à l'entrée. */
    val isIdentity: Boolean =
        clampedBrightness == 0 && clampedContrast == 0 && !lcdColorCorrection

    /** Luminosité et contraste neutres (la correction LCD, elle, peut être active). */
    val isToneIdentity: Boolean =
        clampedBrightness == 0 && clampedContrast == 0

    // Luminosité/contraste, séparables par canal : une table 8 bits suffit.
    private val toneLut: IntArray = buildToneLut()

    // Correction LCD : gamma de panneau replié dans la table de tonalité.
    private val lcdToneLut: IntArray =
        if (lcdColorCorrection) buildLcdToneLut() else toneLut

    /**
     * Transforme une couleur ARGB en appliquant tous les réglages actifs. Pour
     * une sortie couleur brute (Game Boy Color), c'est le chemin par pixel.
     */
    fun apply(argb: Int): Int {
        if (isIdentity) return argb
        val a = argb and (0xFF shl 24)
        var r = (argb ushr 16) and 0xFF
        var g = (argb ushr 8) and 0xFF
        var b = argb and 0xFF
        if (lcdColorCorrection) {
            // Désaturation vers la luminance (Rec. 709 en virgule fixe /256).
            val luma = (54 * r + 183 * g + 19 * b) ushr 8
            r = r + (((luma - r) * DESATURATION) shr 8)
            g = g + (((luma - g) * DESATURATION) shr 8)
            b = b + (((luma - b) * DESATURATION) shr 8)
            r = lcdToneLut[r]
            g = lcdToneLut[g]
            b = lcdToneLut[b]
        } else {
            r = toneLut[r]
            g = toneLut[g]
            b = toneLut[b]
        }
        return a or (r shl 16) or (g shl 8) or b
    }

    /**
     * Applique uniquement luminosité et contraste (sans correction LCD). Utilisé
     * pour ajuster une **palette monochrome** déjà calibrée sans la désaturer.
     */
    fun applyTone(argb: Int): Int {
        if (isToneIdentity) return argb
        val a = argb and (0xFF shl 24)
        val r = toneLut[(argb ushr 16) and 0xFF]
        val g = toneLut[(argb ushr 8) and 0xFF]
        val b = toneLut[argb and 0xFF]
        return a or (r shl 16) or (g shl 8) or b
    }

    private fun buildToneLut(): IntArray {
        val offset = clampedBrightness * 128 / 100
        // C mappé sur [-128, 128] ; facteur photographique classique.
        val c = clampedContrast * 128 / 100
        val factor = (259.0 * (c + 255.0)) / (255.0 * (259.0 - c))
        return IntArray(256) { v ->
            val contrasted = factor * (v - 128.0) + 128.0
            (contrasted + offset).roundToInt().coerceIn(0, 255)
        }
    }

    private fun buildLcdToneLut(): IntArray {
        // Gamma de panneau réfléchissant (assombrit légèrement les moyens tons),
        // puis luminosité/contraste utilisateur par-dessus.
        return IntArray(256) { v ->
            val gamma = (255.0 * (v / 255.0).pow(LCD_GAMMA)).roundToInt().coerceIn(0, 255)
            toneLut[gamma]
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DisplayAdjustments) return false
        return clampedBrightness == other.clampedBrightness &&
            clampedContrast == other.clampedContrast &&
            lcdColorCorrection == other.lcdColorCorrection
    }

    override fun hashCode(): Int {
        var result = clampedBrightness
        result = 31 * result + clampedContrast
        result = 31 * result + lcdColorCorrection.hashCode()
        return result
    }

    override fun toString(): String =
        "DisplayAdjustments(brightness=$clampedBrightness, contrast=$clampedContrast, " +
            "lcdColorCorrection=$lcdColorCorrection)"

    private companion object {
        /** Part de désaturation vers la luminance (/256 ≈ 28 %). */
        const val DESATURATION = 72

        /** Gamma du panneau LCD simulé (> 1 : assombrit les moyens tons). */
        const val LCD_GAMMA = 1.15
    }
}
