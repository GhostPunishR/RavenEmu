package com.ravenemu.shared

import kotlin.test.Test
import kotlin.test.assertEquals

class RetroDashboardTest {
    private fun entry(id: String, title: String, progress: Float, date: Long = 0) =
        AchievementEntry(id, title, "Explorer le monde", 5, if (date > 0) 2 else 1,
            "${progress.toInt()}%", date, 1, "", progress)

    @Test fun searchAndSortKeepRealSdkMeasurementsAndDates() {
        val entries = listOf(entry("1", "Départ", 10f), entry("2", "Collection", 90f), entry("3", "Victoire", 100f, 100))
        assertEquals(listOf("2", "1", "3"), retroAchievementList(entries, "", 0, RetroAchievementSort.PROGRESS).map { it.id })
        assertEquals(listOf("3", "1", "2"), retroAchievementList(entries, "", 0, RetroAchievementSort.DATE).map { it.id })
        assertEquals(listOf("2"), retroAchievementList(entries, " COLLECTION ", 1, RetroAchievementSort.DEFAULT).map { it.id })
        assertEquals(listOf("3"), retroAchievementList(entries, "monde", 2, RetroAchievementSort.DEFAULT).map { it.id })
    }

    @Test fun completionAndScoreboardMessagesPreserveTheSdkResults() {
        assertEquals(RetroBanner("Jeu terminé", "Synthetic", award = true),
            RetroNoticeText.of("completed", "Synthetic", "Joueur · 10/10 succès · 50 points", ""))
        assertEquals(RetroBanner("Jeu maîtrisé", "Synthetic", award = true), RetroNoticeText.of("mastered", "Synthetic", "Joueur", ""))
        assertEquals(RetroBanner("Classement terminé · Course", "Score : 12.34 · Rang : 2/10"),
            RetroNoticeText.of("leaderboard_scoreboard", "Course", "Score : 12.34 · Rang : 2/10", "7"))
    }

    @Test fun popupPreferencesAreBoundedAndInvalidPositionsFallBackToTop() {
        val safe = Settings(retroPopupPosition = "invalid", retroPopupSeconds = 500, retroProgressSeconds = -2).normalized()
        assertEquals("top", safe.retroPopupPosition)
        assertEquals(10, safe.retroPopupSeconds)
        assertEquals(1, safe.retroProgressSeconds)
    }
}
