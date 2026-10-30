package com.ravenemu.app.data.saves;


import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** A synthetic 16 KiB file stands for the player's BIOS: no BIOS is used. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36)
public final class GbaBiosRepositoryTest {
    private File files;

    @Before public void prepare() {
        files = RuntimeEnvironment.getApplication().getFilesDir();
        GbaBiosRepository.remove(files);
    }

    private static byte[] bios(int size, int seed) {
        byte[] bytes = new byte[size];
        for (int i = 0; i < size; i++) bytes[i] = (byte)(i * 31 + seed);
        return bytes;
    }

    @Test public void aSixteenKibFileIsCopiedWhereTheGbaCoreLooksForIt() throws IOException {
        assertFalse(GbaBiosRepository.installed(files));
        assertNull(GbaBiosRepository.read(files));
        byte[] chosen = bios(GbaBiosRepository.SIZE, 1);
        GbaBiosRepository.install(files, new ByteArrayInputStream(chosen));
        assertTrue(GbaBiosRepository.installed(files));
        assertArrayEquals(chosen, GbaBiosRepository.read(files));
        // RavenCore reads the Libretro system directory's gba_bios.bin.
        assertArrayEquals(chosen, Files.readAllBytes(new File(files, "gba_bios.bin").toPath()));
    }

    @Test public void aFileOfAnotherSizeIsRefusedAndTheInstalledBiosKept() throws IOException {
        byte[] kept = bios(GbaBiosRepository.SIZE, 2);
        GbaBiosRepository.install(files, new ByteArrayInputStream(kept));
        for (int size : new int[]{0, GbaBiosRepository.SIZE - 1, GbaBiosRepository.SIZE + 1, 1024 * 1024}) {
            try {
                GbaBiosRepository.install(files, new ByteArrayInputStream(bios(size, 3)));
                fail("A " + size + "-byte file is not a GBA BIOS");
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("16 Kio"));
            }
            assertArrayEquals(kept, GbaBiosRepository.read(files));
        }
    }

    @Test public void replacingAndRemovingTheBios() throws IOException {
        GbaBiosRepository.install(files, new ByteArrayInputStream(bios(GbaBiosRepository.SIZE, 4)));
        byte[] other = bios(GbaBiosRepository.SIZE, 5);
        GbaBiosRepository.install(files, new ByteArrayInputStream(other));
        assertArrayEquals(other, GbaBiosRepository.read(files));
        GbaBiosRepository.remove(files);
        assertFalse(GbaBiosRepository.installed(files));
        assertNull(GbaBiosRepository.read(files));
        assertFalse(new File(files, "gba_bios.bin").exists());
    }

    @Test public void aDamagedFileIsNotUsed() throws IOException {
        Files.write(new File(files, "gba_bios.bin").toPath(), bios(100, 6));
        assertFalse(GbaBiosRepository.installed(files));
        assertNull(GbaBiosRepository.read(files));
    }

    @Test public void theDigestNamesTheBiosNotItsCopy() {
        byte[] first = bios(GbaBiosRepository.SIZE, 7);
        String digest = GbaBiosRepository.digest(first);
        assertTrue(digest.matches("[0-9a-f]{16}"));
        assertEquals(digest, GbaBiosRepository.digest(Arrays.copyOf(first, first.length)));
        assertNotEquals(digest, GbaBiosRepository.digest(bios(GbaBiosRepository.SIZE, 8)));
    }
}
