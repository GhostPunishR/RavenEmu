package com.ravenemu.app.ui.player;

import org.junit.Test;
import static org.junit.Assert.*;

/** The beta's chime: four notes over 0.79 s, a quarter of full scale. */
public final class UnlockChimeTest {
    @Test public void fourStruckNotesRingingOutWithoutAClick() {
        short[] pcm = UnlockChime.pcm(48_000);
        assertEquals("0.24 s of strikes and 0.55 s of ring, in stereo", (int)(0.79 * 48_000) * 2, pcm.length);
        int peak = 0;
        for (int i = 0; i < pcm.length; i += 2) {
            assertEquals("Both channels alike", pcm[i], pcm[i + 1]);
            peak = Math.max(peak, Math.abs(pcm[i]));
        }
        assertEquals("A quarter of full scale", 8000, peak);
        assertEquals("Starts from silence", 0, pcm[0]);
        assertTrue("Ends without a click", Math.abs(pcm[pcm.length - 2]) < 40);
    }

    @Test public void noRateNoSound() {
        assertEquals(0, UnlockChime.pcm(0).length);
        assertEquals(0, UnlockChime.pcm(-1).length);
    }
}
