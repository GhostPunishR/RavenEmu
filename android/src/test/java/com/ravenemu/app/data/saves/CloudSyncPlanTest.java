package com.ravenemu.app.data.saves;

import static com.ravenemu.app.data.saves.CloudSyncPlan.Action.CONFLICT;
import static com.ravenemu.app.data.saves.CloudSyncPlan.Action.DOWNLOAD;
import static com.ravenemu.app.data.saves.CloudSyncPlan.Action.NONE;
import static com.ravenemu.app.data.saves.CloudSyncPlan.Action.UPLOAD;
import static com.ravenemu.app.data.saves.CloudSyncPlan.decide;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** The three-way rule, case by case: one side changed follows it, both
 * changed is a conflict, and a save missing on one side is copied to it. */
public final class CloudSyncPlanTest {
    private static final String A = "a", B = "b", C = "c";

    @Test public void theSameSaveOnBothSidesOrNoneAtAllDoesNothing() {
        assertEquals(NONE, decide(null, null, null));
        assertEquals(NONE, decide(null, A, null));
        assertEquals(NONE, decide(A, null, A));
        assertEquals(NONE, decide(A, A, A));
        assertEquals(NONE, decide(A, B, A));
    }

    @Test public void aSaveMissingOnOneSideIsCopiedNeverDeleted() {
        assertEquals(UPLOAD, decide(A, null, null));
        // The cloud lost it (another device signed out, a folder emptied): sent again.
        assertEquals(UPLOAD, decide(A, A, null));
        assertEquals(DOWNLOAD, decide(null, null, A));
        // This phone lost it: taken back, not deleted from the cloud.
        assertEquals(DOWNLOAD, decide(null, A, A));
        assertEquals(DOWNLOAD, decide(null, A, B));
    }

    @Test public void aSaveChangedOnOneSideFollowsThatSide() {
        assertEquals(UPLOAD, decide(B, A, A));
        assertEquals(DOWNLOAD, decide(A, A, B));
    }

    @Test public void changedOnBothSidesOrNeverComparedIsAConflict() {
        assertEquals(CONFLICT, decide(B, A, C));
        // A first sync on a phone that already had a different save.
        assertEquals(CONFLICT, decide(A, null, B));
    }
}
