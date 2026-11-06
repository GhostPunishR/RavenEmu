package com.ravenemu.app.data.saves;


import android.util.AtomicFile;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
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
    private static final byte[] PREVIEW = new byte[]{(byte)0x89, 80, 78, 71, 13, 10, 26, 10, 17, 42};
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
            corrupt.seek(payloadOffset() + STATE.length - 1);
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
        writeV1(KEY, 0, STATE, false);
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

    @Test public void versionOneStatesAndTombstonesRemainReadableWithoutBeingRewritten() throws Exception {
        writeV1(KEY, 0, STATE, false);
        writeV1(KEY, 1, new byte[0], true);
        byte[] stateFile = Files.readAllBytes(slotFile(KEY, 0).toPath());
        byte[] deletionFile = Files.readAllBytes(slotFile(KEY, 1).toPath());
        StateSlotRepository.Slot[] slots = StateSlotRepository.list(files(), KEY, CORE);
        assertTrue(slots[0].occupied);
        assertTrue(slots[0].compatible);
        assertNull(slots[0].thumbnail);
        assertFalse(slots[1].occupied);
        assertNull(slots[1].thumbnail);
        assertArrayEquals(STATE, StateSlotRepository.read(files(), KEY, 0, CORE));
        assertNull(StateSlotRepository.read(files(), KEY, 1, CORE));
        assertArrayEquals(stateFile, Files.readAllBytes(slotFile(KEY, 0).toPath()));
        assertArrayEquals(deletionFile, Files.readAllBytes(slotFile(KEY, 1).toPath()));
    }

    @Test public void versionTwoStoresEachPreviewWithItsGameState() throws Exception {
        for (int slot = 0; slot < 4; slot++) {
            byte[] preview = Arrays.copyOf(PREVIEW, PREVIEW.length + slot);
            StateSlotRepository.write(files(), KEY, slot, CORE, new byte[]{(byte)slot}, preview);
        }
        StateSlotRepository.Slot[] slots = StateSlotRepository.list(files(), KEY, CORE);
        for (int slot = 0; slot < 4; slot++) {
            assertArrayEquals(Arrays.copyOf(PREVIEW, PREVIEW.length + slot), slots[slot].thumbnail);
            assertArrayEquals(new byte[]{(byte)slot}, StateSlotRepository.read(files(), KEY, slot, CORE));
            try (RandomAccessFile encoded = new RandomAccessFile(slotFile(KEY, slot), "r")) {
                encoded.seek(8);
                assertEquals(2, encoded.readInt());
            }
        }
        StateSlotRepository.delete(files(), KEY, 2);
        assertNull(StateSlotRepository.list(files(), KEY, CORE)[2].thumbnail);
        assertFalse(StateSlotRepository.list(files(), KEY, CORE)[2].occupied);
        assertArrayEquals(Arrays.copyOf(PREVIEW, PREVIEW.length + 1),
                StateSlotRepository.list(files(), KEY, CORE)[1].thumbnail);
    }

    @Test public void absentOrDamagedPreviewDoesNotLoseAVerifiedState() throws Exception {
        StateSlotRepository.write(files(), KEY, 0, CORE, STATE, null);
        assertNull(StateSlotRepository.list(files(), KEY, CORE)[0].thumbnail);
        StateSlotRepository.write(files(), KEY, 0, CORE, STATE, new byte[0]);
        assertNull(StateSlotRepository.list(files(), KEY, CORE)[0].thumbnail);
        StateSlotRepository.write(files(), KEY, 0, CORE, STATE, PREVIEW);
        byte[] good = Files.readAllBytes(slotFile(KEY, 0).toPath());
        // Independent thumbnail hash failure.
        byte[] damaged = good.clone(); damaged[damaged.length - 1] ^= 1;
        Files.write(slotFile(KEY, 0).toPath(), damaged);
        assertStateWithoutPreview();
        // A truncated trailer, including its length entirely absent.
        for (int length : new int[]{good.length - 1, payloadOffset() + STATE.length,
                payloadOffset() + STATE.length + 2}) {
            Files.write(slotFile(KEY, 0).toPath(), Arrays.copyOf(good, length));
            assertStateWithoutPreview();
        }
        Files.write(slotFile(KEY, 0).toPath(), Arrays.copyOf(good, good.length + 1));
        assertStateWithoutPreview();
    }

    @Test public void untrustedPreviewLengthIsBoundedBeforeAllocation() throws Exception {
        StateSlotRepository.write(files(), KEY, 0, CORE, STATE, PREVIEW);
        for (int length : new int[]{-1, 256 * 1024 + 1, Integer.MAX_VALUE}) {
            try (RandomAccessFile encoded = new RandomAccessFile(slotFile(KEY, 0), "rw")) {
                encoded.seek(payloadOffset() + STATE.length);
                encoded.writeInt(length);
            }
            assertStateWithoutPreview();
        }
    }

    @Test public void oversizedPreviewWriteCannotReplaceStateOrImage() throws Exception {
        StateSlotRepository.write(files(), KEY, 0, CORE, STATE, PREVIEW);
        byte[] original = Files.readAllBytes(slotFile(KEY, 0).toPath());
        assertThrows(IOException.class, () -> StateSlotRepository.write(files(), KEY, 0, CORE,
                new byte[]{99}, new byte[256 * 1024 + 1]));
        assertArrayEquals(original, Files.readAllBytes(slotFile(KEY, 0).toPath()));
        assertArrayEquals(STATE, StateSlotRepository.read(files(), KEY, 0, CORE));
        assertArrayEquals(PREVIEW, StateSlotRepository.list(files(), KEY, CORE)[0].thumbnail);
    }

    @Test public void previewsAtTheLimitRemainBoundedAndConstructorClonesItsInput() throws Exception {
        byte[] bounded = new byte[256 * 1024];
        bounded[bounded.length - 1] = 7;
        StateSlotRepository.write(files(), KEY, 0, CORE, STATE, bounded);
        assertArrayEquals(bounded, StateSlotRepository.list(files(), KEY, CORE)[0].thumbnail);
        StateSlotRepository.Slot slot = new StateSlotRepository.Slot(0, true, true, false,
                1, null, bounded);
        bounded[0] = 9;
        assertEquals(0, slot.thumbnail[0]);
    }

    private void assertStateWithoutPreview() throws Exception {
        StateSlotRepository.Slot slot = StateSlotRepository.list(files(), KEY, CORE)[0];
        assertTrue(slot.occupied);
        assertTrue(slot.compatible);
        assertNull(slot.error);
        assertNull(slot.thumbnail);
        assertArrayEquals(STATE, StateSlotRepository.read(files(), KEY, 0, CORE));
    }

    private static int payloadOffset() {
        return 8 + 4 + 1 + 64 + 2 + CORE.getBytes(StandardCharsets.UTF_8).length + 8 + 4 + 32;
    }

    /** A fixture matching the shipped v1 layout, independent of the v2 writer. */
    private void writeV1(String key, int slot, byte[] payload, boolean deleted) throws Exception {
        File path = slotFile(key, slot);
        path.getParentFile().mkdirs();
        byte[] core = deleted ? new byte[0] : CORE.getBytes(StandardCharsets.UTF_8);
        try (DataOutputStream output = new DataOutputStream(new FileOutputStream(path))) {
            output.writeLong(0x5241565354415445L);
            output.writeInt(1);
            output.writeByte(deleted ? 0 : 1);
            output.write(key.getBytes(StandardCharsets.US_ASCII));
            output.writeShort(core.length);
            output.write(core);
            output.writeLong(1700000000000L);
            output.writeInt(payload.length);
            output.write(MessageDigest.getInstance("SHA-256").digest(payload));
            output.write(payload);
        }
    }

    private File files() { return temp.getRoot(); }
    private File slotFile(String key, int slot) {
        return new File(new File(files(), "native-saves"), key + "-slot" + slot + ".state");
    }
}
