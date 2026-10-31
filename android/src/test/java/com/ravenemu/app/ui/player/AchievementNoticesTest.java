package com.ravenemu.app.ui.player;

import com.ravenemu.app.bridge.NativeAchievements;

import org.junit.Test;
import static org.junit.Assert.*;

/** The beta's RetroNoticeText, and RavenEmu's lines for the session. */
public final class AchievementNoticesTest {
    private static AchievementNotices.Notice of(String kind, String title, String detail, String extra) {
        return AchievementNotices.of(new NativeAchievements.Event(kind, title, detail, extra, "badge.png", "1"));
    }

    @Test public void unlocksAndCompletedGamesAreAwards() {
        AchievementNotices.Notice unlock = of("achievement", "Victoire", "Gagner", "10");
        assertEquals("Succès débloqué · 10 points", unlock.heading);
        assertEquals("Victoire", unlock.title);
        assertEquals("badge.png", unlock.image);
        assertTrue(unlock.award);
        assertEquals("Succès débloqué · 1 point", of("achievement", "Un", "", "1").heading);
        assertEquals("Jeu terminé", of("completed", "Synthetic", "1", "1").heading);
        assertEquals("Jeu maîtrisé", of("mastered", "Synthetic", "1", "1").heading);
        assertTrue(of("mastered", "Synthetic", "1", "1").award);
    }

    @Test public void aStartingGameSaysWhatItOffers() {
        AchievementNotices.Notice game = of("game", "Synthetic game", "42", "0");
        assertEquals("42 succès disponibles", game.heading);
        assertEquals("Synthetic game", game.title);
        assertFalse(game.award);
        assertEquals("1 succès disponible", of("game", "G", "1", "0").heading);
        assertEquals("Aucun succès disponible", of("game", "G", "0", "0").heading);
        assertEquals("Succès disponibles", of("game", "G", "?", "0").heading);
    }

    @Test public void theSessionsStateIsToldWithoutBlame() {
        assertEquals("RetroAchievements hors ligne", of("login_error", "", "", "offline").heading);
        AchievementNotices.Notice refused = of("login_error", "Invalid User/Password combination.", "", "refused");
        assertEquals("Connexion RetroAchievements refusée", refused.heading);
        assertEquals("Invalid User/Password combination.", refused.title);
        assertEquals("Reconnectez le compte dans les paramètres", of("login_error", " ", "", "refused").title);
        assertEquals("RetroAchievements hors ligne", of("game_offline", "", "", "").heading);
        assertTrue(of("game_unknown", "", "", "").title.contains("aucun succès"));
        assertEquals("Erreur RetroAchievements", of("server_error", "Le serveur a refusé.", "", "").heading);
        assertTrue(of("offline", "", "", "").title.contains("au retour du réseau"));
        assertEquals("Classement envoyé", of("leaderboard_submitted", "Speedrun", "desc", "").heading);
        assertFalse(of("offline", "", "", "").award);
    }

    @Test public void indicatorsAndTheAccountShowNoBanner() {
        for (String kind : new String[]{"login", "progress_show", "progress_hide", "challenge_show", "challenge_hide",
                "tracker_show", "tracker_hide", "hardcore_reset", "unknown"})
            assertNull(kind, of(kind, "t", "d", "e"));
    }
}
