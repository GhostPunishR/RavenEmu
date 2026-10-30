package com.ravenemu.app.data.saves;


import android.util.AtomicFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/** The player's own GBA BIOS, dumped from their console: none ships with
 * RavenEmu. Copied into private storage, where RavenCore's GBA core reads it
 * as the Libretro system directory's gba_bios.bin. */
public final class GbaBiosRepository {
    public static final int SIZE = 16 * 1024;

    private GbaBiosRepository() {}

    private static File path(File files) {
        return new File(files, "gba_bios.bin");
    }

    /** Whether a BIOS is installed, without reading it (for the settings page). */
    public static boolean installed(File files) {
        File path = path(files);
        return path.length() == SIZE || new File(path.getPath() + ".bak").exists();
    }

    /** The installed BIOS, or null when there is none or it is not 16 KiB.
     * AtomicFile first puts back a replacement that was interrupted. */
    public static byte[] read(File files) throws IOException {
        File path = path(files);
        if (!path.exists() && !new File(path.getPath() + ".bak").exists()) return null;
        try (InputStream input = new AtomicFile(path).openRead()) {
            byte[] bios = readAtMost(input, SIZE + 1);
            return bios.length == SIZE ? bios : null;
        }
    }

    /** Copies the chosen file; it must be a GBA BIOS, 16 KiB exactly. */
    public static void install(File files, InputStream input) throws IOException {
        if (input == null) throw new IOException("Fichier illisible.");
        byte[] bios = readAtMost(input, SIZE + 1);
        if (bios.length != SIZE)
            throw new IOException("Ce fichier n'est pas un BIOS GBA : il doit faire 16 Kio (16 384 octets).");
        if (!files.isDirectory() && !files.mkdirs())
            throw new IOException("Impossible de créer le dossier du BIOS.");
        AtomicFile atomic = new AtomicFile(path(files));
        FileOutputStream out = atomic.startWrite();
        try {
            out.write(bios);
            atomic.finishWrite(out);
        } catch (IOException | RuntimeException failure) {
            atomic.failWrite(out);
            throw failure;
        }
    }

    public static void remove(File files) {
        // AtomicFile also removes an interrupted write and its backup.
        new AtomicFile(path(files)).delete();
    }

    /** The first 8 bytes of the BIOS's SHA-256, in hexadecimal. */
    public static String digest(byte[] bios) {
        try {
            byte[] checksum = MessageDigest.getInstance("SHA-256").digest(bios);
            char[] digits = "0123456789abcdef".toCharArray();
            StringBuilder hex = new StringBuilder(16);
            for (int i = 0; i < 8; i++) hex.append(digits[(checksum[i] >>> 4) & 15]).append(digits[checksum[i] & 15]);
            return hex.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    private static byte[] readAtMost(InputStream input, int limit) throws IOException {
        byte[] buffer = new byte[limit];
        int total = 0;
        while (total < limit) {
            int read = input.read(buffer, total, limit - total);
            if (read < 0) break;
            total += read;
        }
        return Arrays.copyOf(buffer, total);
    }
}
