package com.ravenemu.shared.retro

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RetroIndicatorStateTest {
    @Test
    fun globalProgressHideClearsSwitchedProgressButKeepsChallenges() {
        val state = RetroIndicatorState()
        state.update("challenge_show", "1", "Challenge", "")
        state.update("progress_show", "2", "Old progress", "1/10", "old.png")
        state.update("progress_show", "3", "New progress", "2/10", "new.png")
        assertEquals(RetroProgress("New progress", "2/10", "new.png"), state.progress)
        // Progress has its own popup, apart from challenge and tracker lines.
        assertEquals(listOf("Challenge"), state.labels)
        state.update("progress_hide", "", "", "")
        assertNull(state.progress)
        assertEquals(listOf("Challenge"), state.labels)
        state.update("challenge_hide", "1", "", "")
        assertTrue(state.labels.isEmpty())
    }

    @Test
    fun trackersUpdateInPlaceAndHideById() {
        val state = RetroIndicatorState()
        state.update("tracker_show", "9", "Classement en cours", "0:12.40")
        state.update("tracker_show", "9", "Classement en cours", "0:13.10")
        assertEquals(listOf("Classement en cours • 0:13.10"), state.labels)
        state.update("tracker_hide", "9", "", "")
        assertTrue(state.labels.isEmpty())
    }

    @Test
    fun otherEventsAreNotIndicators() {
        val state = RetroIndicatorState()
        assertFalse(state.handles("achievement"))
        assertFalse(state.handles("leaderboard_started"))
        state.update("achievement", "1", "Not an indicator", "")
        assertTrue(state.labels.isEmpty())
    }

    @Test
    fun popupTextsForUnlocksLeaderboardsAndNotices() {
        assertEquals(RetroBanner("Succès débloqué · 10 points", "Badge", award = true),
            RetroNoticeText.of("achievement", "Badge", "desc", "10"))
        assertEquals("Succès débloqué · 1 point", RetroNoticeText.of("achievement", "Badge", "", "1")?.heading)
        assertEquals(RetroBanner("Classement envoyé", "Speedrun"), RetroNoticeText.of("leaderboard_submitted", "Speedrun", "desc", "4"))
        assertEquals(RetroBanner("Erreur RetroAchievements", "Le serveur a refusé."),
            RetroNoticeText.of("notice", "Erreur RetroAchievements", "Le serveur a refusé.", ""))
        assertEquals(RetroBanner("Mode Hardcore désactivé", "Le redémarrage du jeu a échoué."),
            RetroNoticeText.of("hardcore_refused", "Mode Hardcore désactivé", "Le redémarrage du jeu a échoué.", ""))
        assertNull(RetroNoticeText.of("login", "user", "", "token"))
        assertNull(RetroNoticeText.of("tracker_show", "x", "", ""))
    }

    @Test
    fun aGameThatStartsShowsWhatItOffersWithoutChiming() {
        assertEquals(RetroBanner("42 succès disponibles", "Synthetic game"),
            RetroNoticeText.of("game", "Synthetic game", "42 succès", "42"))
        assertEquals("1 succès disponible", RetroNoticeText.of("game", "G", "", "1")?.heading)
        assertEquals("Aucun succès disponible", RetroNoticeText.of("game", "G", "", "0")?.heading)
        assertEquals("Succès disponibles", RetroNoticeText.of("game", "G", "", "")?.heading)
        assertFalse(RetroNoticeText.of("game", "G", "", "3")!!.award)
    }
}
