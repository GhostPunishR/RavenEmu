package com.ravenemu.app.ui.player;

import com.ravenemu.app.bridge.NativeAchievements;

import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

/** The beta's retroAchievementList and RetroAccountCard lines. */
public final class AchievementsSnapshotTest {
    private static NativeAchievements.Entry entry(int id, String title, int state, String progress, long at, float percent) {
        return new NativeAchievements.Entry(id, title, "Description de " + title, 5, state, progress, at, 1,
                "https://media.retroachievements.org/Badge/" + id + ".png", percent);
    }

    private static AchievementsSnapshot snapshot(String gameTitle, int pending, int[] sync, NativeAchievements.Entry... entries) {
        return new AchievementsSnapshot(true, "Connecté • Mode normal (Softcore)", "Raven", "Raven Rouge", "", 34, 12,
                gameTitle, gameTitle.isEmpty() ? 0 : 1234, 1, entries.length, 5, 5L * entries.length, "", entries, sync,
                new NativeAchievements.Board[0], null, 0, false, false, "", pending);
    }

    @Test public void filterSearchAndSortAsInTheBeta() {
        AchievementsSnapshot s = snapshot("Jeu", 0, new int[3],
                entry(1, "Premier pas", 1, "3/10", 0, 30f),
                entry(2, "Victoire", 2, "", 1_700_000_000L, 100f),
                entry(3, "Collectionneur", 1, "8/10", 0, 80f));
        assertEquals(Arrays.asList(0, 1, 2), s.list("", AchievementsSnapshot.FILTER_ALL, AchievementsSnapshot.SORT_DEFAULT));
        assertEquals(Arrays.asList(0, 2), s.list("", AchievementsSnapshot.FILTER_LOCKED, AchievementsSnapshot.SORT_DEFAULT));
        assertEquals(Arrays.asList(1), s.list("", AchievementsSnapshot.FILTER_UNLOCKED, AchievementsSnapshot.SORT_DEFAULT));
        assertEquals("Title or description, any case", Arrays.asList(1),
                s.list("  VICT ", AchievementsSnapshot.FILTER_ALL, AchievementsSnapshot.SORT_DEFAULT));
        assertEquals(Arrays.asList(2), s.list("description de coll", AchievementsSnapshot.FILTER_ALL, 0));
        assertEquals("Locked first, the closest first", Arrays.asList(2, 0, 1),
                s.list("", AchievementsSnapshot.FILTER_ALL, AchievementsSnapshot.SORT_PROGRESS));
        assertEquals("The latest unlock first", Arrays.asList(1, 0, 2),
                s.list("", AchievementsSnapshot.FILTER_ALL, AchievementsSnapshot.SORT_DATE));
    }

    @Test public void eachAchievementSaysWhereItStands() {
        AchievementsSnapshot s = snapshot("Jeu", 0,
                new int[]{0, AchievementsSnapshot.SYNC_PENDING, 0, 0},
                entry(1, "A", 1, "3/10", 0, 30f), entry(2, "B", 2, "", 10, 100f),
                entry(3, "C", 3, "", 0, 0f), entry(4, "D", 1, "", 0, 0f));
        assertEquals("3/10", s.stateLine(0));
        assertEquals("En attente d'envoi", s.stateLine(1));
        assertEquals("Non pris en charge", s.stateLine(2));
        assertEquals("Verrouillé", s.stateLine(3));
        assertEquals("Débloqué", snapshot("Jeu", 0, new int[1], entry(2, "B", 2, "", 10, 100f)).stateLine(0));
    }

    @Test public void theCardInTheGameMenu() {
        assertArrayEquals(new String[]{"Non connecté", "Touchez pour vous connecter", "", ""},
                AchievementsSnapshot.SIGNED_OUT.cardLines());
        String[] lines = snapshot("Jeu", 2, new int[2], entry(1, "A", 2, "", 1, 100f), entry(2, "B", 1, "", 0, 0f))
                .cardLines();
        assertEquals("Raven Rouge", lines[0]);
        assertEquals("Softcore 34 pts  ·  Hardcore 12 pts", lines[1]);
        assertEquals("1/2 succès  ·  5/10 pts  ·  2 en attente", lines[2]);
        assertEquals("Softcore", lines[3]);
        assertEquals("Aucun jeu reconnu pour le moment.", snapshot("", 0, new int[0]).cardLines()[2]);
        assertEquals(3, snapshot("Jeu", 0, new int[0]).withPending(3).pendingAwards);
        assertEquals(0, snapshot("Jeu", 0, new int[0]).withPending(-1).pendingAwards);
    }
}
