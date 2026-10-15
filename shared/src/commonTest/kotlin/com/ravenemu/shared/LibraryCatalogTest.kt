package com.ravenemu.shared

import kotlin.test.Test
import kotlin.test.assertEquals

class LibraryCatalogTest {
    private val games = listOf(
        GameEntry("1", "Tetris", GamePlatform.GB),
        GameEntry("2", "Tetris DX", GamePlatform.GBC),
        GameEntry("3", "Advance Wars", GamePlatform.GBA),
    )

    @Test fun searchIgnoresCaseAndWhitespace() {
        assertEquals(listOf("1", "2"), LibraryCatalog.search(games, "  TeTrIs ", null).map { it.id })
    }

    @Test fun filtersByConsoleAndName() {
        assertEquals(listOf("2"),
            LibraryCatalog.search(games, "tetris", GamePlatform.GBC).map { it.id })
    }

    @Test fun emptySearchKeepsAllGames() {
        assertEquals(games, LibraryCatalog.search(games, " ", null))
    }
}
