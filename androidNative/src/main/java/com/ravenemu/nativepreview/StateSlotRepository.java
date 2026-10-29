package com.ravenemu.nativepreview;

import android.util.AtomicFile;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/** Four private preview slots. Call from the serialized storage executor.
 * Each atomically replaced file binds its payload to a ROM and core build.
 * A tombstone hides, but never modifies, the preview's old quick-save file.
 * This class never accesses the beta's storage. */
final class StateSlotRepository {
    static final int SLOT_COUNT = 4;
    private static final int MAX_STATE = 16 * 1024 * 1024;
    private static final int MAX_THUMBNAIL = 256 * 1024;
    private static final int MAX_COMPATIBILITY = 1024;
    private static final long MAGIC = 0x5241565354415445L; // RAVSTATE
    private static final int VERSION = 2;
    private static final int DELETED = 0, SAVED = 1;

    static final class Slot {
        final int index;
        final boolean occupied, compatible, legacy;
        final long savedAt;
        /** Null for readable slots; an invalid file remains visible/deletable. */
        final String error;
        /** Optional encoded preview; decoding and dimension checks belong to UI storage work. */
        final byte[] thumbnail;

        Slot(int index, boolean occupied, boolean compatible, boolean legacy,
             long savedAt, String error) {
            this(index, occupied, compatible, legacy, savedAt, error, null);
        }

        Slot(int index, boolean occupied, boolean compatible, boolean legacy,
             long savedAt, String error, byte[] thumbnail) {
            this.index = index;
            this.occupied = occupied;
            this.compatible = compatible;
            this.legacy = legacy;
            this.savedAt = savedAt;
            this.error = error;
            this.thumbnail = thumbnail == null ? null : thumbnail.clone();
        }
    }

    private static final class Record {
        final boolean deleted;
        final String compatibility;
        final long savedAt;
        final byte[] payload, thumbnail;
        Record(boolean deleted, String compatibility, long savedAt, byte[] payload, byte[] thumbnail) {
            this.deleted = deleted;
            this.compatibility = compatibility;
            this.savedAt = savedAt;
            this.payload = payload;
            this.thumbnail = thumbnail;
        }
    }

    private StateSlotRepository() {}

    static Slot[] list(File files, String key, String compatibility) throws IOException {
        validateCompatibility(compatibility);
        Slot[] slots = new Slot[SLOT_COUNT];
        for (int index = 0; index < slots.length; index++) {
            File path = path(files, key, index);
            try {
                if (exists(path)) {
                    Record record = readRecord(path, key, false);
                    slots[index] = new Slot(index, !record.deleted,
                            !record.deleted && compatibility.equals(record.compatibility),
                            false, record.deleted ? 0 : record.savedAt, null, record.thumbnail);
                } else if (index == 0 && exists(legacyPath(files, key))) {
                    File legacy = legacyPath(files, key);
                    readLegacy(legacy, false);
                    slots[index] = new Slot(index, true, false, true, legacy.lastModified(), null);
                } else slots[index] = new Slot(index, false, false, false, 0, null);
            } catch (IOException failure) {
                slots[index] = new Slot(index, true, false, false, path.lastModified(),
                        failure.getMessage() == null ? "État illisible" : failure.getMessage());
            }
        }
        return slots;
    }

    /** Legacy slot zero has no core identity: the caller must obtain explicit
     * user confirmation before reading/loading a Slot marked legacy. */
    static byte[] read(File files, String key, int slot, String compatibility) throws IOException {
        validateCompatibility(compatibility);
        File path = path(files, key, slot);
        if (!exists(path)) {
            File legacy = legacyPath(files, key);
            return slot == 0 && exists(legacy) ? readLegacy(legacy, true) : null;
        }
        Record record = readRecord(path, key, true);
        if (record.deleted) return null;
        if (!compatibility.equals(record.compatibility))
            throw new IOException("État incompatible avec cette version du core");
        return record.payload;
    }

    static void write(File files, String key, int slot, String compatibility, byte[] state)
            throws IOException {
        write(files, key, slot, compatibility, state, null);
    }

    static void write(File files, String key, int slot, String compatibility, byte[] state,
                      byte[] thumbnail) throws IOException {
        byte[] encodedCompatibility = validateCompatibility(compatibility);
        File path = path(files, key, slot);
        if (state == null || state.length == 0 || state.length > MAX_STATE)
            throw new IOException("Taille d'état invalide");
        if (thumbnail != null && thumbnail.length > MAX_THUMBNAIL)
            throw new IOException("Miniature trop volumineuse");
        writeRecord(path, key, encodedCompatibility, state,
                thumbnail == null ? new byte[0] : thumbnail, false);
    }

    /** Explicit deletion writes a durable tombstone; an older quick-save
     * cannot reappear, and SRAM/other slots/legacy source stay unchanged. */
    static void delete(File files, String key, int slot) throws IOException {
        writeRecord(path(files, key, slot), key, new byte[0], new byte[0], new byte[0], true);
    }

    private static File path(File files, String key, int slot) {
        if (files == null || key == null || !key.matches("[a-f0-9]{64}") ||
                slot < 0 || slot >= SLOT_COUNT)
            throw new IllegalArgumentException("Invalid state slot identity");
        return new File(new File(files, "native-saves"), key + "-slot" + slot + ".state");
    }

    private static File legacyPath(File files, String key) {
        return new File(new File(files, "native-saves"), key + ".state");
    }

    private static boolean exists(File path) {
        // AtomicFile restores its historical backup on openRead(). Ignore an
        // uncommitted .new file: it never replaced the last committed value.
        return path.exists() || new File(path.getPath() + ".bak").exists();
    }

