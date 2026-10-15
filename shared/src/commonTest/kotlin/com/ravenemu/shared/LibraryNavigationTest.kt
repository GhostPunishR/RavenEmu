package com.ravenemu.shared

import kotlin.test.Test
import kotlin.test.assertEquals

class LibraryNavigationTest {
    private val gb = GameEntry("gb", "Tetris", GamePlatform.GB)
    private val gbc = GameEntry("gbc", "Zelda", GamePlatform.GBC)
    private val gba = GameEntry("gba", "Advance Wars", GamePlatform.GBA)

    @Test fun emptyLibraryHasOnePage() {
        assertEquals(listOf(LibraryPage.ALL), LibraryNavigation.pages(emptyList()))
    }

    @Test fun oneConsoleHasNoRedundantAllPage() {
        assertEquals(listOf(LibraryPage.GB), LibraryNavigation.pages(listOf(gb)))
        assertEquals(listOf(LibraryPage.GBA), LibraryNavigation.pages(listOf(gba)))
    }

    @Test fun consolePagesFollowConsoleOrderRegardlessOfImportOrder() {
        assertEquals(
            listOf(LibraryPage.ALL, LibraryPage.GB, LibraryPage.GBC, LibraryPage.GBA),
            LibraryNavigation.pages(listOf(gba, gbc, gb)),
        )
    }

    @Test fun eachPageFiltersSearchAndSortsItsOwnGames() {
        val games = listOf(gba, gb, gbc, GameEntry("gb2", "A Link", GamePlatform.GB))
        assertEquals(
            listOf("A Link", "Tetris"),
            LibraryNavigation.gamesForPage(games, LibraryPage.GB, "").map { it.title },
        )
        assertEquals(
            listOf("Tetris"),
            LibraryNavigation.gamesForPage(games, LibraryPage.ALL, "tet").map { it.title },
        )
    }
}
