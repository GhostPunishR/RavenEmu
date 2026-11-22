package com.ravenemu.app.ui.player;

import android.os.Handler;
import android.os.Looper;
import com.ravenemu.app.bridge.NativeBridge;
import com.ravenemu.app.data.library.RomLibrary;
import com.ravenemu.app.platform.AchievementsHttp;
import com.ravenemu.app.platform.RetroUserAgent;
import com.ravenemu.app.settings.AchievementAccount;
import com.ravenemu.app.settings.AchievementSettings;
import com.ravenemu.app.settings.AwardJournal;
import com.ravenemu.app.settings.HardcorePolicy;
import java.io.IOException;

/** The game's RetroAchievements session: the account it runs with, its
 * refresh for the game menu, its restart and the sign-out. */
final class PlayerAchievementSession {
    private final PlayerActivity activity;

    PlayerAchievementSession(PlayerActivity activity) {
        this.activity = activity;
    }

    /** The menu's RetroAchievements: a snapshot on the GL thread, the
     * unlocks still waiting counted from the journal off it. */
    void refreshAchievements() {
        final PlayerMenuSheet menu = activity.playerMenu;
        if (activity.disposed.get() || menu == null || !menu.isShowing() || activity.surface == null) return;
        activity.surface.queueEvent(() -> {
            if (activity.disposed.get() || !activity.foreground) return;
            final AchievementsSnapshot snapshot = activity.achievements.snapshot();
            final AwardJournal journal = activity.achievements.journal();
            activity.submitIo(() -> {
                int pending = journal == null || !snapshot.signedIn ? 0 : journal.forAccount(snapshot.user).size();
                activity.runOnUiThread(() -> {
                    if (!activity.disposed.get() && activity.playerMenu == menu && menu.isShowing())
                        menu.setAchievements(snapshot.withPending(pending));
                });
            });
        });
    }

    /** Events come in bursts (progress, scores): one refresh for them. */
    void refreshAchievementsSoon() {
        if (activity.achievementsRefreshQueued || activity.playerMenu == null || !activity.playerMenu.isShowing()) return;
        activity.achievementsRefreshQueued = true;
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            activity.achievementsRefreshQueued = false;
            refreshAchievements();
        }, 250);
    }

    /** The account and the mode a game's achievements run with. */
    static String accountKey(AchievementAccount account, boolean hardcoreRequested) {
        return account == null ? null
                : account.user + "\n" + account.token + "\n" + HardcorePolicy.enabled(hardcoreRequested);
    }

    /** The stored account now, for the game running: signed in, out, or
     * another one in Settings → RetroAchievements, as the beta applied it at
     * once. "Réessayer" asks for the same with the same account. */
    void restartAchievements() { refreshAchievementAccount(true); }

    void refreshAchievementAccount(boolean force) {
        final RomLibrary.Entry entry = activity.activeEntry;
        if (entry == null || activity.disposed.get()) return;
        activity.submitIo(() -> {
            final AchievementAccount account = AchievementAccount.load(activity);
            final boolean hardcore = AchievementSettings.load(activity).hardcore;
            final String key = accountKey(account, hardcore);
            final String previous = activity.activeAccount;
            if (!force && java.util.Objects.equals(key, previous)) return;
            // Hardcore turned on during the game: it starts over, as in the beta.
            final boolean restartGame = account != null && HardcorePolicy.enabled(hardcore)
                    && (previous == null || !previous.endsWith("\ntrue"));
            byte[] rom = null;
            if (account != null) {
                try { rom = RomLibrary.readRom(activity.getFilesDir(), entry); }
                catch (IOException unreadable) { activity.postStatus("RetroAchievements : jeu illisible"); return; }
            }
            final byte[] game = rom;
            final AchievementsHttp http = account == null ? null : new AchievementsHttp(RetroUserAgent.of(activity));
            final AwardJournal journal = account == null ? null : AwardJournal.open(activity);
            if (activity.disposed.get()) return;
            activity.surface.queueEvent(() -> {
                // Paused meanwhile: activeAccount is unchanged, onResume starts over.
                if (activity.disposed.get() || !activity.foreground || activity.activeEntry != entry) return;
                if (restartGame) NativeBridge.resetGame();
                activity.achievements.start(http, account, journal, entry.system, game, hardcore);
                activity.activeAccount = key;
                activity.runOnUiThread(() -> {
                    if (activity.disposed.get()) return;
                    if (http != null) activity.badges.setHttp(http);
                    activity.achievementOverlay.clear();
                    refreshAchievements();
                });
            });
        });
    }

    /** From the game menu, as in the beta: the token and the unlocks still
     * waiting are forgotten on this device. */
    void signOutOfAchievements() {
        activity.submitIo(() -> {
            AwardJournal.clear(activity);
            AchievementAccount.clear(activity);
            activity.surface.queueEvent(() -> {
                // Paused meanwhile: the account is already gone, onResume stops the session.
                if (activity.disposed.get() || !activity.foreground) return;
                activity.achievements.stop();
                activity.activeAccount = null;
                activity.runOnUiThread(() -> {
                    if (activity.disposed.get()) return;
                    if (activity.playerMenu != null && activity.playerMenu.isShowing()) activity.playerMenu.showMenu();
                    refreshAchievements();
                });
            });
        });
    }
}
