package com.ravenemu.app.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Sous-échantillonnage des pochettes.
 *
 * Le défilement de la bibliothèque saccadait parce que chaque vignette
 * décodait son image à la taille d'origine. Ce test fige la règle : réduire
 * autant que possible, mais jamais au point de rendre une image plus grossière
 * que la vignette qui l'affiche.
 */
class CoverSamplingTest {

    @Test
    fun `une image deja plus petite que la vignette n'est pas reduite`() {
        assertEquals(1, CoverSampling.sampleSize(128, 128, 256, 256))
    }

    @Test
    fun `une image de taille exacte n'est pas reduite`() {
        assertEquals(1, CoverSampling.sampleSize(256, 256, 256, 256))
    }

    @Test
    fun `une image deux fois trop grande est reduite de moitie`() {
        assertEquals(2, CoverSampling.sampleSize(512, 512, 256, 256))
    }

    @Test
    fun `une photo de plusieurs milliers de pixels est fortement reduite`() {
        // Cas réel : une pochette scannée pour une vignette de 256 pixels.
        assertEquals(16, CoverSampling.sampleSize(4096, 4096, 256, 256))
    }

    @Test
    fun `le facteur est toujours une puissance de deux`() {
        for (largeur in listOf(300, 700, 1000, 1500, 3000, 5000)) {
            val facteur = CoverSampling.sampleSize(largeur, largeur, 256, 256)
            assertTrue(
                facteur > 0 && facteur and (facteur - 1) == 0,
                "Facteur non puissance de deux pour $largeur : $facteur",
            )
        }
    }

    @Test
    fun `l'image reduite reste au moins aussi grande que la vignette`() {
        // C'est la garantie qui protège la qualité : sous-échantillonner trop
        // afficherait une vignette floue, ce qui serait pire que le défaut
        // qu'on corrige.
        for (source in listOf(257, 400, 511, 512, 513, 2000, 4095)) {
            val facteur = CoverSampling.sampleSize(source, source, 256, 256)
            assertTrue(
                source / facteur >= 256,
                "Image trop réduite : $source / $facteur < 256",
            )
        }
    }

    @Test
    fun `une image non carree suit sa dimension la plus contraignante`() {
        // Réduire de 4 amènerait la hauteur sous la vignette : on s'arrête à 2.
        assertEquals(2, CoverSampling.sampleSize(2048, 512, 256, 256))
    }

    @Test
    fun `des dimensions inconnues ou absurdes ne reduisent pas`() {
        // BitmapFactory rend 0 quand il n'a pas su lire l'en-tête : mieux vaut
        // une image lourde qu'une division par zéro ou une image absente.
        assertEquals(1, CoverSampling.sampleSize(0, 0, 256, 256))
        assertEquals(1, CoverSampling.sampleSize(-1, 512, 256, 256))
        assertEquals(1, CoverSampling.sampleSize(512, 512, 0, 256))
    }
}
