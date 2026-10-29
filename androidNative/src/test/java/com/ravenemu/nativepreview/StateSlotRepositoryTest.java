package com.ravenemu.nativepreview;

import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36)
public final class StateSlotRepositoryTest {
    private static final String KEY = SaveRepository.keyFor("gb", new byte[]{1, 2, 3});
    private static final String OTHER = SaveRepository.keyFor("gb", new byte[]{1, 2, 4});
    private static final String CORE = "libretro/1|RavenCore GB|0.14.0|build-15675cc3|state-v1";
    private static final byte[] STATE = new byte[]{4, 8, 15, 16, 23, 42};
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void allFourSlotsRoundTripIndependentlyWithoutCreatingEmptySlots() throws Exception {
        StateSlotRepository.Slot[] empty = StateSlotRepository.list(files(), KEY, CORE);
        assertEquals(4, empty.length);
        assertFalse(new File(files(), "native-saves").exists());
        for (StateSlotRepository.Slot slot : empty) assertFalse(slot.occupied);
        for (int slot = 0; slot < 4; slot++)
            StateSlotRepository.write(files(), KEY, slot, CORE, new byte[]{(byte)slot, 7});
        StateSlotRepository.Slot[] slots = StateSlotRepository.list(files(), KEY, CORE);
        for (int slot = 0; slot < 4; slot++) {
            assertArrayEquals(new byte[]{(byte)slot, 7}, StateSlotRepository.read(files(), KEY, slot, CORE));
            assertEquals(slot, slots[slot].index);
            assertTrue(slots[slot].occupied);
            assertTrue(slots[slot].compatible);
            assertFalse(slots[slot].legacy);
            assertTrue(slots[slot].savedAt > 0);
            assertNull(slots[slot].error);
            assertFalse(StateSlotRepository.list(files(), OTHER, CORE)[slot].occupied);
            assertNull(StateSlotRepository.read(files(), OTHER, slot, CORE));
        }
    }

    @Test public void compatibilityMismatchLeavesOriginalBytesIntact() throws Exception {
        StateSlotRepository.write(files(), KEY, 0, CORE, STATE);
        byte[] original = Files.readAllBytes(slotFile(KEY, 0).toPath());
        StateSlotRepository.Slot slot = StateSlotRepository.list(files(), KEY, "new-core-build")[0];
        assertTrue(slot.occupied);
        assertFalse(slot.compatible);
        assertNull(slot.error);
        assertThrows(IOException.class, () -> StateSlotRepository.read(files(), KEY, 0, "new-core-build"));
        assertArrayEquals(original, Files.readAllBytes(slotFile(KEY, 0).toPath()));
        assertArrayEquals(STATE, StateSlotRepository.read(files(), KEY, 0, CORE));
    }

    @Test public void copiedStateCannotBeLoadedAsAnotherRom() throws Exception {
        StateSlotRepository.write(files(), KEY, 2, CORE, STATE);
        Files.copy(slotFile(KEY, 2).toPath(), slotFile(OTHER, 2).toPath());
        assertThrows(IOException.class, () -> StateSlotRepository.read(files(), OTHER, 2, CORE));
        StateSlotRepository.Slot invalid = StateSlotRepository.list(files(), OTHER, CORE)[2];
        assertTrue(invalid.occupied);
        assertFalse(invalid.compatible);
        assertNotNull(invalid.error);
        assertArrayEquals(STATE, StateSlotRepository.read(files(), KEY, 2, CORE));
    }

    @Test public void payloadCorruptionIsVisibleAndDoesNotHideHealthySlots() throws Exception {
        StateSlotRepository.write(files(), KEY, 0, CORE, STATE);
        StateSlotRepository.write(files(), KEY, 1, CORE, new byte[]{9, 10});
        try (RandomAccessFile corrupt = new RandomAccessFile(slotFile(KEY, 0), "rw")) {
            corrupt.seek(corrupt.length() - 1);
            corrupt.write(0x99);
        }
        byte[] damaged = Files.readAllBytes(slotFile(KEY, 0).toPath());
        StateSlotRepository.Slot[] slots = StateSlotRepository.list(files(), KEY, CORE);
        assertNotNull(slots[0].error);
        assertFalse(slots[0].compatible);
        assertTrue(slots[1].compatible);
        assertThrows(IOException.class, () -> StateSlotRepository.read(files(), KEY, 0, CORE));
        assertArrayEquals(new byte[]{9, 10}, StateSlotRepository.read(files(), KEY, 1, CORE));
        assertArrayEquals(damaged, Files.readAllBytes(slotFile(KEY, 0).toPath()));
        StateSlotRepository.delete(files(), KEY, 0);
        assertFalse(StateSlotRepository.list(files(), KEY, CORE)[0].occupied);
    }

