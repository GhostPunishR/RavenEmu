package com.ravenemu.app.ui.player;


import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public final class PlayerPauseStateTest {
    @Test public void dismissingTheMenuDoesNotResumeAnOperationOrBackgroundActivity() {
        List<Boolean> events = new ArrayList<>();
        PlayerPauseState pause = new PlayerPauseState(events::add);
        assertTrue(pause.isPaused());
        pause.foreground(true); pause.focused(true);
        assertFalse(pause.isPaused());
        pause.menu(true); pause.operation(true); pause.menu(false);
        assertTrue(pause.isPaused());
        pause.foreground(false); pause.operation(false);
        assertTrue(pause.isPaused());
        pause.foreground(true);
        assertFalse(pause.isPaused());
        assertEquals(List.of(false, true, false), events);
    }

    @Test public void returningToTheForegroundWithTheMenuOpenStaysPaused() {
        PlayerPauseState pause = new PlayerPauseState(p -> {});
        pause.foreground(true); pause.focused(true); pause.menu(true);
        pause.foreground(false); pause.focused(false);
        pause.foreground(true); pause.focused(true);
        assertTrue(pause.isPaused());
        pause.menu(false);
        assertFalse(pause.isPaused());
    }

    @Test public void focusLossPausesWithoutRepeatingNativeTransitions() {
        List<Boolean> events = new ArrayList<>();
        PlayerPauseState pause = new PlayerPauseState(events::add);
        pause.foreground(true); pause.focused(true);
        pause.focused(false); pause.focused(false);
        pause.menu(true); pause.focused(true);
        assertTrue(pause.isPaused());
        pause.menu(false);
        assertEquals(List.of(false, true, false), events);
    }
}
