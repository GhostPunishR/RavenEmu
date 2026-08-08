package com.ravenemu.romlibrary

import com.ravenemu.core.gb.cartridge.GameBoyCartridgeMode
import com.ravenemu.emulation.api.ConsoleType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reprise d'un index écrit par une version antérieure.
 *
 * Un index version 1 portait un booléen `supportsCgb` et pouvait nommer une
 * console `GAME_BOY_COLOR` aujourd'hui disparue. Il doit être **repris**, pas
 * jeté : les pochettes choisies à la main et les entrées connues sont du
 * travail de l'utilisateur. Ce qui ne se déduit pas — la compatibilité exacte
 * de la cartouche, qu'un booléen ne distinguait pas — est marqué pour relecture
 * au prochain balayage plutôt que deviné.
 */
class RomIndexMigrationTest {

    /** Index tel que l'écrivait la version 1, avec `supportsCgb`. */
    private fun jsonVersion1(console: String, supportsCgb: Boolean): String = """
        {
          "version": 1,
          "entries": [
            {
              "uri": "content://roms/zelda.gbc",
              "fileName": "zelda.gbc",
              "sizeBytes": 1048576,
              "lastModified": 1700000000,
              "console": "$console",
              "title": "ZELDA",
              "fingerprints": { "crc32": "DEADBEEF", "sha1": "aa", "sha256": "bb" },
              "status": "INTACT",
              "mbcType": "MBC5",
              "supportsCgb": $supportsCgb,
              "coverUri": "content://images/zelda.png"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `un index version 1 est repris et non efface`() {
        val index = RomIndex.fromJson(jsonVersion1("GAME_BOY", supportsCgb = true))
        assertEquals(RomIndex.FORMAT_VERSION, index.version)
        assertEquals(1, index.entries.size)
        val entree = index.entries.single()
        assertEquals("zelda.gbc", entree.fileName)
        assertEquals(
            "content://images/zelda.png",
            entree.coverUri,
            "La pochette choisie par l'utilisateur doit survivre à la migration",
        )
    }

    @Test
    fun `une entree version 1 est marquee pour relecture`() {
        val index = RomIndex.fromJson(jsonVersion1("GAME_BOY", supportsCgb = true))
        val entree = index.entries.single()
        assertNull(
            entree.cartridgeMode,
            "Le booléen supportsCgb ne distinguait pas 0x80 de 0xC0 : rien à en déduire",
        )
        assertTrue(entree.needsReanalysis)
        assertTrue(
            index.needsRefresh(entree.uri, entree.sizeBytes, entree.lastModified),
            "Le prochain balayage doit relire l'en-tête, même si le fichier n'a pas changé",
        )
    }

    @Test
    fun `la console GAME_BOY_COLOR retiree est ramenee sur le coeur Game Boy`() {
        val index = RomIndex.fromJson(jsonVersion1("GAME_BOY_COLOR", supportsCgb = true))
        assertEquals(
            1,
            index.entries.size,
            "Un nom de console retiré ne doit pas faire perdre l'index entier",
        )
        assertEquals(ConsoleType.GAME_BOY, index.entries.single().console)
    }

    @Test
    fun `une console reellement inconnue fait repartir d'un index vide`() {
        val index = RomIndex.fromJson(jsonVersion1("SUPER_NINTENDO", supportsCgb = false))
        assertEquals(
            emptyList(),
            index.entries,
            "Mieux vaut refuser un index incompris que ranger des ROM au hasard",
        )
    }

    @Test
    fun `une entree analysee par la version courante n'est pas relue`() {
        val entree = RomEntry(
            uri = "content://roms/tetris.gb",
            fileName = "tetris.gb",
            sizeBytes = 32768,
            lastModified = 42,
            console = ConsoleType.GAME_BOY,
            title = "TETRIS",
            fingerprints = Fingerprints("0", "0", "0"),
            status = RomStatus.INTACT,
            cartridgeMode = GameBoyCartridgeMode.DMG_ONLY,
        )
        val index = RomIndex().upsert(entree)
        assertFalse(entree.needsReanalysis)
        assertFalse(index.needsRefresh(entree.uri, 32768, 42))
        assertTrue(
            index.needsRefresh(entree.uri, 32768, 43),
            "Un fichier modifié reste relu",
        )
    }

    @Test
    fun `une entree Game Boy Advance n'attend aucun mode de cartouche`() {
        val entree = RomEntry(
            uri = "content://roms/emeraude.gba",
            fileName = "emeraude.gba",
            sizeBytes = 16777216,
            lastModified = 7,
            console = ConsoleType.GAME_BOY_ADVANCE,
            title = "POKEMON EMER",
            fingerprints = Fingerprints("0", "0", "0"),
            status = RomStatus.HEADER_ONLY,
        )
        assertFalse(
            entree.needsReanalysis,
            "La notion de cartouche couleur n'existe pas sur Game Boy Advance",
        )
        assertFalse(RomIndex().upsert(entree).needsRefresh(entree.uri, 16777216, 7))
    }

    @Test
    fun `un aller-retour JSON conserve le mode de cartouche`() {
        val index = RomIndex(
            entries = GameBoyCartridgeMode.entries.map { mode ->
                RomEntry(
                    uri = "content://${mode.name}",
                    fileName = "${mode.name}.gb",
                    sizeBytes = 32768,
                    lastModified = 0,
                    console = ConsoleType.GAME_BOY,
                    title = mode.name,
                    fingerprints = Fingerprints("0", "0", "0"),
                    status = RomStatus.INTACT,
                    cartridgeMode = mode,
                )
            }
        )
        val relu = RomIndex.fromJson(index.toJson())
        assertEquals(index.entries, relu.entries)
        assertEquals(
            GameBoyCartridgeMode.entries,
            relu.entries.map { it.cartridgeMode },
        )
    }
}