    private static byte[] validateCompatibility(String compatibility) {
        if (compatibility == null || compatibility.isEmpty())
            throw new IllegalArgumentException("Missing core compatibility identity");
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(java.nio.CharBuffer.wrap(compatibility));
            if (encoded.remaining() > MAX_COMPATIBILITY)
                throw new IllegalArgumentException("Core compatibility identity too long");
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (CharacterCodingException malformed) {
            throw new IllegalArgumentException("Invalid core compatibility identity", malformed);
        }
    }

    private static Record readRecord(File path, String key, boolean retainPayload) throws IOException {
        try (DataInputStream input = new DataInputStream(new AtomicFile(path).openRead())) {
            if (input.readLong() != MAGIC)
                throw new IOException("Format d'état inconnu");
            int version = input.readInt();
            if (version != 1 && version != VERSION) throw new IOException("Format d'état inconnu");
            int kind = input.readUnsignedByte();
            if (kind != DELETED && kind != SAVED) throw new IOException("Type d'état invalide");
            byte[] identity = new byte[64];
            input.readFully(identity);
            if (!Arrays.equals(identity, key.getBytes(StandardCharsets.US_ASCII)))
                throw new IOException("État associé à un autre jeu");
            int compatibilityLength = input.readUnsignedShort();
            if (compatibilityLength > MAX_COMPATIBILITY ||
                    (kind == SAVED && compatibilityLength == 0) ||
                    (kind == DELETED && compatibilityLength != 0))
                throw new IOException("Identité du core invalide");
            byte[] compatibilityBytes = new byte[compatibilityLength];
            input.readFully(compatibilityBytes);
            String compatibility;
            try {
                compatibility = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(compatibilityBytes)).toString();
            } catch (CharacterCodingException malformed) {
                throw new IOException("Identité du core invalide", malformed);
            }
            long savedAt = input.readLong();
            int size = input.readInt();
            if (savedAt <= 0 || size < 0 || size > MAX_STATE ||
                    (kind == SAVED && size == 0) || (kind == DELETED && size != 0))
                throw new IOException("Taille ou date d'état invalide");
            byte[] expectedHash = new byte[32];
            input.readFully(expectedHash);
            MessageDigest digest = sha256();
            byte[] payload = retainPayload ? new byte[size] : null;
            byte[] chunk = new byte[16 * 1024];
            int offset = 0;
            while (offset < size) {
                int count = Math.min(chunk.length, size - offset);
                input.readFully(chunk, 0, count);
                digest.update(chunk, 0, count);
                if (payload != null) System.arraycopy(chunk, 0, payload, offset, count);
                offset += count;
            }
            if (!MessageDigest.isEqual(expectedHash, digest.digest()))
                throw new IOException("État corrompu");
            byte[] thumbnail = null;
            if (version == 1) {
                if (input.read() != -1) throw new IOException("État corrompu");
            } else thumbnail = readThumbnail(input);
            return new Record(kind == DELETED, compatibility, savedAt, payload,
                    kind == DELETED ? null : thumbnail);
        }
    }

    /** Preview damage cannot invalidate an already verified game payload.
     * Bound the allocation before reading any optional trailer bytes. */
    private static byte[] readThumbnail(DataInputStream input) {
        try {
            int size = input.readInt();
            if (size < 0 || size > MAX_THUMBNAIL) return null;
            byte[] thumbnail = new byte[size];
            input.readFully(thumbnail);
            byte[] checksum = new byte[32];
            input.readFully(checksum);
            if (input.read() != -1 || !MessageDigest.isEqual(checksum, sha256().digest(thumbnail)))
                return null;
            return size == 0 ? null : thumbnail;
        } catch (IOException damagedPreview) {
            return null;
        }
    }

    private static byte[] readLegacy(File path, boolean retainPayload) throws IOException {
        try (FileInputStream input = new AtomicFile(path).openRead()) {
            long length = input.getChannel().size();
            if (length <= 0 || length > MAX_STATE) throw new IOException("Taille d'état invalide");
            byte[] result = retainPayload ? new byte[(int) length] : null;
            byte[] chunk = new byte[16 * 1024];
            int offset = 0, count;
            while ((count = input.read(chunk)) != -1) {
                if (offset > length - count) throw new IOException("État modifié pendant sa lecture");
                if (result != null) System.arraycopy(chunk, 0, result, offset, count);
                offset += count;
            }
            if (offset != length) throw new IOException("État tronqué");
            return result;
        }
    }

    private static void writeRecord(File path, String key, byte[] compatibility,
                                    byte[] payload, byte[] thumbnail, boolean deleted) throws IOException {
        byte[] checksum = sha256().digest(payload);
        byte[] thumbnailChecksum = sha256().digest(thumbnail);
        File folder = path.getParentFile();
        if (!folder.isDirectory() && !folder.mkdirs())
            throw new IOException("Impossible de créer le dossier des états");
        AtomicFile atomic = new AtomicFile(path);
        FileOutputStream stream = atomic.startWrite();
        try {
            DataOutputStream output = new DataOutputStream(stream);
            output.writeLong(MAGIC);
            output.writeInt(VERSION);
            output.writeByte(deleted ? DELETED : SAVED);
            output.write(key.getBytes(StandardCharsets.US_ASCII));
            output.writeShort(compatibility.length);
            output.write(compatibility);
            output.writeLong(System.currentTimeMillis());
            output.writeInt(payload.length);
            output.write(checksum);
            output.write(payload);
            output.writeInt(thumbnail.length);
            output.write(thumbnail);
            output.write(thumbnailChecksum);
            output.flush();
            atomic.finishWrite(stream);
        } catch (IOException | RuntimeException failure) {
            atomic.failWrite(stream);
            throw failure;
        }
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
