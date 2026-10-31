package com.ravenemu.app.data.saves;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Robolectric for AtomicFile. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36)
public final class CheatRepositoryTest {
    private final File files = RuntimeEnvironment.getApplication().getFilesDir();
    private static final String KEY = String.join("", Collections.nCopies(8, "0123abcd"));

    @Test public void aGamesListIsWrittenWholeReadBackAndRemovedWhenEmpty() throws Exception {
        assertTrue(CheatRepository.read(files, KEY).isEmpty());
        List<CheatList.Cheat> cheats = Arrays.asList(new CheatList.Cheat("Vies", "010238CD", true),
                new CheatList.Cheat("Argent", "0199B5D0", false));
        CheatRepository.write(files, KEY, cheats);
        File stored = new File(new File(files, "native-cheats"), KEY + ".cheats");
        assertTrue(stored.isFile());
        assertEquals(cheats, CheatRepository.read(files, KEY));
        CheatRepository.write(files, KEY, Collections.emptyList());
        assertFalse(stored.exists());
        assertTrue(CheatRepository.read(files, KEY).isEmpty());
    }

    @Test public void anInvalidKeyOrAnOversizedFileIsRefused() throws Exception {
        try {
            CheatRepository.read(files, "../escape");
            fail();
        } catch (IllegalArgumentException expected) {}
        File folder = new File(files, "native-cheats");
        assertTrue(folder.isDirectory() || folder.mkdirs());
        try (FileOutputStream out = new FileOutputStream(new File(folder, KEY + ".cheats"))) {
            out.write(new byte[CheatRepository.MAX_BYTES + 1]);
        }
        try {
            CheatRepository.read(files, KEY);
            fail();
        } catch (IOException expected) {
            assertEquals("Liste de codes de triche trop grande.", expected.getMessage());
        }
    }
}
