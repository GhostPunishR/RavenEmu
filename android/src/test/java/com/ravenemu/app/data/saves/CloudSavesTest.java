package com.ravenemu.app.data.saves;

import static org.junit.Assert.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** The phone's side on real files: a save the cloud replaces is kept aside
 * first, and a game being played or changed meanwhile is never replaced. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36)
public final class CloudSavesTest {
    private static final String GAME = key('7');
    private File files;
    private CloudSaves saves;

    private static String key(char c) {
        char[] chars = new char[64];
        Arrays.fill(chars, c);
        return new String(chars);
    }

    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }

    @Before public void prepare() {
        files = RuntimeEnvironment.getApplication().getFilesDir();
        saves = new CloudSaves(files);
        CloudSaves.clearInPlay();
    }

    @After public void clean() {
        CloudSaves.clearInPlay();
    }

    @Test public void aReplacedSaveIsKeptAsideFiveVersionsDeep() throws Exception {
        assertNull(saves.read(GAME));
        assertTrue("A first save needs nothing kept aside", saves.replace(GAME, null, bytes("v0")));
        assertEquals(0, saves.backups(GAME).length);
        for (int version = 1; version <= 6; version++) {
            byte[] current = saves.read(GAME);
            assertTrue(saves.replace(GAME, CloudSync.sha256(current), bytes("v" + version)));
            Thread.sleep(2);
        }
        assertArrayEquals(bytes("v6"), SaveRepository.read(files, GAME, "srm"));
        File[] kept = saves.backups(GAME);
        assertEquals(CloudSaves.BACKUPS_PER_GAME, kept.length);
        // Newest first: v5 was replaced last, v0 and v1 are gone.
        assertArrayEquals(bytes("v5"), Files.readAllBytes(kept[0].toPath()));
        assertArrayEquals(bytes("v1"), Files.readAllBytes(kept[4].toPath()));
        assertTrue(saves.modified(GAME) > 0);
    }

    @Test public void aSaveChangedSinceTheDecisionOrInPlayStaysAsItIs() throws Exception {
        SaveRepository.write(files, GAME, "srm", bytes("mine"));
        assertFalse("Not the save the sync read", saves.replace(GAME, CloudSync.sha256(bytes("other")), bytes("cloud")));
        assertFalse("A save appeared meanwhile", saves.replace(GAME, null, bytes("cloud")));
        CloudSaves.markInPlay(GAME);
        assertFalse(saves.replace(GAME, CloudSync.sha256(bytes("mine")), bytes("cloud")));
        assertArrayEquals(bytes("mine"), SaveRepository.read(files, GAME, "srm"));
        assertTrue("A game was being played", CloudSaves.clearInPlay());
        assertFalse(CloudSaves.clearInPlay());
        assertTrue(saves.replace(GAME, CloudSync.sha256(bytes("mine")), bytes("cloud")));
        assertArrayEquals(bytes("cloud"), SaveRepository.read(files, GAME, "srm"));
    }
}
