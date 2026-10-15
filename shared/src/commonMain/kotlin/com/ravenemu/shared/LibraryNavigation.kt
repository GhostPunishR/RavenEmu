package com.ravenemu.shared

/** Matches RavenEmu 0.8.1: show only populated console pages.
 * 'Tous les jeux' is useful only with at least two different systems.
 */
enum class LibraryPage(val title: String, val platform: GamePlatform?) {
    ALL("Tous les jeux", null),
    GB("Game Boy", GamePlatform.GB),
    GBC("Game Boy Color", GamePlatform.GBC),
    GBA("Game Boy Advance", GamePlatform.GBA),
}

object LibraryNavigation {
    fun pages(entries: List<GameEntry>): List<LibraryPage> {
        val populated = listOf(LibraryPage.GB, LibraryPage.GBC, LibraryPage.GBA)
            .filter { page -> entries.any { it.platform == page.platform } }
        return when (populated.size) {
            0 -> listOf(LibraryPage.ALL)
            1 -> populated
            else -> listOf(LibraryPage.ALL) + populated
        }
    }

    fun gamesForPage(
        games: List<GameEntry>,
        page: LibraryPage,
        query: String,
    ): List<GameEntry> = LibraryCatalog.search(games, query, page.platform)
        .sortedBy { it.title.lowercase() }
}
