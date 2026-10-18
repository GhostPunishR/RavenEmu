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
    val busy: Boolean = false,
    val error: String? = null,
)