    @Test public void unknownVersionsTruncationAndTrailingBytesAreRejected() throws Exception {
        StateSlotRepository.write(files(), KEY, 0, CORE, STATE);
        byte[] good = Files.readAllBytes(slotFile(KEY, 0).toPath());
        try (RandomAccessFile file = new RandomAccessFile(slotFile(KEY, 0), "rw")) {
            file.seek(8);
            file.writeInt(99);
        }
        assertThrows(IOException.class, () -> StateSlotRepository.read(files(), KEY, 0, CORE));
        Files.write(slotFile(KEY, 0).toPath(), Arrays.copyOf(good, good.length - 1));
        assertThrows(IOException.class, () -> StateSlotRepository.read(files(), KEY, 0, CORE));
        Files.write(slotFile(KEY, 0).toPath(), Arrays.copyOf(good, good.length + 1));
        assertThrows(IOException.class, () -> StateSlotRepository.read(files(), KEY, 0, CORE));
    }

    @Test public void declaredPayloadSizeAndCoreIdentityAreBoundedBeforeAllocation() throws Exception {
        StateSlotRepository.write(files(), KEY, 0, CORE, STATE);
        byte[] good = Files.readAllBytes(slotFile(KEY, 0).toPath());
        try (RandomAccessFile file = new RandomAccessFile(slotFile(KEY, 0), "rw")) {
            file.seek(8 + 4 + 1 + 64);
            file.writeShort(65535);
        }
        assertThrows(IOException.class, () -> StateSlotRepository.read(files(), KEY, 0, CORE));
        Files.write(slotFile(KEY, 0).toPath(), good);
        try (RandomAccessFile file = new RandomAccessFile(slotFile(KEY, 0), "rw")) {
            file.seek(8 + 4 + 1 + 64 + 2 + CORE.getBytes(StandardCharsets.UTF_8).length + 8);
            file.writeInt(Integer.MAX_VALUE);
        }
        assertThrows(IOException.class, () -> StateSlotRepository.read(files(), KEY, 0, CORE));
    }

    @Test public void invalidWriteCannotReplaceCommittedState() throws Exception {
        StateSlotRepository.write(files(), KEY, 3, CORE, STATE);
        byte[] original = Files.readAllBytes(slotFile(KEY, 3).toPath());
        assertThrows(IOException.class, () -> StateSlotRepository.write(files(), KEY, 3, CORE, null));
        assertThrows(IOException.class, () -> StateSlotRepository.write(files(), KEY, 3, CORE, new byte[0]));
        assertThrows(IOException.class, () -> StateSlotRepository.write(files(), KEY, 3, CORE,
                new byte[16 * 1024 * 1024 + 1]));
        assertArrayEquals(original, Files.readAllBytes(slotFile(KEY, 3).toPath()));
        assertArrayEquals(STATE, StateSlotRepository.read(files(), KEY, 3, CORE));
    }

    @Test public void legacyQuickSaveIsReadOnlyUntilAnExplicitSlotAction() throws Exception {
        File legacy = new File(new File(files(), "native-saves"), KEY + ".state");
        legacy.getParentFile().mkdirs();
        Files.write(legacy.toPath(), STATE);
        StateSlotRepository.Slot slot = StateSlotRepository.list(files(), KEY, CORE)[0];
        assertTrue(slot.occupied);
        assertTrue(slot.legacy);
        assertFalse(slot.compatible);
        assertFalse(slotFile(KEY, 0).exists());
        // The UI is responsible for confirming this unknown-core legacy load.
        assertArrayEquals(STATE, StateSlotRepository.read(files(), KEY, 0, CORE));
        StateSlotRepository.write(files(), KEY, 0, CORE, new byte[]{99});
        assertFalse(StateSlotRepository.list(files(), KEY, CORE)[0].legacy);
        assertArrayEquals(new byte[]{99}, StateSlotRepository.read(files(), KEY, 0, CORE));
        assertArrayEquals(STATE, Files.readAllBytes(legacy.toPath()));
    }

