package com.ravenemu.app.data.library;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** The catalogue's index.json, read and rewritten by several threads at once:
 * a library recreated by a rotation starts its own executor while the old one
 * may still be importing a folder. Robolectric for AtomicFile. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36)
public final class RomLibraryIndexTest {
    private final File files = RuntimeEnvironment.getApplication().getFilesDir();

    private static byte[] rom(int thread, int game) {
        byte[] rom = new byte[0x150];
        rom[0x134] = (byte) thread;
        rom[0x135] = (byte) game;
        return rom;
    }

    @Test public void concurrentImportsKeepEveryGameInTheIndex() throws Exception {
        final int threads = 4, games = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Set<String>>> imported = new ArrayList<>();
        try {
            for (int t = 0; t < threads; t++) {
                final int thread = t;
                imported.add(pool.submit(() -> {
                    start.await();
                    Set<String> keys = new HashSet<>();
                    for (int g = 0; g < games; g++) {
                        keys.add(RomLibrary.importRom(files, "Jeu " + thread + "-" + g + ".gb",
                                rom(thread, g)).key);
                        // The other threads list the catalogue meanwhile, as the library does.
                        assertFalse(RomLibrary.list(files).isEmpty());
                    }
                    return keys;
                }));
            }
            start.countDown();
            Set<String> expected = new HashSet<>();
            for (Future<Set<String>> keys : imported) expected.addAll(keys.get(60, TimeUnit.SECONDS));
            Set<String> listed = new HashSet<>();
            for (RomLibrary.Entry entry : RomLibrary.list(files)) listed.add(entry.key);
            assertEquals(threads * games, expected.size());
            assertEquals("No import lost from the index", expected, listed);
        } finally {
            pool.shutdownNow();
        }
    }
}
