package com.ravenemu.app;

import android.util.AtomicFile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Private app storage, isolated from the currently distributed RavenEmu.
 * Filename is system + SHA-256 of ROM bytes, never user-controlled ROM path.
 * AtomicFile protects previous SRAM/state if power is lost during a write. */
final class SaveRepository {
    private static final long BATTERY_LIMIT = 1024L * 1024L;
    private static final long STATE_LIMIT = 16L * 1024L * 1024L;

    private SaveRepository() {}

    static String keyFor(String system, byte[] rom) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(system.getBytes(StandardCharsets.US_ASCII));
            digest.update((byte)0);
            byte[] checksum = digest.digest(rom);
            char[] hex = "0123456789abcdef".toCharArray();
            StringBuilder result = new StringBuilder(64);
            for (byte b : checksum) {
                result.append(hex[(b >>> 4) & 15]);
                result.append(hex[b & 15]);
            }
            return result.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    private static File storage(File privateFiles, String key, String extension) {
        if (!key.matches("[a-f0-9]{64}") ||
            !("srm".equals(extension) || "state".equals(extension)))
            throw new IllegalArgumentException("Invalid save identity");
        return new File(new File(privateFiles, "native-saves"), key + "." + extension);
    }

    static byte[] read(File files, String key, String extension) throws IOException {
        File path = storage(files, key, extension);
        // AtomicFile may recover the previous .bak after an interrupted write.
        if (!path.exists() && !new File(path.getPath() + ".bak").exists())
            return null;
        long limit = "srm".equals(extension) ? BATTERY_LIMIT : STATE_LIMIT;
        AtomicFile atomic = new AtomicFile(path);
        try (InputStream input = atomic.openRead();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[16 * 1024];
            int count;
            while ((count = input.read(chunk)) != -1) {
                if (output.size() > limit - count)
                    throw new IOException("Sauvegarde trop volumineuse");
                output.write(chunk, 0, count);
            }
            if (output.size() == 0)
                throw new IOException("Fichier de sauvegarde vide");
            return output.toByteArray();
        }
    }

    static void write(File files, String key, String extension, byte[] bytes)
            throws IOException {
        if (bytes == null || bytes.length == 0) return;
        long limit = "srm".equals(extension) ? BATTERY_LIMIT : STATE_LIMIT;
        if (bytes.length > limit) throw new IOException("Sauvegarde trop volumineuse");
        File path = storage(files, key, extension);
        File folder = path.getParentFile();
        if (!folder.isDirectory() && !folder.mkdirs())
            throw new IOException("Impossible de créer le dossier de sauvegardes");
        AtomicFile atomic = new AtomicFile(path);
        FileOutputStream out = atomic.startWrite();
        try {
            out.write(bytes);
            atomic.finishWrite(out);
        } catch (IOException | RuntimeException failure) {
            atomic.failWrite(out);
            throw failure;
        }
    }
}
