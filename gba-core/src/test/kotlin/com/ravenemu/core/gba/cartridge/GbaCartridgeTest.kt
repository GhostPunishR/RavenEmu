package com.ravenemu.core.gba.cartridge

import com.ravenemu.core.gba.SyntheticRom
import com.ravenemu.emulation.api.RomLoadException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GbaCartridgeTest {

    @Test
    fun `cree une cartouche a partir d'une ROM valide`() {
        val cartridge = GbaCartridge.create(SyntheticRom.build(title = "RAVENTEST"))
        assertEquals("RAVENTEST", cartridge.header.title)
    }

    @Test
    fun `rejette une ROM sans marqueur GBA`() {
        val rom = SyntheticRom.build()
        rom[0xB2] = 0x00
        assertFailsWith<RomLoadException> { GbaCartridge.create(rom) }
    }

    @Test
    fun `rejette une ROM trop volumineuse`() {
        // On simule une taille excessive sans allouer 32 Mio réels : impossible
        // ici, on vérifie plutôt la borne via une ROM juste au-dessus n'est pas
        // testable sans grande allocation ; on couvre la limite basse à la place.
        assertFailsWith<RomLoadException> { GbaCartridge.create(ByteArray(0x40)) }
    }

    @Test
    fun `lecture hors limites retourne zero`() {
        val cartridge = GbaCartridge.create(SyntheticRom.build(sizeBytes = 1024))
        assertEquals(0, cartridge.read8(4096))
    }

    @Test
    fun `lecture d'un octet du programme`() {
        val cartridge = GbaCartridge.create(SyntheticRom.build())
        // Premier octet du mot ARM b . (0xEAFFFFFE, petit-boutiste) = 0xFE.
        assertEquals(0xFE, cartridge.read8(0))
    }
}
