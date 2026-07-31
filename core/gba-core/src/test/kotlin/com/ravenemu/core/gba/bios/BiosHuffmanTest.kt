package com.ravenemu.core.gba.bios

import com.ravenemu.core.gba.GbaMachine
import com.ravenemu.core.gba.SyntheticRom
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Décompression Huffman du BIOS.
 *
 * Les flux sont assemblés à la main pour éprouver le parcours de l'arbre : les
 * offsets des enfants se calculent sur l'**adresse absolue alignée** au sein
 * d'une table qui débute à l'octet de taille, et non sur un offset relatif à la
 * racine — une erreur d'un octet suffit à corrompre tout le décodage.
 */
class BiosHuffmanTest {

    private val base = 0x0200_0000
    private val dest = 0x0600_0000

    private fun machine() = GbaMachine(SyntheticRom.build())

    private fun put(m: GbaMachine, offset: Int, vararg bytes: Int) {
        bytes.forEachIndexed { i, b -> m.bus.write8(base + offset + i, b) }
    }

    private fun putWord(m: GbaMachine, offset: Int, value: Int) {
        m.bus.write32(base + offset, value)
    }

    private fun run(m: GbaMachine) {
        m.cpu.state.regs[0] = base
        m.cpu.state.regs[1] = dest
        m.bios.handleSwi(0x13)
    }

    private fun output(m: GbaMachine, count: Int): List<Int> =
        (0 until count).map { m.bus.read8(dest + it) }

    /**
     * Arbre minimal : une racine dont les deux enfants sont des feuilles.
     * La table occupe quatre octets (taille, racine, feuille gauche, droite).
     */
    private fun writeTwoLeafTree(m: GbaMachine, sizeBytes: Int, symbolBits: Int, left: Int, right: Int) {
        putWord(m, 0, (0x20 or symbolBits) or (sizeBytes shl 8))
        put(m, 4, 0x01)  // taille de table : (1+1)*2 = 4 octets
        put(m, 5, 0xC0)  // racine : offset 0, les deux enfants sont des feuilles
        put(m, 6, left)
        put(m, 7, right)
    }

    @Test
    fun `arbre minimal a deux feuilles, symboles 8 bits`() {
        val m = machine()
        writeTwoLeafTree(m, sizeBytes = 2, symbolBits = 8, left = 0xAA, right = 0xBB)
        // Bits lus du poids fort au poids faible : 0 → gauche, 1 → droite.
        putWord(m, 8, 0x4000_0000)
        run(m)
        assertEquals(listOf(0xAA, 0xBB), output(m, 2))
    }

    @Test
    fun `symboles 4 bits assembles deux par octet`() {
        val m = machine()
        // Le premier symbole occupe le quartet de poids faible.
        writeTwoLeafTree(m, sizeBytes = 1, symbolBits = 4, left = 0x0A, right = 0x0B)
        putWord(m, 8, 0x4000_0000) // gauche puis droite → 0xBA
        run(m)
        assertEquals(listOf(0xBA), output(m, 1))
    }

    @Test
    fun `arbre a plusieurs niveaux`() {
        val m = machine()
        putWord(m, 0, 0x28 or (3 shl 8)) // symboles 8 bits, trois octets attendus
        put(m, 4, 0x03)                  // table de (3+1)*2 = 8 octets
        put(m, 5, 0x80)                  // racine : gauche feuille, droite interne
        put(m, 6, 0x11)                  // feuille gauche
        put(m, 7, 0xC0)                  // nœud interne : deux feuilles
        put(m, 8, 0x22)                  // sa feuille gauche
        put(m, 9, 0x33)                  // sa feuille droite
        put(m, 10, 0x00, 0x00)           // remplissage jusqu'à l'alignement
        // 0 → 0x11 ; 1 puis 0 → 0x22 ; 1 puis 1 → 0x33.
        putWord(m, 12, 0b01011 shl 27)
        run(m)
        assertEquals(listOf(0x11, 0x22, 0x33), output(m, 3))
    }

    @Test
    fun `un arbre incoherent n'ecrit rien`() {
        val m = machine()
        // Offset démesuré : l'enfant sort de la table.
        putWord(m, 0, 0x28 or (2 shl 8))
        put(m, 4, 0x01)
        put(m, 5, 0x3F) // offset maximal, aucun enfant feuille
        put(m, 6, 0xAA, 0xBB)
        putWord(m, 8, 0x0000_0000)
        m.bus.write16(dest, 0x1234)
        run(m)
        assertEquals(0x1234, m.bus.read16(dest), "la destination doit rester intacte")
    }

    @Test
    fun `une largeur de symbole invalide est refusee`() {
        val m = machine()
        putWord(m, 0, 0x26 or (2 shl 8)) // 6 bits : ni 4 ni 8
        put(m, 4, 0x01)
        put(m, 5, 0xC0)
        put(m, 6, 0xAA, 0xBB)
        putWord(m, 8, 0x4000_0000)
        m.bus.write16(dest, 0x5678)
        run(m)
        assertEquals(0x5678, m.bus.read16(dest))
    }

    @Test
    fun `une taille annoncee nulle n'ecrit rien`() {
        val m = machine()
        writeTwoLeafTree(m, sizeBytes = 0, symbolBits = 8, left = 0xAA, right = 0xBB)
        putWord(m, 8, 0x4000_0000)
        m.bus.write16(dest, 0x4321)
        run(m)
        assertEquals(0x4321, m.bus.read16(dest))
    }
}