    @Test public void deletionNeverResurrectsLegacyAndLeavesSramAndOtherSlotsAlone() throws Exception {
        File directory = new File(files(), "native-saves");
        directory.mkdirs();
        File legacy = new File(directory, KEY + ".state"), sram = new File(directory, KEY + ".srm");
        Files.write(legacy.toPath(), STATE);
        Files.write(sram.toPath(), new byte[]{11, 12});
        StateSlotRepository.write(files(), KEY, 1, CORE, new byte[]{55});
        StateSlotRepository.delete(files(), KEY, 0);
        assertFalse(StateSlotRepository.list(files(), KEY, CORE)[0].occupied);
        assertNull(StateSlotRepository.read(files(), KEY, 0, CORE));
        assertArrayEquals(STATE, Files.readAllBytes(legacy.toPath()));
        assertArrayEquals(new byte[]{11, 12}, Files.readAllBytes(sram.toPath()));
        assertArrayEquals(new byte[]{55}, StateSlotRepository.read(files(), KEY, 1, CORE));
        // Replacing and deleting a new slot must not uncover the retained source.
        StateSlotRepository.write(files(), KEY, 0, CORE, new byte[]{66});
        StateSlotRepository.delete(files(), KEY, 0);
        StateSlotRepository.delete(files(), KEY, 0);
        assertNull(StateSlotRepository.read(files(), KEY, 0, CORE));
        assertFalse(StateSlotRepository.list(files(), KEY, CORE)[0].occupied);
    }

    @Test public void interruptedReplacementAndHistoricalBackupRecoverCommittedData() throws Exception {
        StateSlotRepository.write(files(), KEY, 0, CORE, STATE);
        File path = slotFile(KEY, 0);
        AtomicFile atomic = new AtomicFile(path);
        FileOutputStream abandoned = atomic.startWrite();
        abandoned.write(new byte[]{1, 2, 3});
        abandoned.close(); // Simulate process loss before finishWrite.
        assertArrayEquals(STATE, StateSlotRepository.read(files(), KEY, 0, CORE));
        Files.copy(path.toPath(), new File(path.getPath() + ".bak").toPath(),
                StandardCopyOption.REPLACE_EXISTING);
        Files.write(path.toPath(), new byte[]{9});
        assertArrayEquals(STATE, StateSlotRepository.read(files(), KEY, 0, CORE));
        assertFalse(new File(path.getPath() + ".bak").exists());
    }

    @Test public void deletionTombstoneSurvivesAnInterruptedReplacement() throws Exception {
        StateSlotRepository.delete(files(), KEY, 0);
        AtomicFile atomic = new AtomicFile(slotFile(KEY, 0));
        FileOutputStream abandoned = atomic.startWrite();
        abandoned.write(1);
        abandoned.close();
        assertNull(StateSlotRepository.read(files(), KEY, 0, CORE));
        assertFalse(StateSlotRepository.list(files(), KEY, CORE)[0].occupied);
    }

    @Test public void untrustedKeysSlotsAndCompatibilityNeverCreateFiles() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> StateSlotRepository.list(files(), "../escape", CORE));
        assertThrows(IllegalArgumentException.class, () -> StateSlotRepository.read(files(), KEY, -1, CORE));
        assertThrows(IllegalArgumentException.class, () -> StateSlotRepository.write(files(), KEY, 4, CORE, STATE));
        assertThrows(IllegalArgumentException.class, () -> StateSlotRepository.delete(files(), null, 0));
        assertThrows(IllegalArgumentException.class, () -> StateSlotRepository.write(files(), KEY, 0, "", STATE));
        assertThrows(IllegalArgumentException.class, () -> StateSlotRepository.write(files(), KEY, 0,
                "x".repeat(1025), STATE));
        assertThrows(IllegalArgumentException.class, () -> StateSlotRepository.write(files(), KEY, 0,
                "bad-\ud800", STATE));
        assertFalse(new File(files(), "native-saves").exists());
    }

    private File files() { return temp.getRoot(); }
    private File slotFile(String key, int slot) {
        return new File(new File(files(), "native-saves"), key + "-slot" + slot + ".state");
    }
}
