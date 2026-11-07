package com.ravenemu.app.ui.player;

import com.ravenemu.app.bridge.NativeAchievements;

/** What the player's popup says for each RetroAchievements event, laid out as
 * RetroArch's (cheevos.c): a grey line above a white one. A game that starts
 * names itself above how many of its achievements are won; an unlock says so
 * above the achievement and its points; a completed game names itself above
 * the award. RavenEmu adds its own lines for the session's state, which the C
 * module reports without text. */
final class AchievementNotices {
    /** heading (grey) over title (white); an award (an unlock, a completed
     * game) is only shown with "Déblocages et bilan" and rings the chime. */
    static final class Notice {
        final String heading, title, image;
        final boolean award;
        Notice(String heading, String title, String image, boolean award) {
            this.heading = heading; this.title = title; this.image = image; this.award = award;
        }
    }

    private AchievementNotices() {}

    /** The popup for this event, or null for none. */
    static Notice of(NativeAchievements.Event event) {
        switch (event.kind) {
            case "achievement": return new Notice("Succès débloqué", worth(event.title, event.extra),
                    event.image, true);
            case "completed": return new Notice(event.title, "Jeu terminé", event.image, true);
            case "mastered": return new Notice(event.title, "Jeu maîtrisé", event.image, true);
            // A game that starts: its icon, its title and what is already won.
            case "game": return new Notice(event.title, won(event.extra, event.detail), event.image, false);
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

    /** "Title (5)", as RetroArch writes an unlock's points. */
    static String worth(String title, String points) {
        String name = title == null ? "" : title.trim();
        try {
            int count = Integer.parseInt(points);
            return name.isEmpty() ? count + " points" : name + " (" + count + ")";
        } catch (NumberFormatException malformed) {
            return name;
        }
    }

    /** How many of the game's achievements are already won, out of how many. */
    static String won(String unlocked, String total) {
        int all, done;
        try {
            all = Integer.parseInt(total);
        } catch (NumberFormatException malformed) {
            return "Succès disponibles";
        }
        if (all <= 0) return "Ce jeu n'a aucun succès";
        try {
            done = Math.max(0, Math.min(all, Integer.parseInt(unlocked)));
        } catch (NumberFormatException malformed) {
            return all == 1 ? "1 succès disponible" : all + " succès disponibles";
        }
        return "Vous avez débloqué " + done + " succès sur " + all;
    }

    private static String or(String text, String fallback) {
        return text == null || text.trim().isEmpty() ? fallback : text;
    }
}
