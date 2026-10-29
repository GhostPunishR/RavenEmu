package com.ravenemu.app;

import org.junit.Test;
import java.io.File;
import static org.junit.Assert.*;

public final class SaveRepositoryTest {
    @Test public void keysAreStableAndSystemScoped() {
        byte[] rom = new byte[]{0, 1, 2, 3, 4};
        String first = SaveRepository.keyFor("gb", rom);
        assertEquals(64, first.length());
        assertTrue(first.matches("[a-f0-9]{64}"));
        assertEquals(first, SaveRepository.keyFor("gb", rom.clone()));
        assertNotEquals(first, SaveRepository.keyFor("gbc", rom));
        assertNotEquals(first, SaveRepository.keyFor("gb", new byte[]{0, 1, 2, 3, 5}));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUntrustedSavePath() throws Exception {
        SaveRepository.read(new File("."), "../../foo", "srm");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUntrustedExtension() throws Exception {
        String key = SaveRepository.keyFor("gb", new byte[]{7});
        SaveRepository.read(new File("."), key, "../state");
    }
}
