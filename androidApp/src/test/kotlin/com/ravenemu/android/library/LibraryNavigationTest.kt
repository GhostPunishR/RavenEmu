package com.ravenemu.android.library

import com.ravenemu.shared.GameEntry
import com.ravenemu.shared.GamePlatform
import com.ravenemu.shared.LibraryNavigation
import com.ravenemu.shared.LibraryPage
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryNavigationTest {
    private val gb = GameEntry("gb", "Tetris", GamePlatform.GB)
    private val gbc = GameEntry("gbc", "Zelda", GamePlatform.GBC)
    private val gba = GameEntry("gba", "Advance Wars", GamePlatform.GBA)

    @Test
    fun emptyLibraryHasSingleAllPage() {
        assertEquals(listOf(LibraryPage.ALL), LibraryNavigation.pages(emptyList()))
    }

    @Test
    fun onlyOneConsoleAvoidsRedundantAllPage() {
        assertEquals(listOf(LibraryPage.GB), LibraryNavigation.pages(listOf(gb)))
    }

    @Test
    fun multipleConsolesFollowConsoleOrder() {
        assertEquals(
            listOf(LibraryPage.ALL, LibraryPage.GB, LibraryPage.GBC, LibraryPage.GBA),
            LibraryNavigation.pages(listOf(gba, gbc, gb)),
        )
    }

    @Test
    fun consolePageSearchFiltersGames() {
        val games = listOf(gb, gbc, gba)
        assertEquals(
            listOf(gb),
            LibraryNavigation.gamesForPage(games, LibraryPage.GB, "tet"),
        )
    }
}
