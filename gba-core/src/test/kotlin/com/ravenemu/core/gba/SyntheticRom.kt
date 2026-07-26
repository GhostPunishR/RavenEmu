package com.ravenemu.core.gba

import com.ravenemu.core.gba.cartridge.GbaHeader

/**
 * Générateur de **ROM GBA synthétiques** pour les tests : aucune donnée
 * commerciale ni logo Nintendo, uniquement un en-tête valide (marqueur `0x96`,
 * somme de contrôle correcte) et un petit programme ARM placé au début de la
 * ROM, exécuté dès `0x0800_0000`.
 */
object SyntheticRom {

    /** Boucle infinie ARM `b .` (branche sur elle-même). */
    const val ARM_INFINITE_LOOP = 0xEAFFFFFE.toInt()

    /**
     * Construit une ROM contenant [programWords] (mots ARM 32 bits, petits-
     * boutistes) au début, suivie d'un en-tête valide.
     */
    fun build(
        programWords: IntArray = intArrayOf(ARM_INFINITE_LOOP),
        title: String = "RAVENTEST",
        sizeBytes: Int = 1024,
    ): ByteArray {
        require(sizeBytes >= GbaHeader.HEADER_SIZE) { "ROM trop petite" }
        val rom = ByteArray(sizeBytes)
        var offset = 0
        for (word in programWords) {
            putWord(rom, offset, word)
            offset += 4
        }
        writeHeader(rom, title)
        return rom
    }

    /**
     * Construit une ROM à partir de [code] déjà assemblé (mélange ARM et Thumb
     * possible). Le code doit réserver la zone d'en-tête 0x04–0xBF, comme le fait
     * une vraie cartouche : un branchement en 0x00, puis le programme à partir de
     * 0xC0 (voir [GbaAssembler.padTo]).
     */
    fun buildFromCode(
        code: ByteArray,
        title: String = "RAVENTEST",
        sizeBytes: Int = 8192,
    ): ByteArray {
        require(sizeBytes >= GbaHeader.HEADER_SIZE) { "ROM trop petite" }
        require(code.size <= sizeBytes) { "Programme plus grand que la ROM" }
        val rom = ByteArray(sizeBytes)
        code.copyInto(rom)
        writeHeader(rom, title)
        return rom
    }

    private fun writeHeader(rom: ByteArray, title: String) {
        writeAscii(rom, 0xA0, title.take(12))
        writeAscii(rom, 0xAC, "TEST")
        writeAscii(rom, 0xB0, "RV")
        rom[0xB2] = 0x96.toByte() // marqueur fixe GBA
        rom[0xBD] = GbaHeader.computeHeaderChecksum(rom).toByte()
    }

    /**
     * Programme ARM écrivant une couleur BGR555 tenant sur 8 bits (`0..0xFF`)
     * dans l'arrière-plan (palette 0), puis bouclant : produit une image unie.
     */
    fun backdropProgram(colorBgr555: Int): IntArray {
        require(colorBgr555 in 0..0xFF) { "Couleur non codable en immédiat 8 bits" }
        return intArrayOf(
            0xE3A00000.toInt() or colorBgr555, // MOV R0, #color
            0xE3A01405.toInt(),                // MOV R1, #0x05000000 (palette RAM)
            0xE5810000.toInt(),                // STR R0, [R1]
            ARM_INFINITE_LOOP,                 // b .
        )
    }

    /**
     * Installe en IWRAM le plus court gestionnaire d'interruption **viable** pour
     * un jeu, et le déclare au BIOS en `0x0300_7FFC`.
     *
     * Il acquitte `IF` avant de rendre la main : sans cet acquittement, la source
     * reste levée et l'exception se redéclenche à l'infini dès le retour — ce que
     * fait aussi le vrai matériel.
     */
    fun installMinimalIrqHandler(
        bus: com.ravenemu.core.gba.memory.GbaBus,
        at: Int = 0x0300_0000,
    ) {
        val asm = GbaAssembler(base = at)
        asm.ldrPc(0, "ifRegister")
        asm.ldrh(1, 0)          // lit les sources levées
        asm.strh(1, 0)          // écrire un 1 efface le drapeau
        asm.bx(14)
        asm.label("ifRegister")
        asm.word(0x0400_0202)
        val code = asm.assemble()
        for ((i, byte) in code.withIndex()) bus.write8(at + i, byte.toInt() and 0xFF)
        bus.write32(0x0300_7FFC, at)
    }

    private fun putWord(rom: ByteArray, offset: Int, word: Int) {
        rom[offset] = (word and 0xFF).toByte()
        rom[offset + 1] = ((word ushr 8) and 0xFF).toByte()
        rom[offset + 2] = ((word ushr 16) and 0xFF).toByte()
        rom[offset + 3] = ((word ushr 24) and 0xFF).toByte()
    }

    private fun writeAscii(rom: ByteArray, offset: Int, text: String) {
        for ((i, c) in text.withIndex()) rom[offset + i] = c.code.toByte()
    }
}
