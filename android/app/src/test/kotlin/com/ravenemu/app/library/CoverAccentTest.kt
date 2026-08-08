package com.ravenemu.app.library

import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Teinte du titre tirée de la pochette.
 *
 * Deux exigences se contredisent et ce test tient les deux : la couleur doit
 * venir de l'image, et le titre doit rester lisible sur le fond sombre de la
 * bibliothèque. Une couleur fidèle mais illisible serait un défaut, une
 * couleur lisible mais inventée aussi.
 */
class CoverAccentTest {

    private val fond = 0x0B0C12

    private fun image(couleur: Int, taille: Int = 4) =
        IntArray(taille * taille) { couleur }

    private fun canal(valeur: Double): Double =
        if (valeur <= 0.03928) valeur / 12.92 else ((valeur + 0.055) / 1.055).pow(2.4)

    private fun luminance(couleur: Int): Double =
        0.2126 * canal(((couleur shr 16) and 0xFF) / 255.0) +
            0.7152 * canal(((couleur shr 8) and 0xFF) / 255.0) +
            0.0722 * canal((couleur and 0xFF) / 255.0)

    private fun contraste(couleur: Int): Double =
        (luminance(couleur) + 0.05) / (luminance(fond) + 0.05)

    @Test
    fun `une couleur vive et deja lisible est rendue telle quelle`() {
        // Le rouge pur atteint 4,88 de contraste sur le fond : le toucher
        // n'apporterait rien et lui ferait perdre sa force.
        assertEquals(0xFFFF0000.toInt(), CoverAccent.extract(image(0xFFFF0000.toInt()), 4, 4))
    }

    @Test
    fun `une couleur sombre est ramenee a pleine intensite sans changer de teinte`() {
        // Un rouge sombre n'est qu'un rouge sous-exposé : le gain le restitue
        // exactement, sans mélange ni dérive.
        assertEquals(0xFFFF0000.toInt(), CoverAccent.extract(image(0xFF400000.toInt()), 4, 4))
    }

    @Test
    fun `un bleu reste bleu meme apres avoir ete eclairci`() {
        // Le bleu est la composante la moins lumineuse de l'œil : à pleine
        // intensité il n'atteint pas le contraste voulu et doit être pâli.
        // Il doit le rester bleu, c'est-à-dire garder sa composante dominante.
        val accent = CoverAccent.extract(image(0xFF001040.toInt()), 4, 4)
        val rouge = (accent shr 16) and 0xFF
        val vert = (accent shr 8) and 0xFF
        val bleu = accent and 0xFF
        assertTrue(bleu > vert && bleu > rouge, "Teinte perdue : ${accent.toString(16)}")
    }

    @Test
    fun `toute couleur rendue tient le contraste minimal sur le fond`() {
        // C'est la garantie de lisibilité, éprouvée sur des teintes de
        // luminances très différentes — le vert est clair, le bleu très sombre.
        val teintes = listOf(
            0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(),
            0xFF001040.toInt(), 0xFF400000.toInt(), 0xFF2A0A3C.toInt(),
            0xFF7F3300.toInt(), 0xFF003322.toInt(),
        )
        for (teinte in teintes) {
            val accent = CoverAccent.extract(image(teinte), 4, 4)
            assertTrue(
                contraste(accent) >= 4.5,
                "Contraste insuffisant pour ${teinte.toString(16)} : " +
                    "${accent.toString(16)} donne ${contraste(accent)}",
            )
        }
    }

    @Test
    fun `une image sans couleur retombe sur la teinte neutre`() {
        // Gris, noir et blanc n'ont aucune teinte à emprunter. Inventer une
        // couleur serait pire que de n'en mettre aucune.
        for (neutre in listOf(0xFF808080.toInt(), 0xFF000000.toInt(), 0xFFFFFFFF.toInt())) {
            assertEquals(CoverAccent.DEFAUT, CoverAccent.extract(image(neutre), 4, 4))
        }
    }

    @Test
    fun `une touche de couleur dans une image grise est retenue`() {
        // La jaquette d'un jeu peut être presque monochrome : c'est justement
        // le seul accent présent qu'il faut trouver.
        val pixels = IntArray(16) { 0xFF808080.toInt() }
        pixels[7] = 0xFFFF0000.toInt()
        assertEquals(0xFFFF0000.toInt(), CoverAccent.extract(pixels, 4, 4))
    }

    @Test
    fun `les pixels transparents ne donnent pas la teinte`() {
        // Une pochette au format PNG a souvent des bords transparents dont la
        // couleur, invisible à l'écran, n'a rien à dire du jeu.
        assertEquals(CoverAccent.DEFAUT, CoverAccent.extract(image(0x00FF0000), 4, 4))
    }

    @Test
    fun `une image vide ou de dimensions absurdes ne fait pas echouer`() {
        assertEquals(CoverAccent.DEFAUT, CoverAccent.extract(IntArray(0), 0, 0))
        assertEquals(CoverAccent.DEFAUT, CoverAccent.extract(intArrayOf(0xFFFF0000.toInt()), 0, 4))
        assertEquals(CoverAccent.DEFAUT, CoverAccent.extract(intArrayOf(0xFFFF0000.toInt()), 4, -1))
    }

    @Test
    fun `la teinte neutre est elle-meme lisible`() {
        // Le repli n'échappe pas à la règle qu'il sert à respecter.
        assertTrue(contraste(CoverAccent.DEFAUT) >= 4.5)
    }
}
