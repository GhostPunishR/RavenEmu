package com.ravenemu.app.ui.player;

import com.ravenemu.app.bridge.NativeAchievements;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** What the game menu shows of RetroAchievements, after the beta's
 * RetroSnapshot: public presentation data copied on the GL thread, never a
 * token. Immutable. */
final class AchievementsSnapshot {
    /** The beta's RetroSync labels; NONE for an achievement still locked. */
    static final int SYNC_NONE = 0, SYNC_LOCAL = 1, SYNC_PENDING = 2, SYNC_CONFIRMED = 3;
    static final String[] SYNC_LABELS = {"", "Débloqué localement", "En attente d'envoi",
            "Confirmé par RetroAchievements"};
    /** The beta's RetroAchievementSort. */
    static final int SORT_DEFAULT = 0, SORT_PROGRESS = 1, SORT_DATE = 2;
    static final String[] SORT_LABELS = {"Ordre du jeu", "Progression", "Date de déblocage"};
    static final int FILTER_ALL = 0, FILTER_LOCKED = 1, FILTER_UNLOCKED = 2;

    static final AchievementsSnapshot SIGNED_OUT = new AchievementsSnapshot(false, "Non connecté", "", "", "", 0, 0,
            "", 0, 0, 0, 0, 0, "", new NativeAchievements.Entry[0], new int[0], new NativeAchievements.Board[0],
            null, 0, false, false, "", 0);

    final boolean signedIn;
    final String status, user, displayName, avatarUrl, gameTitle, richPresence;
    final long softcorePoints, hardcorePoints, gameId, unlocked, total, pointsUnlocked, pointsTotal;
    final NativeAchievements.Entry[] entries;
    /** SYNC_* of each entry, in the same order. */
    final int[] sync;
    final NativeAchievements.Board[] boards;
    /** The scores last fetched, or null. */
    final NativeAchievements.Scores scores;
    final int scoresBoard;
    final boolean scoresAround, scoresLoading;
    final String scoresError;
    /** Unlocks this device still has to send, from the offline journal. */
    final int pendingAwards;

    AchievementsSnapshot(boolean signedIn, String status, String user, String displayName, String avatarUrl,
                         long softcorePoints, long hardcorePoints, String gameTitle, long gameId, long unlocked,
                         long total, long pointsUnlocked, long pointsTotal, String richPresence,
                         NativeAchievements.Entry[] entries, int[] sync, NativeAchievements.Board[] boards,
                         NativeAchievements.Scores scores, int scoresBoard, boolean scoresAround,
                         boolean scoresLoading, String scoresError, int pendingAwards) {
        this.signedIn = signedIn; this.status = status; this.user = user; this.displayName = displayName;
        this.avatarUrl = avatarUrl; this.softcorePoints = softcorePoints; this.hardcorePoints = hardcorePoints;
        this.gameTitle = gameTitle; this.gameId = gameId; this.unlocked = unlocked; this.total = total;
        this.pointsUnlocked = pointsUnlocked; this.pointsTotal = pointsTotal; this.richPresence = richPresence;
        this.entries = entries; this.sync = sync; this.boards = boards; this.scores = scores;
        this.scoresBoard = scoresBoard; this.scoresAround = scoresAround; this.scoresLoading = scoresLoading;
        this.scoresError = scoresError; this.pendingAwards = pendingAwards;
    }

    AchievementsSnapshot withPending(int count) {
        return new AchievementsSnapshot(signedIn, status, user, displayName, avatarUrl, softcorePoints,
                hardcorePoints, gameTitle, gameId, unlocked, total, pointsUnlocked, pointsTotal, richPresence,
                entries, sync, boards, scores, scoresBoard, scoresAround, scoresLoading, scoresError,
                Math.max(0, count));
    }

    String name() { return displayName.isEmpty() ? user : displayName; }

    /** The beta's RetroAccountCard lines: name, points, game, mode. */
    String[] cardLines() {
        if (!signedIn || user.isEmpty()) {
            boolean busy = status.startsWith("Connexion");
            return new String[]{busy ? "Connexion en cours…" : "Non connecté",
                    busy || status.equals("Non connecté") ? "Touchez pour vous connecter" : status, "", ""};
        }
        String game;
        if (!gameTitle.isEmpty()) {
            game = unlocked + "/" + total + " succès  ·  " + pointsUnlocked + "/" + pointsTotal + " pts"
                    + (pendingAwards > 0 ? "  ·  " + pendingAwards + " en attente" : "");
        } else {
            game = status.startsWith("Connecté") ? "Aucun jeu reconnu pour le moment." : status;
        }
        return new String[]{name(), "Softcore " + softcorePoints + " pts  ·  Hardcore " + hardcorePoints + " pts",
                game, "Softcore"};
    }

    /** The beta's retroAchievementList: filter, search, then sort; the
     * indexes of the entries kept. */
    List<Integer> list(String query, int filter, int sort) {
        String text = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<Integer> kept = new ArrayList<>();
        for (int i = 0; i < entries.length; i++) {
            NativeAchievements.Entry entry = entries[i];
            if (filter != FILTER_ALL && (filter == FILTER_UNLOCKED) != entry.unlocked()) continue;
            if (!text.isEmpty() && !entry.title.toLowerCase(Locale.ROOT).contains(text)
                    && !entry.description.toLowerCase(Locale.ROOT).contains(text)) continue;
            kept.add(i);
        }
        if (sort == SORT_PROGRESS) {
            // Locked first, the closest to done first; the game's order otherwise.
            Collections.sort(kept, Comparator.<Integer>comparingInt(i -> entries[i].unlocked() ? 1 : 0)
                    .thenComparing(i -> -entries[i].percent));
        } else if (sort == SORT_DATE) {
            Collections.sort(kept, Comparator.comparingLong(i -> -entries[i].unlockedAt));
        }
        return kept;
    }

    /** The line under an achievement's title, as in the beta. */
    String stateLine(int index) {
        NativeAchievements.Entry entry = entries[index];
        int state = index < sync.length ? sync[index] : SYNC_NONE;
        if (state != SYNC_NONE) return SYNC_LABELS[state];
        if (entry.unlocked()) return "Débloqué";
        if (entry.state == 3) return "Non pris en charge";
        if (!entry.progress.isEmpty()) return entry.progress;
        return "Verrouillé";
    }
}
