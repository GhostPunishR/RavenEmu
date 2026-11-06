package com.ravenemu.app.ui.player;
import com.ravenemu.app.bridge.NativeBridge;

import android.view.KeyEvent;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** The beta's GamepadMapper cases, on the native module's button masks. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36)
public final class GamepadMapperTest {
    private static final int DOWN = KeyEvent.ACTION_DOWN, UP = KeyEvent.ACTION_UP;

    @Test public void faceButtonsPressAndRelease() {
        GamepadMapper mapper = new GamepadMapper();
        assertTrue(mapper.key(KeyEvent.KEYCODE_BUTTON_A, DOWN, 0));
        assertEquals(NativeBridge.A, mapper.mask());
        assertTrue(mapper.key(KeyEvent.KEYCODE_BUTTON_A, UP, 0));
        assertEquals(0, mapper.mask());
        mapper.key(KeyEvent.KEYCODE_BUTTON_START, DOWN, 0);
        mapper.key(KeyEvent.KEYCODE_BUTTON_R2, DOWN, 0);
        assertEquals(NativeBridge.START | NativeBridge.R, mapper.mask());
    }

    @Test public void keyRepeatDoesNotPressAgainAndOtherKeysStayWithTheSystem() {
        GamepadMapper mapper = new GamepadMapper();
        assertFalse(mapper.key(KeyEvent.KEYCODE_BUTTON_B, DOWN, 3));
        assertFalse(mapper.handles(KeyEvent.KEYCODE_BACK));
        assertFalse(mapper.handles(KeyEvent.KEYCODE_VOLUME_UP));
        assertFalse(mapper.key(KeyEvent.KEYCODE_VOLUME_UP, DOWN, 0));
        assertTrue(mapper.handles(KeyEvent.KEYCODE_DPAD_LEFT));
        assertEquals(0, mapper.mask());
    }

    @Test public void theStickMovesTheDpadPastHalfItsTravelAndReleasesOnReturn() {
        GamepadMapper mapper = new GamepadMapper();
        mapper.axes(0f, 0f, -0.9f, 0.1f);
        assertEquals(NativeBridge.LEFT, mapper.mask());
        mapper.axes(0f, 0f, 0.9f, 0f);
        assertEquals(NativeBridge.RIGHT, mapper.mask());
        mapper.axes(0f, 0f, 0.4f, -0.45f);
        assertEquals("Inside the dead zone", 0, mapper.mask());
    }

    @Test public void theHatWinsOverTheStickAndGivesDiagonals() {
        GamepadMapper mapper = new GamepadMapper();
        mapper.axes(1f, -1f, -0.9f, 0.9f);
        assertEquals(NativeBridge.RIGHT | NativeBridge.UP, mapper.mask());
        mapper.axes(0f, 1f, 0f, 0f);
        assertEquals(NativeBridge.DOWN, mapper.mask());
    }

    @Test public void aButtonHeldByTwoSourcesStaysUntilBothRelease() {
        GamepadMapper mapper = new GamepadMapper();
        mapper.key(KeyEvent.KEYCODE_BUTTON_L1, DOWN, 0);
        mapper.key(KeyEvent.KEYCODE_BUTTON_L2, DOWN, 0);
        mapper.key(KeyEvent.KEYCODE_BUTTON_L1, UP, 0);
        assertEquals(NativeBridge.L, mapper.mask());
        mapper.key(KeyEvent.KEYCODE_BUTTON_L2, UP, 0);
        assertEquals(0, mapper.mask());
        mapper.key(KeyEvent.KEYCODE_DPAD_LEFT, DOWN, 0);
        mapper.axes(-1f, 0f, 0f, 0f);
        mapper.key(KeyEvent.KEYCODE_DPAD_LEFT, UP, 0);
        assertEquals("The hat still holds left", NativeBridge.LEFT, mapper.mask());
        mapper.reset();
        assertEquals(0, mapper.mask());
    }
}
