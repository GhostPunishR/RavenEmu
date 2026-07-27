package com.ravenemu.romlibrary

import com.ravenemu.core.gb.GameBoyConsoleProvider
import com.ravenemu.core.gb.cartridge.CartridgeHeader
import com.ravenemu.core.gba.GbaConsoleProvider
import com.ravenemu.core.gba.cartridge.GbaCartridge
import com.ravenemu.emulation.api.ConsoleRegistry
import com.ravenemu.emulation.api.ConsoleType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Câblage réel des consoles livrées.
 *
 * Les analyseurs de la bibliothèque ne redéclarent plus ce qu'ils savent lire :
 * ils s'en remettent au fournisseur publié par le module de moteur. Ce test
 * vérifie qu'il n'existe bien qu'une source, en comparant ce que l'analyseur
 * annonce à ce que le fournisseur déclare.
 */
class ConsoleProviderWiringTest {

    private val registre = ConsoleRegistry(
        listOf(GameBoyConsoleProvider(), GbaConsoleProvider())
    )

    @Test
    fun `les consoles livrees sont enregistrees une fois chacune`() {
        assertEquals(
            setOf(ConsoleType.GAME_BOY, ConsoleType.GAME_BOY_ADVANCE),
            registre.supportedConsoles,
        )
        assertEquals(2, registre.providers.size)
    }

    @Test
    fun `chaque fournisseur cree le moteur de sa console`() {
        for (console in registre.supportedConsoles) {
            assertEquals(console, registre.create(console).console)
        }
    }

    @Test
    fun `l'analyseur Game Boy tient ses regles de son fournisseur`() {
        val analyseur = GameBoyRomAnalyzer()
        assertEquals(ConsoleType.GAME_BOY, analyseur.console)
        assertEquals(CartridgeHeader.MAX_ROM_SIZE, analyseur.maxRomSizeBytes)
        assertTrue(analyseur.canAnalyze("tetris.gb"))
        assertTrue(analyseur.canAnalyze("cristal.gbc"), "Le cœur Game Boy lit toute la gamme")
        assertFalse(analyseur.canAnalyze("emeraude.gba"))
    }

    @Test
    fun `l'analyseur Game Boy Advance tient ses regles de son fournisseur`() {
        val analyseur = GbaRomAnalyzer()
        assertEquals(ConsoleType.GAME_BOY_ADVANCE, analyseur.console)
        assertEquals(GbaCartridge.MAX_ROM_SIZE, analyseur.maxRomSizeBytes)
        assertTrue(analyseur.canAnalyze("emeraude.gba"))
        assertFalse(analyseur.canAnalyze("tetris.gb"))
    }

    @Test
    fun `analyseur et registre rattachent un fichier a la meme console`() {
        // La duplication d'antan permettait aux deux de diverger sans bruit.
        val analyseurs = listOf(GameBoyRomAnalyzer(), GbaRomAnalyzer())
        for (nom in listOf("tetris.gb", "cristal.gbc", "emeraude.gba")) {
            val parAnalyseur = analyseurs.first { it.canAnalyze(nom) }.console
            val parRegistre = registre.providerForFile(nom)?.console
            assertEquals(parAnalyseur, parRegistre, "Désaccord sur « $nom »")
        }
    }

    @Test
    fun `un analyseur expose le fournisseur du registre pour sa console`() {
        assertEquals(
            registre.providerFor(ConsoleType.GAME_BOY)?.console,
            GameBoyRomAnalyzer().provider.console,
        )
        assertSame(
            ConsoleType.GAME_BOY_ADVANCE,
            GbaRomAnalyzer().provider.console,
        )
    }
}
