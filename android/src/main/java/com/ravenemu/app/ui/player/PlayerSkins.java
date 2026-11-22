package com.ravenemu.app.ui.player;

import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import com.ravenemu.app.data.skins.DeltaSkin;
import com.ravenemu.app.data.skins.DeltaSkinException;
import com.ravenemu.app.data.skins.DeltaSkins;
import com.ravenemu.app.settings.ControlSettings;

/** The Delta skin of the open game's console over the picture, or the classic
 * controls, and the controls moved by the player (PlayerActivity's state). */
final class PlayerSkins {
    private final PlayerActivity activity;

    PlayerSkins(PlayerActivity activity) {
        this.activity = activity;
    }

    /** UI thread: the skin of the open game's console, read again (back from
     * the settings, it may have changed); classic controls without one. */
    void refreshSkin() {
        if (activity.skinView == null || activity.loadedSystem == null || activity.disposed.get()) return;
        final DeltaSkin.Console console = DeltaSkins.console(activity.loadedSystem);
        final boolean haptics = ControlSettings.load(activity).haptics;
        final int request = ++activity.skinRequest;
        final android.content.Context context = activity.getApplicationContext();
        PlayerActivity.SKINS.execute(() -> {
            DeltaSkins.Configuration found;
            try {
                found = new DeltaSkins(context).configuration(console);
            } catch (RuntimeException unreadable) {
                found = null;
            }
            final DeltaSkins.Configuration configuration = found;
            activity.runOnUiThread(() -> {
                if (activity.disposed.get() || request != activity.skinRequest || activity.skinView == null) return;
                // A skin without landscape representations stays chosen: the
                // classic controls stand in for it until the phone turns back.
                if (configuration == null
                        || (activity.classicLandscapePicture != null && configuration.landscape.isEmpty())) {
                    showClassicControls();
                    return;
                }
                DeltaSkins.Configuration current = activity.skinView.configuration();
                // The same archive keeps its drawn page.
                activity.skinView.setConfiguration(current != null && current.sha256.equals(configuration.sha256)
                        ? current : configuration, haptics);
            });
        });
    }

    DeltaSkinView newSkinView(boolean landscape) {
        DeltaSkinView view = new DeltaSkinView(activity, new DeltaSkinView.Listener() {
            @Override public void onButtons(int mask) { activity.inputPart.setTouchButtons(mask); }
            @Override public void onMenu() { activity.menuPart.togglePlayerMenu(); }
            @Override public void onReady(DeltaSkinLayout layout) { showSkin(layout); }
            @Override public void onError(DeltaSkinException.Code code) { skinFailed(); }
        }, landscape);
        view.setEnabled(false);
        return view;
    }

    /** The skin is drawn: the picture takes the game's area, above the skin
     * in portrait, in its screen frame in landscape. */
    void showSkin(DeltaSkinLayout layout) {
        if (activity.pictureFrame == null || activity.gameControls == null) return;
        if (activity.gameControls.isEditing()) finishMovingControls();
        activity.inputPart.releaseInput();
        activity.gameControls.setVisibility(View.GONE);
        activity.gameControls.footprint().setVisibility(View.GONE);
        updateHeaderMenu();
        int width = (int) Math.round(layout.gameArea.width()), height = (int) Math.round(layout.gameArea.height());
        int left = (int) Math.round(layout.gameArea.left), top = (int) Math.round(layout.gameArea.top);
        if (activity.pictureFrame.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams area = new FrameLayout.LayoutParams(width, height);
            area.leftMargin = left;
            area.topMargin = top;
            activity.pictureFrame.setLayoutParams(area);
        } else {
            LinearLayout.LayoutParams area = new LinearLayout.LayoutParams(width, height);
            area.leftMargin = left;
            area.topMargin = top;
            activity.pictureFrame.setLayoutParams(area);
        }
        // As in the beta, the banners use the whole skin, not the picture alone.
        placeAchievementOverlay(activity.skinStage);
    }

    /** The game's picture to RetroAchievements' overlay, so that its corner is
     * the picture's: RavenCore's frame is 240 by 160 on the GBA, 160 by 144 on
     * the Game Boy and the Game Boy Color. */
    void showPictureTo(AchievementOverlay overlay, String system) {
        if (overlay == null || activity.surface == null) return;
        final boolean gba = "gba".equals(system);
        overlay.setPicture(activity.surface, gba ? 240 : 160, gba ? 160 : 144, activity.integerScale);
    }

