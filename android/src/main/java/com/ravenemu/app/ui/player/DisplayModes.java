package com.ravenemu.app.ui.player;


import android.os.Build;
import android.view.Display;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** The display mode to ask for while a game is on screen, as the beta's
 * GameWindow does: a refresh rate the game's divides (60 or 120 Hz rather
 * than 90 Hz), so the native pacer can lock on it. Same size modes only, and
 * from Android 12 only those the screen switches to without blanking. */
final class DisplayModes {
    /** The skew the native pacer accepts (RAVEN_FRAME_PACER_MAX_SKEW). */
    static final double MAX_SKEW = 0.05;
    static final int MAX_VSYNCS = 4;

    private DisplayModes() {}

    /** Refreshes per game frame, or 0: the native raven_frame_cadence rule. */
    static int vsyncsPerFrame(double displayHz, double gameFps) {
        if (!(gameFps >= 1.0 && gameFps <= 240.0) || !(displayHz >= 1.0 && displayHz <= 1000.0)) return 0;
        double ratio = displayHz / gameFps;
        double count = Math.floor(ratio + 0.5);
        if (count < 1.0 || count > MAX_VSYNCS) return 0;
        return Math.abs(count / ratio - 1.0) <= MAX_SKEW ? (int)count : 0;
    }

    /** Keep the current rate when the game divides it, otherwise the highest
     * that fits (120 rather than 60 Hz on a 90 Hz screen); 0 when none does.
     * Asking for the current one also keeps some phones from jumping to 90 Hz
     * at the first touch. */
    static float preferredRefreshRate(float[] rates, float current, double gameFps) {
        if (vsyncsPerFrame(current, gameFps) > 0) return current;
        float best = 0f;
        for (float rate : rates) if (vsyncsPerFrame(rate, gameFps) > 0 && rate > best) best = rate;
        return best;
    }

    /** The mode to request, or 0 to leave the choice to the system. */
    static int modeFor(Display display, double gameFps) {
        if (display == null || !(gameFps > 0.0)) return 0;
        Display.Mode current = display.getMode();
        float[] seamless = null;
        if (Build.VERSION.SDK_INT >= 31) {
            float[] alternatives = current.getAlternativeRefreshRates();
            seamless = Arrays.copyOf(alternatives, alternatives.length + 1);
            seamless[alternatives.length] = current.getRefreshRate();
        }
        List<Display.Mode> modes = new ArrayList<>();
        for (Display.Mode mode : display.getSupportedModes()) {
            if (mode.getPhysicalWidth() != current.getPhysicalWidth()
                    || mode.getPhysicalHeight() != current.getPhysicalHeight()) continue;
            if (seamless != null && !contains(seamless, mode.getRefreshRate())) continue;
            modes.add(mode);
        }
        float[] rates = new float[modes.size()];
        for (int i = 0; i < rates.length; i++) rates[i] = modes.get(i).getRefreshRate();
        float rate = preferredRefreshRate(rates, current.getRefreshRate(), gameFps);
        if (rate <= 0f) return 0;
        if (Math.abs(rate - current.getRefreshRate()) < 0.1f) return current.getModeId();
        for (Display.Mode mode : modes) if (Math.abs(mode.getRefreshRate() - rate) < 0.1f) return mode.getModeId();
        return 0;
    }

    private static boolean contains(float[] rates, float rate) {
        for (float candidate : rates) if (Math.abs(candidate - rate) < 0.1f) return true;
        return false;
    }
}
