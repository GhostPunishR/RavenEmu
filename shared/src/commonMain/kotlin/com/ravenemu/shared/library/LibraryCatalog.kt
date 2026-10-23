package com.ravenemu.shared.library

enum class GamePlatform { GB, GBC, GBA }

data class GameEntry(
    val id: String,
    val title: String,
    val platform: GamePlatform,
)

object LibraryCatalog {
    fun search(
        games: List<GameEntry>,
        query: String,
        platform: GamePlatform?,
    ): List<GameEntry> {
        val needle = query.trim()
        return games.filter { game ->
            (platform == null || game.platform == platform) &&
                (needle.isEmpty() || game.title.contains(needle, ignoreCase = true))
        }
    }
}
