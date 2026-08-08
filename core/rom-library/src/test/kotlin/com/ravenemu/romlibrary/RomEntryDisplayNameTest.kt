package com.ravenemu.romlibrary

import com.ravenemu.emulation.api.ConsoleType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Nom affiché d'une entrée de bibliothèque.
 *
 * Trois sources se succèdent, du plus au moins spécifique : le renommage de
 * l'utilisateur, le titre lu dans la cartouche, le nom de fichier. Le titre
 * d'en-tête est souvent tronqué et en majuscules ; il ne fait pas toujours une
 * entrée lisible, d'où le renommage.
 */
class RomEntryDisplayNameTest {

    private fun entree(
        title: String = "",
        fileName: String = "jeu.gb",
        customTitle: String? = null,
    ) = RomEntry(
        uri = "content://roms/$fileName",
        fileName = fileName,
        sizeBytes = 32768,
        lastModified = 0,
        console = ConsoleType.GAME_BOY,
        title = title,
        fingerprints = Fingerprints("0", "0", "0"),
        status = RomStatus.INTACT,
        customTitle = customTitle,
    )

    @Test
    fun `le renommage prime sur le titre de la cartouche`() {
        assertEquals(
            "Zelda : L'Éveil de Link",
            entree(title = "ZELDA", customTitle = "Zelda : L'Éveil de Link").displayName,
        )
    }

    @Test
    fun `sans renommage le titre de la cartouche est utilise`() {
        assertEquals("ZELDA", entree(title = "ZELDA").displayName)
    }

    @Test
    fun `sans titre de cartouche le nom de fichier sert de repli`() {
        assertEquals("tetris", entree(fileName = "tetris.gb").displayName)
    }

    @Test
    fun `un renommage vide ou blanc ne masque pas le titre`() {
        // Effacer le champ dans l'interface doit rendre le titre d'origine,
        // pas afficher une ligne vide.
        assertEquals("ZELDA", entree(title = "ZELDA", customTitle = "").displayName)
        assertEquals("ZELDA", entree(title = "ZELDA", customTitle = "   ").displayName)
    }

    @Test
    fun `un index anterieur au champ se relit sans migration`() {
        // Le champ est additif : une entrée sérialisée sans lui doit se relire
        // telle quelle, la valeur par défaut s'appliquant. C'est ce qui permet
        // de ne pas migrer l'index déjà installé chez les joueurs.
        val json = """
            {
              "version": 2,
              "entries": [
                {
                  "uri": "content://roms/tetris.gb",
                  "fileName": "tetris.gb",
                  "sizeBytes": 32768,
                  "lastModified": 0,
                  "console": "GAME_BOY",
                  "title": "TETRIS",
                  "fingerprints": { "crc32": "0", "sha1": "0", "sha256": "0" },
                  "status": "INTACT",
                  "cartridgeMode": "DMG_ONLY"
                }
              ]
            }
        """.trimIndent()
        val entree = RomIndex.fromJson(json).entries.single()
        assertNull(entree.customTitle, "Aucun renommage ne doit être inventé")
        assertEquals("TETRIS", entree.displayName)
    }

    @Test
    fun `le renommage survit a un aller-retour JSON`() {
        val entree = entree(title = "ZELDA", customTitle = "Zelda")
        val relu = RomIndex.fromJson(RomIndex().upsert(entree).toJson()).entries.single()
        assertEquals("Zelda", relu.customTitle)
        assertEquals("Zelda", relu.displayName)
    }
}
