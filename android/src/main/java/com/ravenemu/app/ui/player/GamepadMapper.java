package com.ravenemu.app.ui.player;
import com.ravenemu.app.bridge.NativeBridge;

import android.util.SparseIntArray;
import android.view.KeyEvent;
import java.util.HashSet;
import java.util.Set;

/** Physical controllers (Bluetooth or USB) and keyboards, as the beta's
 * GamepadMapper: Android key codes and axes to console buttons. The D-pad
 * also arrives as hat axes on many controllers, and the left stick drives it
 * too, past half its travel. Plain values in, a button mask out: no Android
 * event, so it runs in unit tests. UI thread only. */
final class GamepadMapper {
    /** Past half the travel, as RetroArch's analog-to-digital default. */
    static final float AXIS_THRESHOLD = 0.5f;
    private static final SparseIntArray KEYS = new SparseIntArray();
    static {
        KEYS.put(KeyEvent.KEYCODE_DPAD_UP, NativeBridge.UP);
        KEYS.put(KeyEvent.KEYCODE_DPAD_DOWN, NativeBridge.DOWN);
        KEYS.put(KeyEvent.KEYCODE_DPAD_LEFT, NativeBridge.LEFT);
        KEYS.put(KeyEvent.KEYCODE_DPAD_RIGHT, NativeBridge.RIGHT);
        KEYS.put(KeyEvent.KEYCODE_BUTTON_A, NativeBridge.A);
        KEYS.put(KeyEvent.KEYCODE_BUTTON_B, NativeBridge.B);
        KEYS.put(KeyEvent.KEYCODE_BUTTON_START, NativeBridge.START);
        KEYS.put(KeyEvent.KEYCODE_BUTTON_SELECT, NativeBridge.SELECT);
        // Shoulders and triggers both reach L/R (GBA); a GB game ignores them.
        KEYS.put(KeyEvent.KEYCODE_BUTTON_L1, NativeBridge.L);
        KEYS.put(KeyEvent.KEYCODE_BUTTON_R1, NativeBridge.R);
        KEYS.put(KeyEvent.KEYCODE_BUTTON_L2, NativeBridge.L);
        KEYS.put(KeyEvent.KEYCODE_BUTTON_R2, NativeBridge.R);
    }

    private final Set<Integer> held = new HashSet<>();
    private int axisX, axisY;

    /** True when keyCode is a console button, whatever the event does. */
    boolean handles(int keyCode) { return KEYS.indexOfKey(keyCode) >= 0; }

    /** Records a key event; false for an unmapped key or a key repeat. */
    boolean key(int keyCode, int action, int repeatCount) {
        if (!handles(keyCode)) return false;
        if (action == KeyEvent.ACTION_DOWN && repeatCount == 0) return held.add(keyCode);
        if (action == KeyEvent.ACTION_UP) return held.remove(keyCode);
        return false;
    }

    /** One joystick motion: the hat (a D-pad reported as axes) wins over the stick. */
    void axes(float hatX, float hatY, float stickX, float stickY) {
        axisX = direction(hatX, stickX);
        axisY = direction(hatY, stickY);
    }

    /** The buttons held through keys and axes together. */
    int mask() {
        int mask = 0;
        for (int key : held) mask |= KEYS.get(key);
        if (axisX < 0) mask |= NativeBridge.LEFT;
        if (axisX > 0) mask |= NativeBridge.RIGHT;
        if (axisY < 0) mask |= NativeBridge.UP;
        if (axisY > 0) mask |= NativeBridge.DOWN;
        return mask;
    }

    /** Forget everything, once the session released every button. */
    void reset() {
        held.clear();
        axisX = axisY = 0;
    }

    private static int direction(float hat, float stick) {
        float value = hat != 0f ? hat : stick;
        return value < -AXIS_THRESHOLD ? -1 : value > AXIS_THRESHOLD ? 1 : 0;
    }
}