    /** RetroAchievements' banners and indicators, over the whole skin or over
     * the picture. The overlay takes no touch: the skin's buttons stay live. */
    void placeAchievementOverlay(android.view.ViewGroup parent) {
        if (activity.achievementOverlay == null || parent == null || activity.achievementOverlay.getParent() == parent) return;
        android.view.ViewParent current = activity.achievementOverlay.getParent();
        if (current instanceof android.view.ViewGroup) ((android.view.ViewGroup) current).removeView(activity.achievementOverlay);
        parent.addView(activity.achievementOverlay, new FrameLayout.LayoutParams(-1, -1));
    }

    void showClassicControls() {
        if (activity.skinView == null) return;
        activity.skinView.setConfiguration(null, false);
        if (activity.pictureFrame == null || activity.gameControls == null) return;
        placeAchievementOverlay(activity.pictureFrame);
        if (activity.gameControls.getVisibility() == View.VISIBLE) return;
        activity.inputPart.releaseInput();
        if (activity.classicLandscapePicture != null) activity.pictureFrame.setLayoutParams(activity.classicLandscapePicture);
        else activity.pictureFrame.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1));
        activity.gameControls.setVisibility(View.VISIBLE);
        activity.gameControls.footprint().setVisibility(View.VISIBLE);
        updateHeaderMenu();
    }

    /** Landscape: the picture between the controls' columns, which follow
     * the size chosen in Settings → Interface. */
    void fitLandscapePicture() {
        if (activity.classicLandscapePicture == null || activity.gameControls == null) return;
        int side = activity.dp(activity.gameControls.sideWidth());
        if (activity.classicLandscapePicture.leftMargin == side) return;
        activity.classicLandscapePicture.leftMargin = activity.classicLandscapePicture.rightMargin = side;
        if (activity.gameControls.getVisibility() == View.VISIBLE && activity.pictureFrame != null)
            activity.pictureFrame.setLayoutParams(activity.classicLandscapePicture);
    }

    /** The bar's ⋮ only where the screen has no touch Menu: the controls'
     * own button, in portrait and in landscape, or a skin's; some skins
     * have none. */
    void updateHeaderMenu() {
        if (activity.headerMenu == null || activity.gameControls == null) return;
        boolean classic = activity.gameControls.getVisibility() == View.VISIBLE;
        boolean touchMenu = classic ? activity.gameControls.showsMenu() : activity.skinView != null && activity.skinView.hasMenu();
        activity.headerMenu.setVisibility(touchMenu ? View.GONE : View.VISIBLE);
    }

    /** A skin that cannot be drawn is unselected, as in the beta. */
    void skinFailed() {
        DeltaSkins.Configuration failed = activity.skinView != null ? activity.skinView.configuration() : null;
        showClassicControls();
        if (failed == null) return;
        final android.content.Context context = activity.getApplicationContext();
        PlayerActivity.SKINS.execute(() -> new DeltaSkins(context).fallback(failed.console, failed.sha256));
        android.widget.Toast.makeText(activity, "Skin invalide : commandes classiques restaurées",
                android.widget.Toast.LENGTH_LONG).show();
    }

    /** From the game menu: the game waits while the player drags the controls. */
    void startMovingControls() {
        if (activity.disposed.get() || activity.gameControls == null || activity.gameControls.isEditing()) return;
        if (activity.skinView != null && activity.skinView.configuration() != null) {
            android.widget.Toast.makeText(activity, "Un skin Delta place lui-même ses boutons.",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        activity.pauseState.editing(true);
        if (activity.playerMenu != null) activity.playerMenu.dismiss();
        activity.editingLandscape = activity.gameControls.isLandscape();
        activity.gameControls.setEditing(true);
        activity.editBar.setVisibility(View.VISIBLE);
    }

    /** Keeps the places for the orientation they were chosen in. */
    void finishMovingControls() {
        if (activity.gameControls == null || !activity.gameControls.isEditing()) return;
        activity.gameControls.controlLayout().save(activity, activity.editingLandscape);
        activity.gameControls.setEditing(false);
        activity.editBar.setVisibility(View.GONE);
        activity.pauseState.editing(false);
    }
}
