package com.ravenemu.app.ui.player;

import com.ravenemu.app.bridge.NativeAchievements;

import org.junit.Test;
import static org.junit.Assert.*;

/** RetroArch's popup lines (cheevos.c), and RavenEmu's lines for the session. */
public final class AchievementNoticesTest {
    private static AchievementNotices.Notice of(String kind, String title, String detail, String extra) {
        return AchievementNotices.of(new NativeAchievements.Event(kind, title, detail, extra, "badge.png", "1"));
    }

    @Test public void unlocksAndCompletedGamesAreAwardsWordedAsRetroArch() {
        AchievementNotices.Notice unlock = of("achievement", "Victoire", "Gagner", "10");
        assertEquals("Succès débloqué", unlock.heading);
        assertEquals("Victoire (10)", unlock.title);
        assertEquals("badge.png", unlock.image);
        assertTrue(unlock.award);
        assertEquals("Un (1)", of("achievement", "Un", "", "1").title);
        assertEquals("No points given: the title alone", "Un", of("achievement", "Un", "", "?").title);
        AchievementNotices.Notice completed = of("completed", "Synthetic", "1", "1");
        assertEquals("The game above, the award below", "Synthetic", completed.heading);
        assertEquals("Jeu terminé", completed.title);
        assertEquals("Jeu maîtrisé", of("mastered", "Synthetic", "1", "1").title);
        assertTrue(of("mastered", "Synthetic", "1", "1").award);
    }

    @Test public void aStartingGameNamesItselfAboveWhatIsAlreadyWon() {
        AchievementNotices.Notice game = of("game", "Synthetic game", "42", "3");
        assertEquals("Synthetic game", game.heading);
        assertEquals("Vous avez débloqué 3 succès sur 42", game.title);
        assertFalse(game.award);
        assertEquals("Vous avez débloqué 0 succès sur 1", of("game", "G", "1", "0").title);
        assertEquals("Ce jeu n'a aucun succès", of("game", "G", "0", "0").title);
        assertEquals("Never more than the game has", "Vous avez débloqué 5 succès sur 5", of("game", "G", "5", "9").title);
        assertEquals("No unlocked count: what it offers", "5 succès disponibles", of("game", "G", "5", "").title);
        assertEquals("Succès disponibles", of("game", "G", "?", "0").title);
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
