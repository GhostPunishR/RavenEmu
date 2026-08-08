package com.ravenemu.core.gb.ppu

import com.ravenemu.core.gb.InterruptController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CgbPpuTest {

    private fun freshCgb(prepare: (Ppu) -> Unit): Ppu {
        val ppu = Ppu(InterruptController().apply { interruptFlags = 0 }, cgbMode = true)
        ppu.writeLcdc(0x11) // LCD éteint pour préparer VRAM/CRAM
        prepare(ppu)
        return ppu
    }

    /** Programme une couleur BGR555 dans une palette de fond. */
    private fun Ppu.setBgColor(palette: Int, colorIndex: Int, bgr555: Int) {
        writeBcps(0x80 or (palette * 8 + colorIndex * 2))
        writeBcpd(bgr555 and 0xFF)
        writeBcpd((bgr555 shr 8) and 0xFF)
    }

    private fun Ppu.setObjColor(palette: Int, colorIndex: Int, bgr555: Int) {
        writeOcps(0x80 or (palette * 8 + colorIndex * 2))
        writeOcpd(bgr555 and 0xFF)
        writeOcpd((bgr555 shr 8) and 0xFF)
    }

    @Test
    fun `conversion BGR555 vers ARGB`() {
        assertEquals(0xFF000000.toInt(), Ppu.bgr555ToArgb(0x0000))
        assertEquals(0xFFFFFFFF.toInt(), Ppu.bgr555ToArgb(0x7FFF))
        assertEquals(0xFFFF0000.toInt(), Ppu.bgr555ToArgb(0x001F)) // rouge
        assertEquals(0xFF00FF00.toInt(), Ppu.bgr555ToArgb(0x03E0)) // vert
        assertEquals(0xFF0000FF.toInt(), Ppu.bgr555ToArgb(0x7C00)) // bleu
    }

    @Test
    fun `fond colorise via l'attribut de palette`() {
        val ppu = freshCgb { p ->
            // Tuile 0 pleine (indice de couleur 3 partout).
            for (i in 0 until 16) p.writeVram(0x8000 + i, 0xFF)
            // Attribut en banque 1 : palette 2.
            p.writeVramBank(1)
            for (i in 0 until 0x400) p.writeVram(0x9800 + i, 0x02)
            p.writeVramBank(0)
            p.setBgColor(palette = 2, colorIndex = 3, bgr555 = 0x03E0) // vert
        }
        ppu.writeLcdc(0x91) // LCD + BG tiles 0x8000
        ppu.tick(456 * 154)
        assertEquals(0xFF00FF00.toInt(), ppu.completedFrame[0])
        assertEquals(0xFF00FF00.toInt(), ppu.completedFrame[100 * 160 + 80])
    }

    @Test
    fun `donnees de tuile lues dans la banque VRAM 1`() {
        val ppu = freshCgb { p ->
            // Tuile 0 vide en banque 0, pleine en banque 1.
            p.writeVramBank(1)
            for (i in 0 until 16) p.writeVram(0x8000 + i, 0xFF)
            // Attribut : banque de tuile 1 (bit 3), palette 0.
            p.writeVram(0x9800, 0x08)
            p.writeVramBank(0)
            p.setBgColor(palette = 0, colorIndex = 3, bgr555 = 0x001F) // rouge
        }
        ppu.writeLcdc(0x91)
        ppu.tick(456 * 154)
        assertEquals(0xFFFF0000.toInt(), ppu.completedFrame[0])
    }

    @Test
    fun `retournement horizontal de l'attribut BG`() {
        val ppu = freshCgb { p ->
            // Tuile : colonne 0 = couleur 1, reste = 0.
            for (row in 0 until 8) {
                p.writeVram(0x8000 + row * 2, 0x80) // bit 7 (colonne 0) plan bas
                p.writeVram(0x8000 + row * 2 + 1, 0x00)
            }
            p.writeVramBank(1)
            p.writeVram(0x9800, 0x20) // xFlip
            p.writeVramBank(0)
            p.setBgColor(0, 1, 0x001F) // couleur 1 = rouge
            p.setBgColor(0, 0, 0x7FFF) // couleur 0 = blanc
        }
        ppu.writeLcdc(0x91)
        ppu.tick(456 * 154)
        // Sans flip, la colonne 0 serait rouge ; avec xFlip, c'est la colonne 7.
        assertEquals(0xFFFFFFFF.toInt(), ppu.completedFrame[0])
        assertEquals(0xFFFF0000.toInt(), ppu.completedFrame[7])
    }

    @Test
    fun `sprite colorise par sa palette CGB`() {
        val ppu = freshCgb { p ->
            for (i in 0 until 16) p.writeVram(0x8010 + i, 0xFF) // tuile 1 pleine
            p.setObjColor(palette = 3, colorIndex = 3, bgr555 = 0x7C00) // bleu
            // Sprite 0 : écran (0,0), tuile 1, palette 3.
            p.writeOam(0xFE00, 16)
            p.writeOam(0xFE01, 8)
            p.writeOam(0xFE02, 1)
            p.writeOam(0xFE03, 0x03) // palette CGB 3
            p.setBgColor(0, 0, 0x7FFF) // fond blanc
        }
        ppu.writeLcdc(0x93) // LCD + BG + sprites
        ppu.tick(456 * 154)
        assertEquals(0xFF0000FF.toInt(), ppu.completedFrame[0])
    }

    @Test
    fun `priorite maitre desactivee place les sprites au-dessus`() {
        val ppu = freshCgb { p ->
            // Fond opaque (couleur 3) avec attribut prioritaire.
            for (i in 0 until 16) p.writeVram(0x8000 + i, 0xFF)
            for (i in 0 until 16) p.writeVram(0x8010 + i, 0xFF)
            p.writeVramBank(1)
            p.writeVram(0x9800, 0x80) // attribut BG prioritaire
            p.writeVramBank(0)
            p.setBgColor(0, 3, 0x001F) // fond rouge
            p.setObjColor(0, 3, 0x7C00) // sprite bleu
            p.writeOam(0xFE00, 16)
            p.writeOam(0xFE01, 8)
            p.writeOam(0xFE02, 1)
            p.writeOam(0xFE03, 0x00)
        }
        // LCDC bit0 = 0 : priorité maître désactivée → sprite au-dessus malgré
        // l'attribut BG prioritaire.
        ppu.writeLcdc(0x92) // LCD + sprites, bit0=0
        ppu.tick(456 * 154)
        assertEquals(0xFF0000FF.toInt(), ppu.completedFrame[0])
    }

    @Test
    fun `attribut BG prioritaire masque le sprite quand la priorite maitre est active`() {
        val ppu = freshCgb { p ->
            for (i in 0 until 16) p.writeVram(0x8000 + i, 0xFF)
            for (i in 0 until 16) p.writeVram(0x8010 + i, 0xFF)
            p.writeVramBank(1)
            p.writeVram(0x9800, 0x80) // attribut BG prioritaire
            p.writeVramBank(0)
            p.setBgColor(0, 3, 0x001F) // rouge
            p.setObjColor(0, 3, 0x7C00) // bleu
            p.writeOam(0xFE00, 16)
            p.writeOam(0xFE01, 8)
            p.writeOam(0xFE02, 1)
            p.writeOam(0xFE03, 0x00)
        }
        ppu.writeLcdc(0x93) // bit0 = 1 : priorité maître active
        ppu.tick(456 * 154)
        assertEquals(0xFFFF0000.toInt(), ppu.completedFrame[0]) // fond visible
    }

    @Test
    fun `le framebuffer CGB ne contient que des couleurs opaques`() {
        val ppu = freshCgb { p ->
            for (i in 0 until 16) p.writeVram(0x8000 + i, 0xFF)
            p.setBgColor(0, 3, 0x03E0)
        }
        ppu.writeLcdc(0x91)
        ppu.tick(456 * 154)
        assertTrue(ppu.completedFrame.all { (it ushr 24) == 0xFF })
    }
}
