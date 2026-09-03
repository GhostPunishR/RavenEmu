package com.ravenemu.romlibrary

import com.ravenemu.core.gb.cartridge.GameBoyCartridgeMode
import com.ravenemu.emulation.api.ConsoleType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pages de la bibliothèque : la console devient l'axe de navigation, une page
 * chacune. Ce qui se vérifie ici est ce qu'on ne veut pas voir à l'écran —
 * une page vide qu'il faut traverser, ou une page « Tous » qui répète sa
 * voisine.
 */
class LibraryPagesTest {

    private fun entree(
        nom: String,
        console: ConsoleType = ConsoleType.GAME_BOY,
        mode: GameBoyCartridgeMode? = null,
    ) = RomEntry(
        uri = "content://$nom",
        fileName = nom,
        sizeBytes = 32768,
        lastModified = 0,
        console = console,
        title = nom,
        fingerprints = Fingerprints("0", "0", "0"),
        status = RomStatus.INTACT,
        cartridgeMode = mode,
    )

    private val monochrome = entree("tetris.gb", mode = GameBoyCartridgeMode.DMG_ONLY)
    private val couleur = entree("cristal.gbc", mode = GameBoyCartridgeMode.CGB_ONLY)
    private val advance = entree("emeraude.gba", console = ConsoleType.GAME_BOY_ADVANCE)
    private val ds = entree("donjon.nds", console = ConsoleType.NINTENDO_DS)

    private val gba = ConsoleType.GAME_BOY_ADVANCE.name
    private val nds = ConsoleType.NINTENDO_DS.name

    @Test
    fun `une bibliotheque vide garde une page`() {
        // Sans page, l'écran n'aurait rien à afficher — pas même le message
        // qui explique comment ajouter un dossier.
        assertEquals(listOf(LibraryFilter.ALL), LibraryPages.forEntries(emptyList()))
    }

    @Test
    fun `une seule console ne merite pas de page Tous`() {
        // Elle montrerait exactement la même chose que sa voisine.
        assertEquals(
            listOf(LibraryFilter.GAME_BOY_MONOCHROME_CARTRIDGES),
            LibraryPages.forEntries(listOf(monochrome, entree("mario.gb", mode = GameBoyCartridgeMode.DMG_ONLY))),
        )
    }

    @Test
    fun `plusieurs consoles ouvrent une page Tous en tete`() {
        assertEquals(
            listOf(
                LibraryFilter.ALL,
                LibraryFilter.GAME_BOY_MONOCHROME_CARTRIDGES,
                LibraryFilter.GAME_BOY_COLOR_CARTRIDGES,
                gba,
            ),
            LibraryPages.forEntries(listOf(monochrome, couleur, advance)),
        )
    }

    @Test
    fun `une console absente n'a pas de page`() {
        // C'est tout l'intérêt : ne pas faire feuilleter du vide.
        val pages = LibraryPages.forEntries(listOf(monochrome, advance))
        assertEquals(listOf(LibraryFilter.ALL, LibraryFilter.GAME_BOY_MONOCHROME_CARTRIDGES, gba), pages)
        assertTrue(LibraryFilter.GAME_BOY_COLOR_CARTRIDGES !in pages)
    }

    @Test
    fun `l'ordre suit les generations et non le nombre de jeux`() {
        // Une page ne doit pas changer de place quand la bibliothèque grossit :
        // ici la Game Boy Advance est majoritaire et reste pourtant en dernier.
        val nombreux = List(20) { entree("jeu$it.gba", console = ConsoleType.GAME_BOY_ADVANCE) }
        assertEquals(
            listOf(LibraryFilter.ALL, LibraryFilter.GAME_BOY_MONOCHROME_CARTRIDGES, gba),
            LibraryPages.forEntries(nombreux + monochrome),
        )
    }

    @Test
    fun `la Nintendo DS ferme la marche des generations`() {
        // L'ordre suit le matériel : la DS vient après la Game Boy Advance, et
        // n'apparaît que si la bibliothèque en contient un jeu.
        assertEquals(
            listOf(
                LibraryFilter.ALL,
                LibraryFilter.GAME_BOY_MONOCHROME_CARTRIDGES,
                gba,
                nds,
            ),
            LibraryPages.forEntries(listOf(monochrome, advance, ds)),
        )
        assertTrue(nds !in LibraryPages.forEntries(listOf(monochrome, advance)))
    }

    @Test
    fun `une bibliotheque uniquement Nintendo DS n'a qu'une page`() {
        assertEquals(listOf(nds), LibraryPages.forEntries(listOf(ds)))
    }

    @Test
    fun `la page retenue est retrouvee par son filtre`() {
        val pages = LibraryPages.forEntries(listOf(monochrome, couleur, advance))
        assertEquals(2, LibraryPages.indexOf(pages, LibraryFilter.GAME_BOY_COLOR_CARTRIDGES))
        assertEquals(3, LibraryPages.indexOf(pages, gba))
    }

    @Test
    fun `un filtre dont la page a disparu ramene a la premiere`() {
        // Le dernier jeu d'une console peut avoir été retiré depuis la dernière
        // ouverture de l'écran : mieux vaut la première page qu'un index hors
        // limites.
        val pages = LibraryPages.forEntries(listOf(monochrome, advance))
        assertEquals(0, LibraryPages.indexOf(pages, LibraryFilter.GAME_BOY_COLOR_CARTRIDGES))
    }

    @Test
    fun `un filtre enregistre par une version anterieure reste compris`() {
        // `GAME_BOY_COLOR` était un nom de console ; c'est aujourd'hui un
        // filtre sur la cartouche, et la page correspondante doit se retrouver.
        val pages = LibraryPages.forEntries(listOf(monochrome, couleur, advance))
        assertEquals(2, LibraryPages.indexOf(pages, "GAME_BOY_COLOR"))
    }
}
