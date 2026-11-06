package com.ravenemu.app.ui.player;


import android.content.Context;
import android.os.Vibrator;
import android.os.VibratorManager;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowVibrator;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** The cartridge's motor, as the beta drove the phone's vibrator. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36)
public final class CartridgeRumbleTest {
    private static final long FRAME = 16_666_667L;
    private Context context;
    private ShadowVibrator vibrator;

    @Before public void prepare() {
        context = RuntimeEnvironment.getApplication();
        Vibrator device = context.getSystemService(VibratorManager.class).getDefaultVibrator();
        vibrator = shadowOf(device);
        vibrator.setHasVibrator(true);
        ShadowVibrator.reset();
    }

    @Test public void aMotorHeldOnPulsesEveryFrameWithoutGaps() {
        CartridgeRumble rumble = new CartridgeRumble(context);
        rumble.update(true, 0);
        assertTrue(vibrator.isVibrating());
        assertEquals("A little over two frames", CartridgeRumble.PULSE_MS, vibrator.getMilliseconds());
        ShadowVibrator.reset();
        // At 120 Hz, the refresh in between adds nothing; the next frame does.
        rumble.update(true, FRAME / 2);
        assertFalse(vibrator.isVibrating());
        rumble.update(true, FRAME);
        assertTrue(vibrator.isVibrating());
    }

    @Test public void theMotorOffLetsThePulseEndAndLeavingStopsAtOnce() {
        CartridgeRumble rumble = new CartridgeRumble(context);
        rumble.stop();
        assertFalse("Nothing to stop", vibrator.isCancelled());
        rumble.update(true, 0);
        rumble.update(false, FRAME);
        assertFalse("The pulse ends on its own, as in the beta", vibrator.isCancelled());
        rumble.update(true, 2 * FRAME);
        rumble.stop();
        assertTrue(vibrator.isCancelled());
        ShadowVibrator.reset();
        // Turned on again right after: a fresh pulse, whatever the time.
        rumble.update(true, 2 * FRAME + 1);
        assertTrue(vibrator.isVibrating());
    }

    @Test public void aPhoneWithoutVibratorIsLeftAlone() {
        vibrator.setHasVibrator(false);
        CartridgeRumble rumble = new CartridgeRumble(context);
        rumble.update(true, 0);
        rumble.stop();
        assertFalse(vibrator.isVibrating());
        assertFalse(vibrator.isCancelled());
    }

    @Test public void theControllerPlayedWithRumblesInsteadOfThePhone() {
        java.util.List<String> pad = new java.util.ArrayList<>();
        CartridgeRumble.Motor controller = new CartridgeRumble.Motor() {
            @Override public void pulse(long millis) { pad.add("pulse " + millis); }
            @Override public void cancel() { pad.add("cancel"); }
        };
        CartridgeRumble rumble = new CartridgeRumble(context);
        rumble.setController(controller);
        rumble.update(true, 0);
        assertEquals(java.util.Collections.singletonList("pulse " + CartridgeRumble.PULSE_MS), pad);
        assertFalse("Not the phone", vibrator.isVibrating());
        // Back to the touch screen while it runs: the controller stops at once, the phone takes over.
        rumble.setController(null);
        assertEquals("cancel", pad.get(pad.size() - 1));
        rumble.update(true, FRAME);
        assertTrue(vibrator.isVibrating());
        // The same controller again changes nothing; leaving stops the one running.
        rumble.setController(controller);
        rumble.setController(controller);
        ShadowVibrator.reset();
        pad.clear();
        rumble.update(true, 2 * FRAME);
        rumble.stop();
        assertEquals(java.util.Arrays.asList("pulse " + CartridgeRumble.PULSE_MS, "cancel"), pad);
        assertFalse(vibrator.isVibrating());
    }

    @Test public void aControllerWithoutMotorLeavesThePhoneRumbling() {
        assertNull(CartridgeRumble.motor((android.view.InputDevice) null));
        CartridgeRumble rumble = new CartridgeRumble(context);
        rumble.setController(CartridgeRumble.motor((android.view.InputDevice) null));
        rumble.update(true, 0);
        assertTrue(vibrator.isVibrating());
    }
}
