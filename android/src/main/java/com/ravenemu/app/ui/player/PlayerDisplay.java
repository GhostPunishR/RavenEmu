package com.ravenemu.app.ui.player;

import android.app.Activity;
import android.view.Display;
import android.view.Surface;
import android.view.WindowManager;
import com.ravenemu.app.bridge.NativeBridge;

/** The display the game is on: its refresh rate and rotation for the native
 * pacer, and a display mode the game's frame rate divides. */
final class PlayerDisplay {
    private final PlayerActivity activity;

    PlayerDisplay(PlayerActivity activity) {
        this.activity = activity;
    }

    /** The display the game is on: the surface's once its window is shown,
     * the default one before. Never Activity.getDisplay(), which throws for a
     * context without a display (Robolectric's activities). */
    Display currentDisplay() {
        Display display = activity.surface != null ? activity.surface.getDisplay() : null;
        if (display == null && activity.displays != null) display = activity.displays.getDisplay(Display.DEFAULT_DISPLAY);
        return display;
    }

    /** UI thread: the native pacer locks on the display only when it knows its
     * rate; the tilt follows the screen, a half turn included. */
    void sendDisplay() {
        Display display = currentDisplay();
        final float hertz = display != null ? display.getRefreshRate() : 0f;
        final int rotation = display != null ? display.getRotation() : Surface.ROTATION_0;
        if (activity.surface != null) activity.surface.queueEvent(() -> {
            if (activity.disposed.get()) return;
            NativeBridge.setDisplayRate(hertz);
            NativeBridge.setDisplayRotation(rotation);
        });
    }

    /** UI thread: a refresh rate the game divides, as long as it is on screen. */
    void applyDisplayMode() {
        int mode = DisplayModes.modeFor(currentDisplay(), activity.gameFps);
        WindowManager.LayoutParams attributes = activity.getWindow().getAttributes();
        if (attributes.preferredDisplayModeId == mode) return;
        attributes.preferredDisplayModeId = mode;
        activity.getWindow().setAttributes(attributes);
    }
}
