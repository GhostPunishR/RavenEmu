package com.ravenemu.app.ui.player;

import com.ravenemu.app.bridge.NativeAchievements;

/** What the player's banner says for each RetroAchievements event: the
 * beta's RetroNoticeText (RetroIndicatorState.kt), and RavenEmu's own lines
 * for the session's state, which the C module reports without text. */
final class AchievementNotices {
    /** heading over title; an award (an unlock, a completed game) is gold
     * and only shown with "Déblocages et bilan"; it rings the chime. */
    static final class Notice {
        final String heading, title, image;
        final boolean award;
        Notice(String heading, String title, String image, boolean award) {
            this.heading = heading; this.title = title; this.image = image; this.award = award;
        }
    }

    private AchievementNotices() {}

    /** The banner for this event, or null for none. */
    static Notice of(NativeAchievements.Event event) {
        switch (event.kind) {
            case "achievement": return new Notice("Succès débloqué · " + points(event.extra), event.title,
                    event.image, true);
            case "completed": return new Notice("Jeu terminé", event.title, event.image, true);
            case "mastered": return new Notice("Jeu maîtrisé", event.title, event.image, true);
            // A game that starts: its icon, its title and what it offers.
            case "game": return new Notice(available(event.detail), event.title, event.image, false);
            case "game_unknown": return new Notice("RetroAchievements",
                    "Ce fichier du jeu n'est pas reconnu : aucun succès", "", false);
            case "game_offline": return new Notice("RetroAchievements hors ligne",
                    "Succès indisponibles : réseau absent au lancement du jeu", "", false);
            case "game_error": return new Notice("Erreur RetroAchievements", or(event.title,
                    "Le jeu n'a pas pu être chargé"), "", false);
            case "login_error": return "offline".equals(event.extra)
                    ? new Notice("RetroAchievements hors ligne",
                            "Succès indisponibles : réseau absent au lancement du jeu", "", false)
                    : new Notice("Connexion RetroAchievements refusée", or(event.title,
                            "Reconnectez le compte dans les paramètres"), "", false);
            // rcheevos retries while the game stays open; the journal, after.
            case "offline": return new Notice("RetroAchievements hors ligne",
                    "Les succès seront envoyés au retour du réseau", "", false);
            case "online": return new Notice("RetroAchievements", "De nouveau en ligne", "", false);
            case "server_error": return new Notice("Erreur RetroAchievements", or(event.title,
                    "Le serveur n'a pas répondu"), "", false);
            case "leaderboard_started": return new Notice("Classement commencé", event.title, "", false);
            case "leaderboard_failed": return new Notice("Classement annulé", event.title, "", false);
            case "leaderboard_submitted": return new Notice("Classement envoyé", event.title, "", false);
            // RavenEmu's own, such as the offline journal being unavailable.
            case "notice": return new Notice(event.title, event.detail, "", false);
            default: return null;
        }
    }

    static String points(String extra) {
        try {
            int count = Integer.parseInt(extra);
            return count > 1 ? count + " points" : count + " point";
        } catch (NumberFormatException malformed) {
            return extra + " points";
        }
    }

    static String available(String count) {
        try {
            int value = Integer.parseInt(count);
            return value == 0 ? "Aucun succès disponible"
                    : value == 1 ? "1 succès disponible" : value + " succès disponibles";
        } catch (NumberFormatException malformed) {
            return "Succès disponibles";
        }
    }

    private static String or(String text, String fallback) {
        return text == null || text.trim().isEmpty() ? fallback : text;
    }
}
