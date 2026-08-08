package com.ravenemu.emulation.api.display

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Ce que la correction colorimétrique LCD fait réellement à une image.
 *
 * Elle est décrite comme une simulation d'écran réfléchissant, ce qui ne dit pas
 * à quoi le résultat ressemble. Ces tests le mesurent, parce que c'est ce qui
 * justifie de ne plus l'imposer à toutes les consoles : appliquée à une image
 * qui ne la demande pas, elle donne exactement l'aspect terne et grisé qu'un
 * utilisateur décrirait comme un voile.
 *
 * Ils figent aussi ce qu'elle **ne fait pas**, pour que la mesure reste
 * utilisable : elle ne relève pas le niveau de noir et n'introduit aucune
 * transparence. Un voile qui s'accompagnerait de l'un ou de l'autre a une autre
 * cause, et il ne faut pas s'arrêter là.
 */
class LcdCorrectionEffectTest {

    private val correction = DisplayAdjustments(lcdColorCorrection = true)

    private fun rouge(argb: Int) = (argb ushr 16) and 0xFF
    private fun vert(argb: Int) = (argb ushr 8) and 0xFF
    private fun bleu(argb: Int) = argb and 0xFF

    /** Écart entre la composante la plus forte et la plus faible : la vivacité. */
    private fun vivacite(argb: Int): Int {
        val canaux = listOf(rouge(argb), vert(argb), bleu(argb))
        return canaux.max() - canaux.min()
    }

    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun `les couleurs vives perdent au moins un cinquieme de leur vivacite`() {
        // C'est l'effet recherché sur une Game Boy Color, et le voile subi
        // ailleurs. La désaturation retire 72/256 de l'écart entre canaux, soit
        // 28 % ; le gamma le rouvre ou le referme ensuite de quelques points
        // selon la teinte. Mesuré sur 255 d'écart initial : jaune 23,9 %, cyan
        // 24,7 %, vert 25,1 %, orange 25,5 %, rouge et magenta 28,6 %, bleu
        // 30,6 %. Le seuil est donc placé sous la plus faible des pertes, pas
        // sur la désaturation nominale.
        for ((nom, couleur) in listOf(
            "rouge" to argb(255, 0, 0),
            "vert" to argb(0, 255, 0),
            "bleu" to argb(0, 0, 255),
            "cyan" to argb(0, 255, 255),
            "magenta" to argb(255, 0, 255),
            "jaune" to argb(255, 255, 0),
        )) {
            val avant = vivacite(couleur)
            val apres = vivacite(correction.apply(couleur))
            val perte = (avant - apres) * 100.0 / avant
            assertTrue(
                perte > 20.0,
                "$nom : vivacité $avant → $apres, soit ${"%.1f".format(perte)} % de perte",
            )
        }
    }

    @Test
    fun `les moyens tons sont assombris`() {
        // Le gamma 1,15 du panneau simulé. C'est ce qui rend l'image mate : les
        // tons intermédiaires descendent — 128 devient 115 — sans que les
        // extrêmes bougent.
        val gris = argb(128, 128, 128)
        val corrige = correction.apply(gris)
        assertTrue(
            rouge(corrige) < 120,
            "Le gris moyen doit descendre, mesuré ${rouge(corrige)}",
        )
    }

    @Test
    fun `le noir reste noir et le blanc reste blanc`() {
        // Les bornes ne bougent pas : la correction n'est pas un voile
        // *additif*. Si le noir d'une image remonte à l'écran, la cause est
        // ailleurs — et c'est une information, pas un détail.
        assertEquals(argb(0, 0, 0), correction.apply(argb(0, 0, 0)))
        assertEquals(argb(255, 255, 255), correction.apply(argb(255, 255, 255)))
    }

    @Test
    fun `la transparence n'est jamais introduite`() {
        // Une image « faussement transparente » ne peut pas venir d'ici : le
        // canal alpha traverse tous les réglages sans être touché.
        val reglages = listOf(
            DisplayAdjustments(lcdColorCorrection = true),
            DisplayAdjustments(brightness = -100, contrast = -100),
            DisplayAdjustments(brightness = 100, contrast = 100, lcdColorCorrection = true),
        )
        for (reglage in reglages) {
            for (couleur in listOf(argb(0, 0, 0), argb(128, 60, 200), argb(255, 255, 255))) {
                assertEquals(
                    0xFF,
                    (reglage.apply(couleur) ushr 24) and 0xFF,
                    "$reglage a modifié l'opacité",
                )
                assertEquals(
                    0xFF,
                    (reglage.applyTone(couleur) ushr 24) and 0xFF,
                    "$reglage a modifié l'opacité (tonalité seule)",
                )
            }
        }
    }

    @Test
    fun `un gris reste gris`() {
        // La désaturation ramène vers la luminance : une couleur déjà neutre
        // n'a nulle part où aller. C'est ce qui garantit que la correction
        // n'introduit pas de teinte parasite.
        for (niveau in listOf(32, 96, 160, 224)) {
            val corrige = correction.apply(argb(niveau, niveau, niveau))
            assertTrue(
                abs(rouge(corrige) - vert(corrige)) <= 1 &&
                    abs(vert(corrige) - bleu(corrige)) <= 1,
                "Teinte parasite sur le niveau $niveau : $corrige",
            )
        }
    }

    @Test
    fun `sans correction l'image sort intacte`() {
        // La garantie que le réglage par défaut ne fait rien du tout : c'est ce
        // que la Game Boy Advance retrouve maintenant qu'elle a sa propre clé.
        val neutre = DisplayAdjustments()
        assertTrue(neutre.isIdentity)
        for (couleur in listOf(argb(255, 0, 0), argb(128, 128, 128), argb(17, 200, 90))) {
            assertEquals(couleur, neutre.apply(couleur))
        }
    }
}
