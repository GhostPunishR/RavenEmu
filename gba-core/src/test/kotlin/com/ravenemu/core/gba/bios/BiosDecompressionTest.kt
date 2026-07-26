package com.ravenemu.core.gba.bios

import com.ravenemu.core.gba.GbaMachine
import com.ravenemu.core.gba.SyntheticRom
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Décompression du BIOS : c'est par ces appels que les jeux déploient leurs
 * graphismes en VRAM. Sans eux, l'écran reste noir.
 */
class BiosDecompressionTest {

    private fun machine() = GbaMachine(SyntheticRom.build())

    /** Dépose des octets en EWRAM, à utiliser comme source compressée. */
    private fun putBytes(m: GbaMachine, address: Int, vararg bytes: Int) {
        bytes.forEachIndexed { i, b -> m.bus.write8(address + i, b) }
    }

    private fun readBytes(m: GbaMachine, address: Int, count: Int): List<Int> =
        (0 until count).map { m.bus.read8(address + it) }

    @Test
    fun `LZ77 restitue une repetition par reference arriere`() {
        val m = machine()
        // En-tête : type 0x10, taille décompressée 8.
        // Drapeaux 0x40 : première unité littérale, seconde compressée.
        // Référence : longueur 7, distance 1 → recopie l'octet précédent.
        putBytes(
            m, 0x0200_0000,
            0x10, 0x08, 0x00, 0x00,
            0x40,
            'A'.code,
            0x40, 0x00,
        )
        m.cpu.state.regs[0] = 0x0200_0000
        m.cpu.state.regs[1] = 0x0200_1000
        m.bios.handleSwi(0x11) // LZ77UnCompWram

        assertEquals(List(8) { 'A'.code }, readBytes(m, 0x0200_1000, 8))
    }

    @Test
    fun `LZ77 vers la VRAM ecrit par demi-mots`() {
        val m = machine()
        putBytes(
            m, 0x0200_0000,
            0x10, 0x04, 0x00, 0x00,
            0x00, // quatre unités littérales
            0x11, 0x22, 0x33, 0x44,
        )
        m.cpu.state.regs[0] = 0x0200_0000
        m.cpu.state.regs[1] = 0x0600_0000
        m.bios.handleSwi(0x12) // LZ77UnCompVram

        // La VRAM refuse les écritures d'octet isolé : le résultat ne serait pas
        // correct si la routine n'écrivait pas par demi-mots.
        assertEquals(0x2211, m.bus.read16(0x0600_0000))
        assertEquals(0x4433, m.bus.read16(0x0600_0002))
    }

    @Test
    fun `RLE developpe une sequence repetee`() {
        val m = machine()
        // En-tête type 0x30, taille 5 ; drapeau 0x82 = répétition de 5 octets.
        putBytes(
            m, 0x0200_0000,
            0x30, 0x05, 0x00, 0x00,
            0x82, 0xAB,
        )
        m.cpu.state.regs[0] = 0x0200_0000
        m.cpu.state.regs[1] = 0x0200_1000
        m.bios.handleSwi(0x14) // RLUnCompWram

        assertEquals(List(5) { 0xAB }, readBytes(m, 0x0200_1000, 5))
    }

    @Test
    fun `RLE gere aussi les octets litteraux`() {
        val m = machine()
        // Drapeau 0x02 = trois octets littéraux (bit 7 à zéro, longueur+1).
        putBytes(
            m, 0x0200_0000,
            0x30, 0x03, 0x00, 0x00,
            0x02, 0x01, 0x02, 0x03,
        )
        m.cpu.state.regs[0] = 0x0200_0000
        m.cpu.state.regs[1] = 0x0200_1000
        m.bios.handleSwi(0x14)

        assertEquals(listOf(0x01, 0x02, 0x03), readBytes(m, 0x0200_1000, 3))
    }

    @Test
    fun `le filtre differentiel 8 bits cumule les ecarts`() {
        val m = machine()
        // En-tête 0x81, taille 4 ; écarts +1, +2, +3, +4 → 1, 3, 6, 10.
        putBytes(
            m, 0x0200_0000,
            0x81, 0x04, 0x00, 0x00,
            0x01, 0x02, 0x03, 0x04,
        )
        m.cpu.state.regs[0] = 0x0200_0000
        m.cpu.state.regs[1] = 0x0200_1000
        m.bios.handleSwi(0x16) // Diff8bitUnFilterWram

        assertEquals(listOf(1, 3, 6, 10), readBytes(m, 0x0200_1000, 4))
    }

    @Test
    fun `un flux de taille aberrante est refuse sans ecrire`() {
        val m = machine()
        // Taille annoncée de 16 Mio : au-delà du garde-fou.
        putBytes(m, 0x0200_0000, 0x10, 0x00, 0x00, 0xFF)
        m.bus.write8(0x0200_1000, 0x5A)
        m.cpu.state.regs[0] = 0x0200_0000
        m.cpu.state.regs[1] = 0x0200_1000
        m.bios.handleSwi(0x11)

        assertEquals(0x5A, m.bus.read8(0x0200_1000), "la destination doit rester intacte")
    }

    @Test
    fun `RegisterRamReset efface les zones demandees`() {
        val m = machine()
        m.bus.write16(0x0500_0000, 0x1234) // palette
        m.bus.write16(0x0600_0000, 0x5678) // VRAM
        m.cpu.state.regs[0] = 0x04 or 0x08 // palette + VRAM
        m.bios.handleSwi(0x01)

        assertEquals(0, m.bus.read16(0x0500_0000))
        assertEquals(0, m.bus.read16(0x0600_0000))
    }
}
