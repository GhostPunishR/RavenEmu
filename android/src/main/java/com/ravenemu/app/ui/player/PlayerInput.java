package com.ravenemu.app.ui.player;

import android.view.InputDevice;
import android.view.KeyEvent;
import com.ravenemu.app.bridge.NativeBridge;

/** The buttons held on the touch screen and on a controller, merged for the
 * core, and the controller whose motor and sensors stand in for the cartridge's. */
final class PlayerInput {
    private final PlayerActivity activity;

    PlayerInput(PlayerActivity activity) {
        this.activity = activity;
    }

    void setTouchButtons(int mask) {
        if (activity.disposed.get() || activity.gamePaused) return;
        // Played on the screen: the phone rumbles and moves again.
        if (mask != 0) controllerUsed(null);
        activity.touchButtons = mask;
        NativeBridge.setButtons(activity.gamepad.mask() | activity.touchButtons);
    }

    void releaseInput() {
        activity.gamepad.reset();
        activity.touchButtons = 0;
        if (activity.gameControls != null) activity.gameControls.releaseAll();
        if (activity.skinView != null) activity.skinView.releaseAll();
        NativeBridge.clearButtons();
    }

    /** The last input played with, UI thread: a controller with a motor
     * rumbles, one with motion sensors moves the cartridge's (Android 12 and
     * later); the touch screen gives both back to the phone. */
    void controllerUsed(InputDevice device) {
        int id = device != null ? device.getId() : -1;
        if (id == activity.controllerId) return;
        activity.controllerId = id;
        activity.rumble.setController(CartridgeRumble.motor(device));
        activity.motion.use(device, activity.sensorsWanted);
    }

    /** A controller key, while the game runs: the buttons it holds reach the core. */
    boolean gamepadKey(int keyCode, KeyEvent event) {
        if (activity.gamePaused || !activity.gamepad.handles(keyCode)) return false;
        controllerUsed(event.getDevice());
        if (activity.gamepad.key(keyCode, event.getAction(), event.getRepeatCount()))
            NativeBridge.setButtons(activity.gamepad.mask() | activity.touchButtons);
        return true;
    }
}
