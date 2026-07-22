package com.ravenemu.core.gb.ppu

import com.ravenemu.core.gb.InterruptController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PpuTest {

    private fun fresh(): Pair<Ppu, InterruptController> {
        val interrupts = InterruptController().apply { interruptFlags = 0 }
        return Ppu(interrupts) to interrupts
    }

    /** PPU éteint pour préparer VRAM/OAM, puis rallumé. */
    private fun freshPrepared(prepare: (Ppu) -> Unit): Pair<Ppu, InterruptController> {
        val (ppu, interrupts) = fresh()
        ppu.writeLcdc(0x11) // LCD éteint, données tuiles 0x8000
        prepare(ppu)
        return ppu to interrupts
    }

    // ---- Machine de modes ----

    @Test
    fun `sequence des modes sur une ligne`() {
        val (ppu, _) = fresh()
        assertEquals(Ppu.MODE_OAM_SCAN, ppu.mode)
        ppu.tick(79)
        assertEquals(Ppu.MODE_OAM_SCAN, ppu.mode)
        ppu.tick(1)
        assertEquals(Ppu.MODE_TRANSFER, ppu.mode)
        ppu.tick(172)
        assertEquals(Ppu.MODE_HBLANK, ppu.mode)
        ppu.tick(203)
        assertEquals(Ppu.MODE_HBLANK, ppu.mode)
        assertEquals(0, ppu.ly)
        ppu.tick(1)
        assertEquals(1, ppu.ly)
        assertEquals(Ppu.MODE_OAM_SCAN, ppu.mode)
    }

    @Test
    fun `vblank a la ligne 144 avec interruption et image prete`() {
        val (ppu, interrupts) = fresh()
        ppu.tick(456 * 144 - 1)
        assertEquals(0, interrupts.interruptFlags and 0x01)
        ppu.tick(1)
        assertEquals(144, ppu.ly)
        assertEquals(Ppu.MODE_VBLANK, ppu.mode)
        assertEquals(0x01, interrupts.interruptFlags and 0x01)
        assertTrue(ppu.frameReady)
    }

    @Test
    fun `trame complete de 70224 cycles reboucle ligne 0`() {
        val (ppu, _) = fresh()
        ppu.tick(456 * 154)
        assertEquals(0, ppu.ly)
        assertEquals(Ppu.MODE_OAM_SCAN, ppu.mode)
    }

    @Test
    fun `interruption STAT sur coincidence LYC`() {
        val (ppu, interrupts) = fresh()
        ppu.writeLyc(5)
        ppu.writeStat(0x40) // interruption LYC
        ppu.tick(456 * 5 - 1)
        assertEquals(0, interrupts.interruptFlags and 0x02)
        ppu.tick(1)
        assertEquals(0x02, interrupts.interruptFlags and 0x02)
        assertEquals(0x04, ppu.readStat() and 0x04) // bit coïncidence
    }

    @Test
    fun `interruption STAT mode 2 une fois par front`() {
        val (ppu, interrupts) = fresh()
        ppu.writeStat(0x20)
        // Front montant à l'entrée de la ligne 1.
        ppu.tick(456)
        assertEquals(0x02, interrupts.interruptFlags and 0x02)
    }

    // ---- Restrictions d'accès ----

    @Test
    fun `vram bloquee en mode 3 accessible sinon`() {
        val (ppu, _) = fresh()
        ppu.writeVram(0x8000, 0x42)
        assertEquals(0x42, ppu.readVram(0x8000)) // mode 2 : accessible
        ppu.tick(80) // mode 3
        assertEquals(0xFF, ppu.readVram(0x8000))
        ppu.writeVram(0x8000, 0x99) // ignoré
        ppu.tick(172) // mode 0
        assertEquals(0x42, ppu.readVram(0x8000))
    }

    @Test
    fun `oam bloquee en modes 2 et 3`() {
        val (ppu, _) = fresh()
        assertEquals(0xFF, ppu.readOam(0xFE00)) // mode 2
        ppu.tick(80 + 172) // mode 0
        ppu.writeOam(0xFE00, 0x55)
        assertEquals(0x55, ppu.readOam(0xFE00))
    }

    @Test
    fun `lcd eteint donne acces libre et ly nul`() {
        val (ppu, _) = fresh()
        ppu.tick(80) // mode 3
        ppu.writeLcdc(0x11) // extinction
        assertEquals(0, ppu.ly)
        ppu.writeVram(0x8000, 0x24)
        assertEquals(0x24, ppu.readVram(0x8000))
        ppu.tick(100_000)
        assertEquals(0, ppu.ly) // l'horloge est gelée
    }

    // ---- Rendu ----

    @Test
    fun `fond uniforme couleur 3`() {
        val (ppu, _) = freshPrepared { p ->
            // Tuile 0 : tous les pixels couleur 3.
            for (i in 0 until 16) p.writeVram(0x8000 + i, 0xFF)
            p.bgp = 0xE4 // palette identité
        }
        ppu.writeLcdc(0x91)
        ppu.tick(456 * 154)
        assertEquals(3, ppu.completedFrame[0])
        assertEquals(3, ppu.completedFrame[80 * 160 + 100])
        assertEquals(3, ppu.completedFrame[143 * 160 + 159])
    }

    @Test
    fun `scroll horizontal decale le motif`() {
        val (ppu, _) = freshPrepared { p ->
            // Tuile 0 : colonnes 0-3 couleur 1, colonnes 4-7 couleur 0.
            for (row in 0 until 8) {
                p.writeVram(0x8000 + row * 2, 0xF0)
                p.writeVram(0x8000 + row * 2 + 1, 0x00)
            }
            p.bgp = 0xE4
            p.scx = 4
        }
        ppu.writeLcdc(0x91)
        ppu.tick(456 * 154)
        // Avec SCX=4 : x=0 échantillonne la colonne 4 (couleur 0),
        // x=4 la colonne 0 de la tuile suivante (couleur 1).
        assertEquals(0, ppu.completedFrame[0])
        assertEquals(1, ppu.completedFrame[4])
    }

    @Test
    fun `adressage signe des tuiles en 0x8800`() {
        val (ppu, _) = freshPrepared { p ->
            // LCDC bit4=0 : index signés autour de 0x9000. Index 0x80 (-128)
            // → 0x8800. Tuile remplie couleur 3.
            for (i in 0 until 16) p.writeVram(0x8800 + i, 0xFF)
            // Carte : tout à 0x80.
            for (i in 0 until 0x400) p.writeVram(0x9800 + i, 0x80)
            p.bgp = 0xE4
        }
        ppu.writeLcdc(0x81) // bit4=0
        ppu.tick(456 * 154)
        assertEquals(3, ppu.completedFrame[0])
    }

    @Test
    fun `fenetre dessinee par-dessus le fond`() {
        val (ppu, _) = freshPrepared { p ->
            // Tuile 1 pleine couleur 3, tuile 0 vide.
            for (i in 0 until 16) p.writeVram(0x8010 + i, 0xFF)
            // Carte fenêtre 0x9C00 : tuile 1 partout.
            for (i in 0 until 0x400) p.writeVram(0x9C00 + i, 0x01)
            p.bgp = 0xE4
            p.wy = 0
            p.wx = 7 + 80 // fenêtre sur la moitié droite
        }
        ppu.writeLcdc(0x91 or 0x20 or 0x40)
        ppu.tick(456 * 154)
        assertEquals(0, ppu.completedFrame[0]) // fond
        assertEquals(3, ppu.completedFrame[80]) // fenêtre
        assertEquals(3, ppu.completedFrame[143 * 160 + 159])
    }

    @Test
    fun `sprite 8x8 dessine avec transparence couleur 0`() {
        val (ppu, _) = freshPrepared { p ->
            // Tuile 1 : moitié gauche couleur 3, moitié droite couleur 0.
            for (row in 0 until 8) {
                p.writeVram(0x8010 + row * 2, 0xF0)
                p.writeVram(0x8010 + row * 2 + 1, 0xF0)
            }
            p.bgp = 0xE4
            p.obp0 = 0xE4
            // Sprite 0 : écran (0,0), tuile 1.
            p.writeOam(0xFE00, 16)
            p.writeOam(0xFE01, 8)
            p.writeOam(0xFE02, 1)
            p.writeOam(0xFE03, 0)
        }
        ppu.writeLcdc(0x93) // BG + sprites
        ppu.tick(456 * 154)
        assertEquals(3, ppu.completedFrame[0]) // pixel sprite
        assertEquals(0, ppu.completedFrame[4]) // couleur 0 transparente → fond
    }

    @Test
    fun `sprite derriere le fond ne couvre que la couleur 0`() {
        val (ppu, _) = freshPrepared { p ->
            // Fond : tuile 0 couleur 2 partout.
            for (row in 0 until 8) {
                p.writeVram(0x8000 + row * 2, 0x00)
                p.writeVram(0x8000 + row * 2 + 1, 0xFF)
            }
            // Tuile 1 pleine couleur 3 pour le sprite.
            for (i in 0 until 16) p.writeVram(0x8010 + i, 0xFF)
            p.bgp = 0xE4
            p.obp0 = 0xE4
            p.writeOam(0xFE00, 16)
            p.writeOam(0xFE01, 8)
            p.writeOam(0xFE02, 1)
            p.writeOam(0xFE03, 0x80) // derrière le fond
        }
        ppu.writeLcdc(0x93)
        ppu.tick(456 * 154)
        assertEquals(2, ppu.completedFrame[0]) // le fond garde la main
    }

    @Test
    fun `priorite au sprite avec le plus petit X`() {
        val (ppu, _) = freshPrepared { p ->
            // Tuile 1 couleur 3, tuile 2 couleur 1.
            for (i in 0 until 16) p.writeVram(0x8010 + i, 0xFF)
            for (row in 0 until 8) {
                p.writeVram(0x8020 + row * 2, 0xFF)
                p.writeVram(0x8020 + row * 2 + 1, 0x00)
            }
            p.bgp = 0xE4
            p.obp0 = 0xE4
            // Sprite 0 : X écran 2 (tuile 2, couleur 1).
            p.writeOam(0xFE00, 16)
            p.writeOam(0xFE01, 10)
            p.writeOam(0xFE02, 2)
            p.writeOam(0xFE03, 0)
            // Sprite 1 : X écran 0 (tuile 1, couleur 3) → prioritaire.
            p.writeOam(0xFE04, 16)
            p.writeOam(0xFE05, 8)
            p.writeOam(0xFE06, 1)
            p.writeOam(0xFE07, 0)
        }
        ppu.writeLcdc(0x93)
        ppu.tick(456 * 154)
        // Zone de recouvrement x=2..7 : le sprite de X plus petit gagne.
        assertEquals(3, ppu.completedFrame[2])
    }

    @Test
    fun `limite de 10 sprites par ligne`() {
        val (ppu, _) = freshPrepared { p ->
            for (i in 0 until 16) p.writeVram(0x8010 + i, 0xFF)
            p.bgp = 0xE4
            p.obp0 = 0xE4
            // 11 sprites sur la ligne 0, X croissants : le 11e est ignoré.
            for (s in 0 until 11) {
                p.writeOam(0xFE00 + s * 4, 16)
                p.writeOam(0xFE00 + s * 4 + 1, 8 + s * 8)
                p.writeOam(0xFE00 + s * 4 + 2, 1)
                p.writeOam(0xFE00 + s * 4 + 3, 0)
            }
        }
        ppu.writeLcdc(0x93)
        ppu.tick(456 * 154)
        assertEquals(3, ppu.completedFrame[9 * 8]) // 10e sprite dessiné
        assertEquals(0, ppu.completedFrame[10 * 8]) // 11e ignoré
    }

    @Test
    fun `sprite 8x16 utilise les deux tuiles`() {
        val (ppu, _) = freshPrepared { p ->
            // Tuiles 2 et 3 : couleur 1 et couleur 3.
            for (row in 0 until 8) {
                p.writeVram(0x8020 + row * 2, 0xFF)
                p.writeVram(0x8020 + row * 2 + 1, 0x00)
                p.writeVram(0x8030 + row * 2, 0xFF)
                p.writeVram(0x8030 + row * 2 + 1, 0xFF)
            }
            p.bgp = 0xE4
            p.obp0 = 0xE4
            // Index impair 3 : masqué en 2 pour le mode 8x16.
            p.writeOam(0xFE00, 16)
            p.writeOam(0xFE01, 8)
            p.writeOam(0xFE02, 3)
            p.writeOam(0xFE03, 0)
        }
        ppu.writeLcdc(0x93 or 0x04) // sprites 8x16
        ppu.tick(456 * 154)
        assertEquals(1, ppu.completedFrame[0]) // ligne 0 : tuile 2
        assertEquals(3, ppu.completedFrame[8 * 160]) // ligne 8 : tuile 3
    }

    @Test
    fun `fond desactive rend la teinte 0`() {
        val (ppu, _) = freshPrepared { p ->
            for (i in 0 until 16) p.writeVram(0x8000 + i, 0xFF)
            p.bgp = 0xE4
        }
        ppu.writeLcdc(0x90) // bit0 = 0
        ppu.tick(456 * 154)
        assertEquals(0, ppu.completedFrame[0])
    }
}
