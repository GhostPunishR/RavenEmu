package com.ravenemu.emulation.api.display

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DisplayAdjustmentsTest {

    private fun r(argb: Int) = (argb ushr 16) and 0xFF
    private fun g(argb: Int) = (argb ushr 8) and 0xFF
    private fun b(argb: Int) = argb and 0xFF
    private fun a(argb: Int) = (argb ushr 24) and 0xFF
    private fun luma(argb: Int) = 0.2126 * r(argb) + 0.7152 * g(argb) + 0.0722 * b(argb)

    @Test
    fun `sans reglage l'identite est vraie et la sortie inchangee`() {
        val adj = DisplayAdjustments()
        assertTrue(adj.isIdentity)
        assertTrue(adj.isToneIdentity)
        val color = 0xFF3C6E21.toInt()
        assertEquals(color, adj.apply(color))
        assertEquals(color, adj.applyTone(color))
    }

    @Test
    fun `l'alpha est toujours preserve`() {
        val adj = DisplayAdjustments(brightness = 40, contrast = 30, lcdColorCorrection = true)
        for (color in listOf(0xFF102030.toInt(), 0x80AABBCC.toInt(), 0x00FFFFFF)) {
            assertEquals(a(color), a(adj.apply(color)))
            assertEquals(a(color), a(adj.applyTone(color)))
        }
    }

    @Test
    fun `la luminosite positive eclaircit chaque canal`() {
        val adj = DisplayAdjustments(brightness = 50)
        assertFalse(adj.isIdentity)
        val out = adj.apply(0xFF404040.toInt())
        assertTrue(r(out) > 0x40)
        assertTrue(g(out) > 0x40)
        assertTrue(b(out) > 0x40)
    }

    @Test
    fun `la luminosite negative assombrit chaque canal`() {
        val adj = DisplayAdjustments(brightness = -50)
        val out = adj.apply(0xFF808080.toInt())
        assertTrue(r(out) < 0x80)
        assertTrue(g(out) < 0x80)
        assertTrue(b(out) < 0x80)
    }

    @Test
    fun `le point milieu gris est stable en contraste`() {
        // Le contraste pivote autour de 128 : le gris moyen ne bouge pas.
        for (c in listOf(-100, -40, 40, 100)) {
            val adj = DisplayAdjustments(contrast = c)
            val out = adj.apply(0xFF808080.toInt())
            assertTrue(r(out) in 0x7F..0x81, "contrast=$c donne ${r(out)}")
        }
    }

    @Test
    fun `le contraste positif ecarte les tons du milieu`() {
        val adj = DisplayAdjustments(contrast = 80)
        val dark = adj.apply(0xFF303030.toInt())
        val bright = adj.apply(0xFFC0C0C0.toInt())
        assertTrue(r(dark) < 0x30, "les sombres doivent s'assombrir : ${r(dark)}")
        assertTrue(r(bright) > 0xC0, "les clairs doivent s'éclaircir : ${r(bright)}")
    }

    @Test
    fun `le contraste negatif rapproche les tons du milieu`() {
        val adj = DisplayAdjustments(contrast = -80)
        val dark = adj.apply(0xFF303030.toInt())
        val bright = adj.apply(0xFFC0C0C0.toInt())
        assertTrue(r(dark) > 0x30, "les sombres doivent remonter : ${r(dark)}")
        assertTrue(r(bright) < 0xC0, "les clairs doivent descendre : ${r(bright)}")
    }

    @Test
    fun `les valeurs restent bornees entre 0 et 255`() {
        val adj = DisplayAdjustments(brightness = 100, contrast = 100, lcdColorCorrection = true)
        for (color in listOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFFFF00FF.toInt())) {
            val out = adj.apply(color)
            for (ch in listOf(r(out), g(out), b(out))) {
                assertTrue(ch in 0..255, "canal hors bornes : $ch")
            }
        }
    }

    @Test
    fun `la correction LCD desature une couleur vive`() {
        val adj = DisplayAdjustments(lcdColorCorrection = true)
        assertFalse(adj.isIdentity)
        assertTrue(adj.isToneIdentity, "sans luminosité/contraste, la tonalité reste neutre")
        // Un rouge pur doit voir ses canaux vert/bleu remonter (rapprochement du gris).
        val out = adj.apply(0xFFFF0000.toInt())
        assertTrue(g(out) > 0, "le vert doit remonter : ${g(out)}")
        assertTrue(b(out) > 0, "le bleu doit remonter : ${b(out)}")
        assertTrue(r(out) < 0xFF, "le rouge doit baisser : ${r(out)}")
    }

    @Test
    fun `la correction LCD preserve le gris neutre en teinte`() {
        val adj = DisplayAdjustments(lcdColorCorrection = true)
        val out = adj.apply(0xFF909090.toInt())
        // La désaturation d'un gris ne crée pas de teinte : canaux égaux.
        assertEquals(r(out), g(out))
        assertEquals(g(out), b(out))
    }

    @Test
    fun `applyTone ignore la correction LCD`() {
        val adj = DisplayAdjustments(lcdColorCorrection = true)
        val color = 0xFFFF0000.toInt()
        // applyTone n'applique que luminosité/contraste : ici neutres → inchangé.
        assertEquals(color, adj.applyTone(color))
        // apply, lui, corrige.
        assertTrue(adj.apply(color) != color)
    }

    @Test
    fun `applyTone et apply concordent sans correction LCD`() {
        val adj = DisplayAdjustments(brightness = 25, contrast = 15)
        for (color in listOf(0xFF204060.toInt(), 0xFFB5C18C.toInt(), 0xFF000000.toInt())) {
            assertEquals(adj.applyTone(color), adj.apply(color))
        }
    }

    @Test
    fun `egalite et hashCode par valeurs`() {
        val a1 = DisplayAdjustments(brightness = 10, contrast = -20, lcdColorCorrection = true)
        val a2 = DisplayAdjustments(brightness = 10, contrast = -20, lcdColorCorrection = true)
        val a3 = DisplayAdjustments(brightness = 10, contrast = -20, lcdColorCorrection = false)
        assertEquals(a1, a2)
        assertEquals(a1.hashCode(), a2.hashCode())
        assertFalse(a1 == a3)
    }

    @Test
    fun `les valeurs hors bornes sont ecretees`() {
        val extreme = DisplayAdjustments(brightness = 500, contrast = -500)
        val clamped = DisplayAdjustments(brightness = 100, contrast = -100)
        assertEquals(clamped, extreme)
    }
}
