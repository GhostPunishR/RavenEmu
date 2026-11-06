package com.ravenemu.app.ui.player;


import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.view.InputDevice;

/** The cartridge's rumble motor on the phone's vibrator, as in the beta, or
 * on the controller played with when it has a motor (the beta had none).
 * Games set its strength by switching the motor on and off quickly, so each
 * drawn frame with the motor on extends a short pulse: the felt rhythm
 * follows the game's own. The GL thread updates it; the UI thread picks the
 * controller; the lifecycle stops it. */
final class CartridgeRumble {
    /** A little over two frames, so a motor held on never gaps. */
    static final long PULSE_MS = 40L;
    /** At 90 or 120 Hz, one pulse per 60 Hz frame is enough. */
    static final long REARM_NANOS = 16_000_000L;

    /** A vibration motor: the phone's, or a controller's. */
    interface Motor {
        void pulse(long millis);
        void cancel();
    }

    private final Motor phone;
    /** The controller played with, when it has a motor; null: the phone. */
    private Motor controller;
    private boolean running;
    private long lastPulse;

    CartridgeRumble(Context context) {
        this(motor(phoneVibrator(context)));
    }

    /** Tests give their own motors; null for a phone without one. */
    CartridgeRumble(Motor phone) {
        this.phone = phone;
    }

    /** A vibrator as a motor, or null without one. */
    static Motor motor(Vibrator vibrator) {
        if (vibrator == null || !vibrator.hasVibrator()) return null;
        return new Motor() {
            @Override public void pulse(long millis) {
                vibrator.vibrate(VibrationEffect.createOneShot(millis, VibrationEffect.DEFAULT_AMPLITUDE));
            }
            @Override public void cancel() { vibrator.cancel(); }
        };
    }

    /** A controller's own motor, or null when it has none. */
    static Motor motor(InputDevice device) {
        if (device == null) return null;
        if (Build.VERSION.SDK_INT >= 31) return motor(device.getVibratorManager().getDefaultVibrator());
        return motor(legacyVibrator(device));
    }

    @SuppressWarnings("deprecation")
    private static Vibrator legacyVibrator(InputDevice device) { return device.getVibrator(); }

    private static Vibrator phoneVibrator(Context context) {
        if (Build.VERSION.SDK_INT >= 31) {
            VibratorManager manager = context.getSystemService(VibratorManager.class);
            return manager != null ? manager.getDefaultVibrator() : null;
        }
        return context.getSystemService(Vibrator.class);
    }

    /** The controller played with (its motor), or null for the phone. A pulse
     * under way on the other motor stops at once. */
    synchronized void setController(Motor motor) {
        if (motor == controller) return;
        Motor previous = current();
        if (running && previous != null) previous.cancel();
        running = false;
        controller = motor;
    }

    private Motor current() { return controller != null ? controller : phone; }

    /** After each drawn frame: on, the motor runs a little longer; off, the
     * current pulse ends on its own (40 ms at most). */
    synchronized void update(boolean motorOn, long nowNanos) {
        if (!motorOn) {
            running = false;
            return;
        }
        Motor motor = current();
        if (motor == null || (running && nowNanos - lastPulse < REARM_NANOS)) return;
        motor.pulse(PULSE_MS);
        running = true;
        lastPulse = nowNanos;
    }

    /** Leaving the game: stop at once. */
    synchronized void stop() {
        Motor motor = current();
        if (running && motor != null) motor.cancel();
        running = false;
    }
}
