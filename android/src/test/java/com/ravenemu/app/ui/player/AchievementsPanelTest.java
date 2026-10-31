package com.ravenemu.app.ui.player;

import com.ravenemu.app.bridge.FakeAchievements;
import com.ravenemu.app.bridge.NativeAchievements;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowDialog;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** The beta's RetroAchievements panel of the game menu, on canned data. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36, qualifiers = "fr-rFR-w360dp-h640dp-mdpi", shadows = FakeAchievements.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class AchievementsPanelTest {
    private Activity activity;
    private AchievementsPanel panel;
    private final List<String> actions = new ArrayList<>();

    @Before public void prepare() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        panel = new AchievementsPanel(activity, new BadgeCache(), new AchievementsPanel.Actions() {
            @Override public void back() { actions.add("back"); }
            @Override public void signIn() { actions.add("signIn"); }
            @Override public void retry() { actions.add("retry"); }
            @Override public void signOut() { actions.add("signOut"); }
            @Override public void fetchScores(int board, boolean around) { actions.add("scores " + board + " " + around); }
            @Override public void openLink(String url) { actions.add(url); }
        });
        activity.setContentView(panel.view());
    }

    private static NativeAchievements.Entry entry(int id, String title, int state, long at) {
        return new NativeAchievements.Entry(id, title, "Description " + title, 5, state, state == 2 ? "" : "1/3", at, 1,
                "", state == 2 ? 100f : 33f);
    }

    private static AchievementsSnapshot game(int count, NativeAchievements.Scores scores, int board, boolean loading,
                                             String error) {
        NativeAchievements.Entry[] entries = new NativeAchievements.Entry[count];
        for (int i = 0; i < count; i++) entries[i] = entry(100 + i, "Succès " + i, i == 0 ? 2 : 1, i == 0 ? 1_700_000_000L : 0);
        NativeAchievements.Board[] boards = {new NativeAchievements.Board(4401, "Course", "Le plus haut", "", 1, false)};
        return new AchievementsSnapshot(true, "Connecté • Mode normal (Softcore)", "Raven", "Raven Rouge", "", 34, 12,
                "Jeu de test", 1234, 1, count, 5, 5L * count, "Au niveau 2", entries, new int[count], boards, scores,
                board, false, loading, error, 1);
    }

    @Test public void theAccountTheGameAndAPageOfAchievements() {
        panel.show(game(14, null, 0, false, ""));
        assertNotNull(text("Raven Rouge"));
        assertNotNull(text("@Raven"));
        assertNotNull(text("Softcore : 34 points  ·  Hardcore : 12 points"));
        assertNotNull(text("Jeu de test"));
        assertNotNull(text("Au niveau 2"));
        assertNotNull(text("1/14 succès  ·  5/70 points"));
        assertNotNull(text("1 déblocage(s) en attente sur cet appareil. La confirmation du serveur est nécessaire."));
        assertEquals("Ten first, as in the beta", 10, rows());
        text("Afficher plus (4 restants)").performClick();
        assertEquals(14, rows());
        described("Afficher : Obtenus").performClick();
        assertEquals(1, rows());
        described("Afficher : Tous").performClick();
        ((EditText)described("Rechercher un succès")).setText("Succès 13");
        assertEquals(1, rows());
        assertNotNull(text("Mode Softcore · SDK officiel rcheevos/12.5"));
        described("Reconnecter RetroAchievements pour ce jeu").performClick();
        described("Se déconnecter de RetroAchievements").performClick();
        text("Retour au menu du jeu").performClick();
        assertEquals(java.util.Arrays.asList("retry", "signOut", "back"), actions);
    }

    @Test public void anAchievementOpensItsDetailsAndItsPage() {
        panel.show(game(2, null, 0, false, ""));
        find(panel.view(), view -> view.getContentDescription() != null
                && view.getContentDescription().toString().startsWith("Succès 0, 5 points, Débloqué")).performClick();
        AlertDialog dialog = (AlertDialog)ShadowDialog.getLatestDialog();
        assertTrue(dialog.isShowing());
        assertNotNull(find(dialog.getWindow().getDecorView(), view -> view instanceof TextView
                && ((TextView)view).getText().toString().startsWith("Description Succès 0\n\n5 points · Débloqué")));
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(java.util.Collections.singletonList("https://retroachievements.org/achievement/100"), actions);
    }

    @Test public void aLeaderboardFetchesItsScoresAndShowsThem() {
        panel.show(game(1, null, 0, false, ""));
        assertNotNull(text("Consultation des scores disponible. Les tentatives officielles nécessitent le mode Hardcore."));
        described("Classement Course").performClick();
        assertEquals("scores 4401 false", actions.get(0));
        panel.show(game(1, null, 4401, true, ""));
        assertNotNull(described("Chargement des scores"));
        NativeAchievements.Score[] lines = {new NativeAchievements.Score(1, "Corbeau", "000120", 1615654895L, false),
                new NativeAchievements.Score(2, "Raven", "000060", 1615634566L, true)};
        panel.show(game(1, new NativeAchievements.Scores(4401, 2, true, lines), 4401, false, ""));
        assertNotNull(text("Scores autour de moi · 2 participants"));
        assertNotNull(text("#1 · Corbeau\n000120"));
        assertNotNull(text("#2 · Raven (vous)\n000060"));
        panel.show(game(1, null, 4401, false, "Impossible de charger les scores. Réessayez."));
        text("Réessayer les scores").performClick();
        text("Classement complet sur le site").performClick();
        assertEquals("https://retroachievements.org/leaderboardinfo.php?i=4401", actions.get(actions.size() - 1));
    }

    @Test public void signedOutItInvitesToSignIn() {
        panel.show(AchievementsSnapshot.SIGNED_OUT);
        assertNotNull(text("Non connecté"));
        described("Se connecter à RetroAchievements dans les paramètres").performClick();
        assertEquals(java.util.Collections.singletonList("signIn"), actions);
        assertEquals(0, rows());
    }

    private int rows() {
        int[] count = {0};
        find(panel.view(), view -> {
            if (view.isShown() && view.getContentDescription() != null
                    && view.getContentDescription().toString().matches("Succès \\d+, 5 points, .*")) count[0]++;
            return false;
        });
        return count[0];
    }

    private View text(String value) {
        return find(panel.view(), view -> view instanceof TextView && view.isShown()
                && value.contentEquals(((TextView)view).getText()));
    }

    private View described(String value) {
        View found = find(panel.view(), view -> view.getContentDescription() != null
                && value.contentEquals(view.getContentDescription()));
        assertNotNull(value, found);
        return found;
    }

    private static View find(View view, java.util.function.Predicate<View> predicate) {
        if (predicate.test(view)) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup)view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View result = find(group.getChildAt(i), predicate);
                if (result != null) return result;
            }
        }
        return null;
    }
}
