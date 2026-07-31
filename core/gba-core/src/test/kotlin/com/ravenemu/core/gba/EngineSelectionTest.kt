package com.ravenemu.core.gba

import com.ravenemu.emulation.api.ConsoleType
import com.ravenemu.emulation.api.EmulatorCore
import com.ravenemu.emulation.api.EmulatorCoreFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Vérifie le contrat de sélection de moteur ([EmulatorCoreFactory]) pour la
 * Game Boy Advance. La fabrique de production (qui connaît aussi la Game Boy)
 * vit dans la racine de composition (module `app`) ; ce test valide le côté GBA
 * de manière indépendante d'Android.
 */
class EngineSelectionTest {

    private val factory = object : EmulatorCoreFactory {
        override val supportedConsoles = setOf(ConsoleType.GAME_BOY_ADVANCE)
        override fun create(console: ConsoleType): EmulatorCore = when (console) {
            ConsoleType.GAME_BOY_ADVANCE -> GbaCore()
            else -> throw IllegalArgumentException("Console non prise en charge : $console")
        }
    }

    @Test
    fun `la fabrique produit un GbaCore pour la GBA`() {
        val core = factory.create(ConsoleType.GAME_BOY_ADVANCE)
        assertTrue(core is GbaCore)
        assertEquals(ConsoleType.GAME_BOY_ADVANCE, core.console)
    }

    @Test
    fun `une console non prise en charge est rejetee`() {
        assertFailsWith<IllegalArgumentException> { factory.create(ConsoleType.GAME_BOY) }
    }

    @Test
    fun `l'extension gba est reconnue par le type console`() {
        assertTrue("gba" in ConsoleType.GAME_BOY_ADVANCE.romExtensions)
    }
}
