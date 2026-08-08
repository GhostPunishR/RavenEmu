package com.ravenemu.app.library

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Couleur d'accent tirée d'une pochette, pour teinter le titre affiché sous
 * elle.
 *
 * Une grille de jaquettes sur fond noir a besoin que le texte appartienne à
 * l'image qu'il nomme, sinon la page se lit comme un tableau. La teinte se
 * prend donc dans la pochette elle-même.
 *
 * Le calcul est volontairement sans Android : c'est de l'arithmétique sur des
 * entiers ARGB, ce qui le rend vérifiable sans appareil. L'appelant remet les
 * pixels déjà décodés.
 */
object CoverAccent {

    /** Repli quand la pochette n'a aucune couleur exploitable (grise, noire). */
    const val DEFAUT = 0xFFCBC4DC.toInt()

    /** Fond de la bibliothèque, contre lequel le titre doit rester lisible. */
    private const val FOND = 0x0B0C12

    /**
     * Contraste minimal exigé entre le titre et le fond, au sens du calcul
     * WCAG. 4,5 est le seuil du niveau AA pour un texte de taille courante :
     * c'est celle des titres sous les vignettes.
     */
    private const val CONTRASTE_MINIMAL = 4.5

    /**
     * En dessous, aucun pixel ne porte de couleur franche : l'image est grise,
     * noire ou délavée, et le repli neutre vaut mieux qu'une teinte inventée.
     * Le seuil s'applique à l'écart entre la composante la plus forte et la
     * plus faible, rapporté à 255 — c'est la forme simplifiée du produit
     * saturation × luminosité, les deux `max` s'annulant.
     */
    private const val SCORE_MINIMAL = 0.25

    /** Nombre de pixels examinés, quelle que soit la taille de la pochette. */
    private const val PIXELS_ECHANTILLONNES = 2048

    /** Luminance que la couleur doit atteindre pour tenir le contraste voulu. */
    private val luminanceVisee =
        CONTRASTE_MINIMAL * (luminance(FOND) + 0.05) - 0.05

    /**
     * Accent de l'image [pixels] (ARGB 8888, [largeur] × [hauteur]).
     *
     * Le pixel retenu est celui qui porte le mieux la couleur de la pochette :
     * saturé et lumineux à la fois. Un seul des deux critères ne suffirait pas
     * — le rouge le plus saturé d'une jaquette est souvent une ombre presque
     * noire, et le pixel le plus lumineux, un blanc de bordure. Le produit des
     * deux se réduit à l'écart entre composante forte et composante faible.
     */
    fun extract(pixels: IntArray, largeur: Int, hauteur: Int): Int {
        if (largeur <= 0 || hauteur <= 0 || pixels.isEmpty()) return DEFAUT

        // Un échantillon suffit : la teinte dominante d'une jaquette ne se joue
        // pas au pixel près, et parcourir une image entière à chaque vignette
        // coûterait ce que l'on vient d'économiser sur le décodage.
        val pas = max(1, (largeur * hauteur) / PIXELS_ECHANTILLONNES)

        var meilleurScore = 0.0
        var meilleur = 0
        var i = 0
        while (i < pixels.size) {
            val pixel = pixels[i]
            if ((pixel ushr 24) >= 128) { // les pixels transparents ne comptent pas
                val r = (pixel shr 16) and 0xFF
                val v = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                val maximum = max(r, max(v, b))
                val minimum = min(r, min(v, b))
                if (maximum > 0) {
                    val score = (maximum - minimum).toDouble() / 255.0
                    if (score > meilleurScore) {
                        meilleurScore = score
                        meilleur = pixel
                    }
                }
            }
            i += pas
        }

        if (meilleurScore < SCORE_MINIMAL) return DEFAUT
        return rendreLisible(meilleur)
    }

    /**
     * Amène la couleur au contraste voulu, en deux temps.
     *
     * D'abord un gain sur les trois composantes jusqu'à ce que la plus forte
     * sature : leurs rapports sont conservés, donc la teinte est exacte et rien
     * n'est écrêté. Si cela ne suffit pas — un bleu nuit reste sombre même à
     * pleine intensité — la couleur est mélangée à du blanc. Le mélange pâlit
     * la couleur mais ne la déplace pas : mieux vaut un bleu clair qu'un bleu
     * devenu cyan par écrêtage, et mieux vaut pâle que d'illisible.
     */
    private fun rendreLisible(couleur: Int): Int {
        val maximum = max(
            (couleur shr 16) and 0xFF,
            max((couleur shr 8) and 0xFF, couleur and 0xFF),
        )
        if (maximum == 0) return DEFAUT // noir pur : aucune teinte à sauver

        val gain = 255.0 / maximum
        val gr = (((couleur shr 16) and 0xFF) * gain).toInt().coerceIn(0, 255)
        val gv = (((couleur shr 8) and 0xFF) * gain).toInt().coerceIn(0, 255)
        val gb = ((couleur and 0xFF) * gain).toInt().coerceIn(0, 255)

        // La luminance relative n'est pas linéaire en les composantes : plutôt
        // que d'inverser la formule, on avance par pas jusqu'au premier mélange
        // qui atteint la cible. Trente-deux pas suffisent à ne pas surpâlir.
        for (pas in 0..ETAPES_MELANGE) {
            val part = pas.toDouble() / ETAPES_MELANGE
            val melange = couleurDe(
                (gr + (255 - gr) * part).toInt(),
                (gv + (255 - gv) * part).toInt(),
                (gb + (255 - gb) * part).toInt(),
            )
            if (luminance(melange) >= luminanceVisee) return melange
        }
        // Inatteignable : le blanc dépasse toute luminance visée sous 1,0.
        return couleurDe(255, 255, 255)
    }

    /** Pas du mélange vers le blanc : assez fin pour ne pas surpâlir. */
    private const val ETAPES_MELANGE = 32

    private fun couleurDe(r: Int, v: Int, b: Int): Int =
        0xFF000000.toInt() or (r shl 16) or (v shl 8) or b

    /** Luminance relative sRGB (WCAG), sur 0..1. */
    private fun luminance(couleur: Int): Double {
        val r = canal(((couleur shr 16) and 0xFF) / 255.0)
        val v = canal(((couleur shr 8) and 0xFF) / 255.0)
        val b = canal((couleur and 0xFF) / 255.0)
        return 0.2126 * r + 0.7152 * v + 0.0722 * b
    }

    /** Linéarisation d'un canal sRGB. */
    private fun canal(valeur: Double): Double =
        if (valeur <= 0.03928) valeur / 12.92 else ((valeur + 0.055) / 1.055).pow(2.4)
}
