package com.ravenemu.app.ui.player;

import android.content.Intent;
import android.graphics.Bitmap;
import com.ravenemu.app.bridge.NativeBridge;
import com.ravenemu.app.data.saves.CheatList;
import com.ravenemu.app.data.saves.CheatRepository;
import com.ravenemu.app.data.saves.StateSlotRepository;
import com.ravenemu.app.settings.HardcorePolicy;
import com.ravenemu.app.ui.settings.PlaybackSettingsActivity;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The game menu (PlayerMenuSheet): opening it pauses the game, Hardcore
 * permitting; its cheats are kept per game. */
final class PlayerGameMenu {
    private final PlayerActivity activity;

    PlayerGameMenu(PlayerActivity activity) {
        this.activity = activity;
    }

    void togglePlayerMenu() {
        activity.skinPart.finishMovingControls();
        // Linked games: no game menu, as in the beta; the bar switches players.
        if (activity.linkMode) return;
        if (activity.playerMenu != null && activity.playerMenu.isShowing()) {
            activity.playerMenu.dismiss();
            return;
        }
        if (activity.activeSaveKey == null || activity.stateCompatibility == null) {
            activity.postStatus("Charge un jeu avant d’ouvrir le menu");
            return;
        }
        // The menu pauses the game: in Hardcore, rcheevos says whether it may now.
        if (activity.achievements.hardcoreActive() && !activity.pauseGranted) {
            activity.surface.queueEvent(() -> {
                if (activity.disposed.get() || !activity.foreground) return;
                final boolean allowed = activity.achievements.canPause();
                activity.runOnUiThread(() -> {
                    if (activity.disposed.get()) return;
                    if (!allowed) {
                        android.widget.Toast.makeText(activity, HardcorePolicy.PAUSE_REFUSED,
                                android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    activity.pauseGranted = true;
                    try { togglePlayerMenu(); } finally { activity.pauseGranted = false; }
                });
            });
            return;
        }
        activity.menuOpen = true;
        activity.pauseState.menu(true);
        activity.inputPart.releaseInput();
        if (activity.playerMenu != null) activity.selectedSlot = activity.playerMenu.getSelectedSlot();
        final int generation = ++activity.menuGeneration;
        activity.playerMenu = new PlayerMenuSheet(activity, activity.gameTitle.getText().toString(), activity.selectedSlot, activity.badges,
                new PlayerMenuSheet.Listener() {
                    @Override public void onSave(int slot) { activity.slotPart.operateSlot(slot, 0); }
                    @Override public void onLoad(int slot) { activity.slotPart.operateSlot(slot, 1); }
                    @Override public void onDelete(int slot) { activity.slotPart.operateSlot(slot, 2); }
                    @Override public void onUndo() { activity.slotPart.undoLastLoad(); }
                    @Override public void onMoveControls() { activity.skinPart.startMovingControls(); }
                    @Override public void onSettings() {
                        if (!activity.disposed.get() && activity.foreground && !activity.slotBusy && !activity.settingsLaunching) {
                            activity.settingsLaunching = true;
                            activity.startActivity(new Intent(activity, PlaybackSettingsActivity.class));
                        }
                    }
                    @Override public void onAchievementsOpened() { activity.achievementPart.refreshAchievements(); }
                    @Override public void onAchievementsSignIn() {
                        if (!activity.disposed.get() && activity.foreground && !activity.slotBusy && !activity.settingsLaunching) {
                            activity.settingsLaunching = true;
                            activity.startActivity(new Intent(activity, PlaybackSettingsActivity.class)
                                    .putExtra(PlaybackSettingsActivity.EXTRA_PAGE, PlaybackSettingsActivity.RETRO));
                        }
                    }
                    @Override public void onAchievementsRetry() { activity.achievementPart.restartAchievements(); }
                    @Override public void onAchievementsSignOut() { activity.achievementPart.signOutOfAchievements(); }
                    @Override public void onFetchScores(int board, boolean around) {
                        if (activity.disposed.get()) return;
                        activity.surface.queueEvent(() -> {
                            if (!activity.disposed.get() && activity.foreground) activity.achievements.fetchScores(board, around);
                        });
                        activity.achievementPart.refreshAchievements();
                    }
                    @Override public void onOpenLink(String url) {
                        try {
                            activity.startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)));
                        } catch (android.content.ActivityNotFoundException missing) {
                            activity.postStatus("Aucune application pour ouvrir ce lien");
                        }
                    }
                    @Override public void onCheatAdd(String name, String code) { addCheat(name, code); }
                    @Override public void onCheatToggle(int index) {
                        List<CheatList.Cheat> list = new ArrayList<>(activity.cheats);
                        if (index < 0 || index >= list.size() || activity.achievements.cheatsLocked()) return;
                        list.set(index, list.get(index).withEnabled(!list.get(index).enabled));
                        changeCheats(list);
                    }
                    @Override public void onCheatDelete(int index) {
                        List<CheatList.Cheat> list = new ArrayList<>(activity.cheats);
                        if (index < 0 || index >= list.size()) return;
                        list.remove(index);
                        changeCheats(list);
                    }
                    @Override public void onDismiss() {
                        if (generation != activity.menuGeneration) return;
                        activity.selectedSlot = activity.playerMenu.getSelectedSlot();
                        activity.menuOpen = false;
                        if (!activity.disposed.get()) activity.pauseState.menu(false);
                    }
                });
        activity.stateSlots = null;
        activity.statePreviews = new Bitmap[StateSlotRepository.SLOT_COUNT];
        renderPlayerMenu();
        activity.playerMenu.setCheats(activity.cheats, !activity.achievements.cheatsLocked());
        activity.playerMenu.show();
        activity.slotPart.refreshSlots();
        activity.achievementPart.refreshAchievements();
    }

    /** The beta's GbCheatsUi.add: a code the core takes is kept, on; a refused
     * one stays in the form with the core's reason. UI thread. */
    void addCheat(String name, String code) {
        final PlayerMenuSheet menu = activity.playerMenu;
        if (menu == null || activity.disposed.get()) return;
        final String line = CheatList.oneLine(code);
        final String key = activity.cheatsKey;
        if (line.isEmpty()) { menu.cheatRefused("Saisissez un code."); return; }
        if (key == null) { menu.cheatRefused("Codes de triche pas encore prêts."); return; }
        if (activity.achievements.cheatsLocked()) { menu.cheatRefused(CheatsPanel.HARDCORE); return; }
        final List<CheatList.Cheat> before = activity.cheats;
        final CheatList.Cheat added = new CheatList.Cheat(
                name == null || name.trim().isEmpty() ? "Code " + (before.size() + 1) : name.trim(), line, true);
        List<CheatList.Cheat> candidate = new ArrayList<>(before);
        candidate.add(added);
        final String[] codes = CheatList.active(candidate);
        activity.queueCore(() -> {
            if (!key.equals(activity.activeSaveKey)) return;
            // Hardcore came on since the form was checked: the lock wins.
            if (activity.cheatsLockApplied) {
                activity.runOnUiThread(() -> { if (!activity.disposed.get()) menu.cheatRefused(CheatsPanel.HARDCORE); });
                return;
            }
            final int refused = NativeBridge.setCheats(codes);
            final String reason = NativeBridge.cheatRefusal();
            activity.runOnUiThread(() -> {
                if (activity.disposed.get() || !key.equals(activity.cheatsKey)) return;
                if (refused == -2) {
                    menu.cheatRefused("Codes de triche indisponibles pour ce jeu.");
                } else if (refused == codes.length - 1) {
                    // The core keeps the others as they were.
                    menu.cheatRefused(reason == null ? "Code refusé par le core." : reason);
                } else {
                    List<CheatList.Cheat> now = new ArrayList<>(activity.cheats);
                    now.add(added);
                    changeCheats(now);
                    menu.cheatAdded();
                }
            });
        });
    }

    /** A new list: shown at once, then given to the core and written whole. */
    void changeCheats(List<CheatList.Cheat> list) {
        final String key = activity.cheatsKey;
        if (key == null || activity.disposed.get()) return;
        final List<CheatList.Cheat> kept = Collections.unmodifiableList(new ArrayList<>(list));
        activity.cheats = kept;
        if (activity.playerMenu != null) activity.playerMenu.setCheats(kept, !activity.achievements.cheatsLocked());
        activity.queueCore(() -> {
            if (!key.equals(activity.activeSaveKey) || activity.cheatsLockApplied) return;
            NativeBridge.setCheats(CheatList.active(kept));
            String reason = NativeBridge.cheatRefusal();
            if (reason != null) activity.postStatus(reason);
        });
        activity.submitIo(() -> {
            try { CheatRepository.write(activity.getFilesDir(), key, kept); }
            catch (IOException | RuntimeException failure) {
                activity.postStatus("Codes de triche non enregistrés : " + failure.getMessage());
            }
        });
    }

    void renderPlayerMenu() {
        if (activity.playerMenu == null) return;
        activity.playerMenu.render(activity.stateSlots, activity.slotBusy);
        activity.playerMenu.setPreviews(activity.statePreviews);
        activity.playerMenu.setUndoAvailable(activity.slotPart.canUndo());
    }
}
