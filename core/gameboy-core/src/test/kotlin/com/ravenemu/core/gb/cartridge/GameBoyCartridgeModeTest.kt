package com.ravenemu.core.gb.cartridge

import com.ravenemu.core.gb.KotlinGameBoyCore
import com.ravenemu.core.gb.TestRoms
import com.ravenemu.emulation.api.ConsoleType
import com.ravenemu.emulation.api.FramebufferFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Compatibilité de cartouche et conséquence sur le rendu.
 *
 * La gamme Game Boy est servie par un seul cœur ; c'est l'octet `0x0143` de
 * l'en-tête qui décide des fonctions couleur. Ces tests couvrent les trois
 * valeurs qu'il peut prendre et vérifient que le format de trame produit suit
 * la cartouche — un moteur monochrome ne doit pas se croire en couleur, ni
 * l'inverse.
 */
class GameBoyCartridgeModeTest {

    private fun header(cgbFlag: Int): CartridgeHeader =
        CartridgeHeader.parse(TestRoms.build(cgbFlag = cgbFlag))

    private fun coreFor(cgbFlag: Int): KotlinGameBoyCore =
        KotlinGameBoyCore().apply { loadRom(TestRoms.build(cgbFlag = cgbFlag)) }

    @Test
    fun `une cartouche monochrome est en mode DMG`() {
        val header = header(0x00)
        assertEquals(GameBoyCartridgeMode.DMG_ONLY, header.cartridgeMode)
        assertFalse(header.supportsCgb)
        assertFalse(header.requiresCgb)
    }

    @Test
    fun `une cartouche compatible couleur est en mode DMG et CGB`() {
        val header = header(0x80)
        assertEquals(GameBoyCartridgeMode.DMG_AND_CGB, header.cartridgeMode)
        assertTrue(header.supportsCgb)
        assertFalse(header.requiresCgb, "0x80 ne rend pas la Game Boy Color obligatoire")
    }

    @Test
    fun `une cartouche exigeant la couleur est en mode CGB seul`() {
        val header = header(0xC0)
        assertEquals(GameBoyCartridgeMode.CGB_ONLY, header.cartridgeMode)
        assertTrue(header.supportsCgb)
        assertTrue(header.requiresCgb)
    }

    @Test
    fun `un octet appartenant au titre ne declare pas la couleur`() {
        // Avant la Game Boy Color, 0x0143 était le dernier caractère du titre :
        // n'importe quelle lettre s'y trouve sur les cartouches d'époque.
        for (octet in listOf(0x41, 0x5A, 0x20, 0x01, 0x7F, 0xFF)) {
            assertEquals(
                GameBoyCartridgeMode.DMG_ONLY,
                GameBoyCartridgeMode.fromCgbFlag(octet),
                "0x%02X ne déclare pas de fonctions couleur".format(octet),
            )
        }
    }

    @Test
    fun `la gamme entiere est servie par un seul coeur`() {
        for (flag in listOf(0x00, 0x80, 0xC0)) {
            assertEquals(ConsoleType.GAME_BOY, coreFor(flag).console)
        }
    }

    @Test
    fun `le format de trame suit la cartouche`() {
        assertEquals(
            FramebufferFormat.INDEXED_4,
            coreFor(0x00).framebufferFormat,
            "Une cartouche monochrome produit des niveaux colorisés par le profil d'écran",
        )
        assertEquals(
            FramebufferFormat.ARGB_8888,
            coreFor(0x80).framebufferFormat,
            "Une cartouche compatible couleur est jouée en couleur",
        )
        assertEquals(
            FramebufferFormat.ARGB_8888,
            coreFor(0xC0).framebufferFormat,
        )
    }

    @Test
    fun `le format de trame suit la cartouche apres un changement de ROM`() {
        // Le format est une propriété du moteur chargé, pas de sa construction :
        // recharger une autre cartouche doit le faire basculer.
        val core = KotlinGameBoyCore()
        core.loadRom(TestRoms.build(cgbFlag = 0xC0))
        assertEquals(FramebufferFormat.ARGB_8888, core.framebufferFormat)
        core.loadRom(TestRoms.build(cgbFlag = 0x00))
        assertEquals(FramebufferFormat.INDEXED_4, core.framebufferFormat)
    }

    @Test
    fun `l'extension du fichier ne decide de rien`() {
        // Une même image, quel que soit le nom qu'on lui donne, garde le mode
        // que déclare son en-tête. C'est le point de la séparation console /
        // cartouche : `.gb` et `.gbc` ne sont pas des indications fiables.
        val couleur = TestRoms.build(cgbFlag = 0x80)
        val monochrome = TestRoms.build(cgbFlag = 0x00)
        assertTrue(CartridgeHeader.parse(couleur).cartridgeMode.usesColor)
        assertFalse(CartridgeHeader.parse(monochrome).cartridgeMode.usesColor)
        assertTrue("gb" in ConsoleType.GAME_BOY.romExtensions)
        assertTrue("gbc" in ConsoleType.GAME_BOY.romExtensions)
    }
}
