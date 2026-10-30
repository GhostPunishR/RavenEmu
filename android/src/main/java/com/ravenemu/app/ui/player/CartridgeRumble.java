package com.ravenemu.app.ui.player;


import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

/** The cartridge's rumble motor on the phone's vibrator, as in the beta.
 * Games set its strength by switching the motor on and off quickly, so each
 * drawn frame with the motor on extends a short pulse: the felt rhythm
 * follows the game's own. The GL thread updates it; the lifecycle stops it. */
final class CartridgeRumble {
    /** A little over two frames, so a motor held on never gaps. */
    static final long PULSE_MS = 40L;
    /** At 90 or 120 Hz, one pulse per 60 Hz frame is enough. */
    static final long REARM_NANOS = 16_000_000L;

    private final Vibrator vibrator;
    private boolean running;
    private long lastPulse;

    CartridgeRumble(Context context) {
        Vibrator found;
        if (Build.VERSION.SDK_INT >= 31) {
            VibratorManager manager = context.getSystemService(VibratorManager.class);
            found = manager != null ? manager.getDefaultVibrator() : null;
        } else {
            found = context.getSystemService(Vibrator.class);
        }
        vibrator = found != null && found.hasVibrator() ? found : null;
    }

    /** After each drawn frame: on, the phone vibrates a little longer; off,
     * the current pulse ends on its own (40 ms at most). */
    synchronized void update(boolean motorOn, long nowNanos) {
        if (!motorOn) {
            running = false;
            return;
        }
        if (vibrator == null || (running && nowNanos - lastPulse < REARM_NANOS)) return;
        vibrator.vibrate(VibrationEffect.createOneShot(PULSE_MS, VibrationEffect.DEFAULT_AMPLITUDE));
        running = true;
        lastPulse = nowNanos;
    }

    /** Leaving the game: stop at once. */
    synchronized void stop() {
        if (running && vibrator != null) vibrator.cancel();
        running = false;
    }
}
