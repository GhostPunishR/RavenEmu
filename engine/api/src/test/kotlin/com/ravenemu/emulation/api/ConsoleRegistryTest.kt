package com.ravenemu.emulation.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Registre des consoles : point d'entrée unique pour savoir ce que RavenEmu
 * sait émuler.
 *
 * Les fournisseurs employés ici sont des doublures : le registre ne doit rien
 * connaître des moteurs réels, c'est précisément ce qu'on vérifie.
 */
class ConsoleRegistryTest {

    private class FauxProvider(
        override val console: ConsoleType,
        override val romExtensions: Set<String> = console.romExtensions,
        override val maxRomSizeBytes: Int = 1024,
    ) : ConsoleProvider {
        var coresCrees = 0
            private set

        override fun createCore(): EmulatorCore {
            coresCrees++
            return FauxCore(console)
        }
    }

    private class FauxCore(override val console: ConsoleType) : EmulatorCore {
        override val video = VideoSpec(1, 1, 60.0)
        override val audio = AudioSpec(1, 1)
        override fun loadRom(rom: ByteArray, batteryRam: ByteArray?) = Unit
        override fun reset() = Unit
        override fun runFrame(framebuffer: IntArray) = Unit
        override fun setButton(button: EmulatorButton, pressed: Boolean) = Unit
        override fun readAudio(buffer: ShortArray): Int = 0
        override val hasBatteryRam: Boolean = false
        override val batteryRamDirty: Boolean = false
        override fun snapshotBatteryRam(): BatteryRamSnapshot? = null
        override fun acknowledgeBatteryRamSaved(generation: Long) = Unit
        override fun saveState(): ByteArray = ByteArray(0)
        override fun loadState(state: ByteArray) = Unit
    }

    private val gb = FauxProvider(ConsoleType.GAME_BOY)
    private val gba = FauxProvider(ConsoleType.GAME_BOY_ADVANCE)
    private val registre = ConsoleRegistry(listOf(gb, gba))

    @Test
    fun `le registre expose les consoles de ses fournisseurs`() {
        assertEquals(
            setOf(ConsoleType.GAME_BOY, ConsoleType.GAME_BOY_ADVANCE),
            registre.supportedConsoles,
        )
    }

    @Test
    fun `un registre vide ne prend en charge aucune console`() {
        val vide = ConsoleRegistry(emptyList())
        assertEquals(emptySet(), vide.supportedConsoles)
        assertNull(vide.providerFor(ConsoleType.GAME_BOY))
        assertNull(vide.providerForFile("tetris.gb"))
    }

    @Test
    fun `creer un moteur passe par le fournisseur de la console`() {
        val core = registre.create(ConsoleType.GAME_BOY_ADVANCE)
        assertEquals(ConsoleType.GAME_BOY_ADVANCE, core.console)
        assertEquals(1, gba.coresCrees)
        assertEquals(0, gb.coresCrees, "Seul le fournisseur concerné est sollicité")
    }

    @Test
    fun `une console absente est refusee avec un message explicite`() {
        val restreint = ConsoleRegistry(listOf(gb))
        val erreur = assertFailsWith<IllegalArgumentException> {
            restreint.create(ConsoleType.GAME_BOY_ADVANCE)
        }
        assertTrue(ConsoleType.GAME_BOY_ADVANCE.name in (erreur.message ?: ""))
    }

    @Test
    fun `deux fournisseurs pour une meme console sont un cablage fautif`() {
        // Refusé à la construction plutôt que départagé au hasard à l'exécution.
        val erreur = assertFailsWith<IllegalArgumentException> {
            ConsoleRegistry(listOf(gb, FauxProvider(ConsoleType.GAME_BOY)))
        }
        assertTrue(ConsoleType.GAME_BOY.name in (erreur.message ?: ""))
    }

    @Test
    fun `un fichier est rattache a la console qui reconnait son extension`() {
        assertSame(gb, registre.providerForFile("tetris.gb"))
        assertSame(gb, registre.providerForFile("cristal.gbc"))
        assertSame(gba, registre.providerForFile("emeraude.gba"))
        assertNull(registre.providerForFile("notice.txt"))
        assertNull(registre.providerForFile("sans-extension"))
    }

    @Test
    fun `l'extension est reconnue quelle que soit la casse`() {
        assertSame(gb, registre.providerForFile("TETRIS.GB"))
        assertSame(gba, registre.providerForFile("Emeraude.GbA"))
    }

    @Test
    fun `une extension partagee revient au premier fournisseur declare`() {
        // L'arbitrage doit être explicite et reproductible, pas dépendre d'un
        // ordre de parcours interne.
        val premier = FauxProvider(ConsoleType.GAME_BOY, romExtensions = setOf("rom"))
        val second = FauxProvider(ConsoleType.GAME_BOY_ADVANCE, romExtensions = setOf("rom"))
        assertSame(premier, ConsoleRegistry(listOf(premier, second)).providerForFile("jeu.rom"))
        assertSame(second, ConsoleRegistry(listOf(second, premier)).providerForFile("jeu.rom"))
    }

    @Test
    fun `un fournisseur declare la taille maximale acceptee par son moteur`() {
        val provider = FauxProvider(ConsoleType.GAME_BOY, maxRomSizeBytes = 8 * 1024 * 1024)
        assertEquals(8 * 1024 * 1024, ConsoleRegistry(listOf(provider)).providers.single().maxRomSizeBytes)
    }

    @Test
    fun `handles s'appuie sur les extensions declarees`() {
        val provider = FauxProvider(ConsoleType.GAME_BOY, romExtensions = setOf("gb"))
        assertTrue(provider.handles("tetris.gb"))
        assertFalse(provider.handles("cristal.gbc"), "L'extension gbc n'est pas déclarée ici")
    }
}
