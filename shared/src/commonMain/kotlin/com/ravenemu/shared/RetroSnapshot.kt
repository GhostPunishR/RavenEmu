package com.ravenemu.shared

/** Public, non-secret presentation data copied from the official rcheevos SDK. */
data class AchievementEntry(
    val id: String,
    val title: String,
    val description: String,
    val points: Int,
    val state: Int,
    val progress: String,
    val unlockedAt: Long,
    val bucket: Int,
    val badgeUrl: String,
    /** Numeric measurement supplied by rcheevos, independent of its formatted text. */
    val measuredPercent: Float = 0f,
    val sync: RetroSync = RetroSync.NONE,
    /** Host-formatted date, keeping platform date formatting out of common code. */
    val unlockedDate: String = "",
) {
    val unlocked: Boolean get() = state == 2
}

data class RetroSnapshot(
    val status: String = "Non connecté",
    val username: String = "",
    val displayName: String = "",
    val softcorePoints: Int = 0,
    val hardcorePoints: Int = 0,
    val gameTitle: String = "",
    val gameId: Int = 0,
    val unlockedCount: Int = 0,
    val totalCount: Int = 0,
    val unlockedPoints: Int = 0,
    val totalPoints: Int = 0,
    val avatarUrl: String = "",
    val entries: List<AchievementEntry> = emptyList(),
    val richPresence: String = "",
    val gameImageUrl: String = "",
    val hardcore: Boolean = false,
    val pendingAwards: Int = 0,
    val syncWarning: Boolean = false,
    val leaderboards: List<RetroLeaderboard> = emptyList(),
    val leaderboardView: RetroLeaderboardView = RetroLeaderboardView(),
    val scoreboard: RetroScoreboard? = null,
    val busy: Boolean = false,
    val error: String? = null,
)

/** NONE is used for locked entries; server-loaded unlocks are CONFIRMED. */
enum class RetroSync(val label: String) {
    NONE(""), LOCAL("Débloqué localement"), PENDING("En attente d'envoi"),
    CONFIRMED("Confirmé par RetroAchievements"),
}

data class RetroLeaderboard(
    val id: Int, val title: String, val description: String, val value: String,
    val state: Int, val lowerIsBetter: Boolean,
)
data class RetroLeaderboardEntry(
    val rank: Int, val username: String, val score: String, val submittedDate: String,
    val isUser: Boolean,
)
data class RetroLeaderboardView(
    val id: Int = 0, val loading: Boolean = false, val error: String = "",
    val total: Int = 0, val aroundUser: Boolean = false,
    val entries: List<RetroLeaderboardEntry> = emptyList(),
)
data class RetroScoreboard(
    val id: Int, val title: String, val submitted: String, val best: String,
    val rank: Int, val total: Int,
)

enum class RetroAchievementSort(val label: String) {
    DEFAULT("Ordre du jeu"), PROGRESS("Progression"), DATE("Date de déblocage"),
}

fun retroAchievementList(entries: List<AchievementEntry>, query: String, filter: Int,
                         sort: RetroAchievementSort): List<AchievementEntry> {
    val text = query.trim()
    val filtered = entries.filter {
        (filter == 0 || (filter == 2) == it.unlocked) &&
            (text.isBlank() || it.title.contains(text, ignoreCase = true) ||
                it.description.contains(text, ignoreCase = true))
    }
    return when (sort) {
        RetroAchievementSort.DEFAULT -> filtered
        RetroAchievementSort.PROGRESS -> filtered.sortedWith(
            compareBy<AchievementEntry> { it.unlocked }.thenByDescending { it.measuredPercent })
        RetroAchievementSort.DATE -> filtered.sortedByDescending { it.unlockedAt }
    }
}
