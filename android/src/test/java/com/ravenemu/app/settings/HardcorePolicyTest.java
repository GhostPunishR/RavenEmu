package com.ravenemu.app.settings;

import org.junit.Test;
import static org.junit.Assert.*;

/** The beta's RetroHardcorePolicy and HardcoreGate rules. */
public final class HardcorePolicyTest {
    @Test public void notBeforeRetroAchievementsApprovesRavenEmu() {
        assertFalse(HardcorePolicy.approved());
        assertFalse("Asked for, still off", HardcorePolicy.enabled(true));
        assertFalse(HardcorePolicy.enabled(false));
    }

    @Test public void aRunningGameWaitsWhileItCannotBeEvaluated() {
        for (HardcorePolicy.Stage stage : HardcorePolicy.Stage.values()) {
            boolean waits = stage == HardcorePolicy.Stage.SIGNING_IN || stage == HardcorePolicy.Stage.LOADING
                    || stage == HardcorePolicy.Stage.WAITING_NETWORK;
            assertEquals(stage.name(), waits, HardcorePolicy.holdFrames(true, true, stage));
            assertFalse("Softcore never waits", HardcorePolicy.holdFrames(false, true, stage));
            assertFalse("Without a game, nothing waits", HardcorePolicy.holdFrames(true, false, stage));
        }
    }

    @Test public void retriesBackOffUpToAMinute() {
        assertEquals(5_000L, HardcorePolicy.retryDelayMillis(0));
        assertEquals(10_000L, HardcorePolicy.retryDelayMillis(1));
        assertEquals(40_000L, HardcorePolicy.retryDelayMillis(3));
        assertEquals(60_000L, HardcorePolicy.retryDelayMillis(4));
        assertEquals(60_000L, HardcorePolicy.retryDelayMillis(40));
        assertEquals(5_000L, HardcorePolicy.retryDelayMillis(-3));
    }
}
