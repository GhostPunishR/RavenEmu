package com.ravenemu.romlibrary

import com.ravenemu.core.gb.GameBoyConsoleProvider
import com.ravenemu.core.gb.cartridge.CartridgeHeader
import com.ravenemu.core.gba.GbaConsoleProvider
import com.ravenemu.core.nds.NdsConsoleProvider
import com.ravenemu.emulation.api.ConsoleRegistry
import com.ravenemu.emulation.api.ConsoleType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
        listOf(GameBoyConsoleProvider(), GbaConsoleProvider(), NdsConsoleProvider())
    )

    @Test
    fun `les consoles livrees sont enregistrees une fois chacune`() {
        assertEquals(
            setOf(ConsoleType.GAME_BOY, ConsoleType.GAME_BOY_ADVANCE, ConsoleType.NINTENDO_DS),
            registre.supportedConsoles,
        )
        assertEquals(3, registre.providers.size)
    }

    @Test
    fun `chaque fournisseur cree le moteur de sa console`() {
        for (console in registre.supportedConsoles) {
            assertEquals(console, registre.create(console).console)
        }
    }

    @Test
    fun `l'analyseur Game Boy tient ses regles de son fournisseur`() {
        val analyseur = GameBoyRomAnalyzer(GameBoyConsoleProvider())
        assertEquals(ConsoleType.GAME_BOY, analyseur.console)
        assertEquals(CartridgeHeader.MAX_ROM_SIZE, analyseur.maxRomSizeBytes)
        assertTrue(analyseur.canAnalyze("tetris.gb"))
        assertTrue(analyseur.canAnalyze("cristal.gbc"), "Le cœur Game Boy lit toute la gamme")
        assertFalse(analyseur.canAnalyze("emeraude.gba"))
    }

    @Test
    fun `l'analyseur Game Boy Advance tient ses regles de son fournisseur`() {
        val analyseur = GbaRomAnalyzer(GbaConsoleProvider())
        assertEquals(ConsoleType.GAME_BOY_ADVANCE, analyseur.console)
        assertEquals(GbaConsoleProvider.MAX_ROM_SIZE, analyseur.maxRomSizeBytes)
        assertTrue(analyseur.canAnalyze("emeraude.gba"))
        assertFalse(analyseur.canAnalyze("tetris.gb"))
    }

    @Test
    fun `analyseur et registre rattachent un fichier a la meme console`() {
        // La duplication d'antan permettait aux deux de diverger sans bruit.
        val analyseurs = listOf(
            GameBoyRomAnalyzer(GameBoyConsoleProvider()),
            GbaRomAnalyzer(GbaConsoleProvider()),
            NdsRomAnalyzer(NdsConsoleProvider()),
        )
        for (nom in listOf("tetris.gb", "cristal.gbc", "emeraude.gba", "donjon.nds")) {
            val parAnalyseur = analyseurs.first { it.canAnalyze(nom) }.console
            val parRegistre = registre.providerForFile(nom)?.console
            assertEquals(parAnalyseur, parRegistre, "Désaccord sur « $nom »")
        }
    }

    @Test
    fun `un analyseur sert la console que le registre lui associe`() {
        // L'analyseur ne reçoit pas l'instance du registre : chaque appel
        // construit la sienne. Ce qui doit coïncider est la console servie.
        assertEquals(
            registre.providerFor(ConsoleType.GAME_BOY)?.console,
            GameBoyRomAnalyzer(GameBoyConsoleProvider()).provider.console,
        )
        assertEquals(
            registre.providerFor(ConsoleType.GAME_BOY_ADVANCE)?.console,
            GbaRomAnalyzer(GbaConsoleProvider()).provider.console,
        )
        assertEquals(
            registre.providerFor(ConsoleType.NINTENDO_DS)?.console,
            NdsRomAnalyzer(NdsConsoleProvider()).provider.console,
        )
    }

    @Test
    fun `l'analyseur Game Boy refuse le fournisseur d'une autre console`() {
        // Le fournisseur est injecté : rien dans le type ne distingue celui du
        // Game Boy de celui du Game Boy Advance. Une interversion produirait un
        // index faux plutôt qu'une erreur, d'où le refus à la construction.
        val erreur = assertFailsWith<IllegalArgumentException> {
            GameBoyRomAnalyzer(GbaConsoleProvider())
        }
        assertTrue(
            ConsoleType.GAME_BOY_ADVANCE.name in (erreur.message ?: ""),
            "Le message doit nommer la console reçue : ${erreur.message}",
        )
    }

    @Test
    fun `l'analyseur Game Boy Advance refuse le fournisseur d'une autre console`() {
        val erreur = assertFailsWith<IllegalArgumentException> {
            GbaRomAnalyzer(GameBoyConsoleProvider())
        }
        assertTrue(
            ConsoleType.GAME_BOY.name in (erreur.message ?: ""),
            "Le message doit nommer la console reçue : ${erreur.message}",
        )
    }

    @Test
    fun `les analyseurs acceptent le fournisseur que le registre publie`() {
        // Contrepartie des deux tests précédents : la précondition ne doit pas
        // rejeter le câblage réel.
        val gb = registre.providerFor(ConsoleType.GAME_BOY)
        val gba = registre.providerFor(ConsoleType.GAME_BOY_ADVANCE)
        val nds = registre.providerFor(ConsoleType.NINTENDO_DS)
        assertEquals(ConsoleType.GAME_BOY, GameBoyRomAnalyzer(gb!!).console)
        assertEquals(ConsoleType.GAME_BOY_ADVANCE, GbaRomAnalyzer(gba!!).console)
        assertEquals(ConsoleType.NINTENDO_DS, NdsRomAnalyzer(nds!!).console)
    }

    @Test
    fun `l'analyseur Nintendo DS tient ses regles de son fournisseur`() {
        val analyseur = NdsRomAnalyzer(NdsConsoleProvider())
        assertEquals(ConsoleType.NINTENDO_DS, analyseur.console)
        assertEquals(NdsConsoleProvider.MAX_ROM_SIZE, analyseur.maxRomSizeBytes)
        assertTrue(analyseur.canAnalyze("donjon.nds"))
        assertFalse(analyseur.canAnalyze("emeraude.gba"))
    }

    @Test
    fun `seule la console sans format d'etat se declare telle`() {
        // La bibliothèque interroge le fournisseur pour décider d'offrir ou non
        // les états d'un jeu qu'elle n'a pas lancé : la réponse doit être celle
        // du module de moteur, et non un défaut hérité par tout le monde.
        assertTrue(registre.providerFor(ConsoleType.GAME_BOY)!!.supportsSaveState)
        assertTrue(registre.providerFor(ConsoleType.GAME_BOY_ADVANCE)!!.supportsSaveState)
        assertFalse(registre.providerFor(ConsoleType.NINTENDO_DS)!!.supportsSaveState)
    }
}
