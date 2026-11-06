package com.ravenemu.app.data.saves;

import android.util.AtomicFile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

/** Each game's cheats in private storage, keyed like its SRAM, after the
 * beta's GbCheatStore: RavenEmu PC's `.cheats` format (CheatList), written
 * whole and atomically, never half a list; an empty list removes the file.
 * Call from the storage executor. */
public final class CheatRepository {
    static final int MAX_BYTES = 1024 * 1024;

    private CheatRepository() {}

    private static File file(File files, String key) {
        if (key == null || !key.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Identifiant de ROM invalide.");
        return new File(new File(files, "native-cheats"), key + ".cheats");
    }

    public static List<CheatList.Cheat> read(File files, String key) throws IOException {
        File path = file(files, key);
        if (!path.exists() && !new File(path.getPath() + ".bak").exists()) return Collections.emptyList();
        try (InputStream in = new AtomicFile(path).openRead(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int n;
            while ((n = in.read(buffer)) != -1) {
                if (n > MAX_BYTES - out.size()) throw new IOException("Liste de codes de triche trop grande.");
                out.write(buffer, 0, n);
            }
            return CheatList.parse(new String(out.toByteArray(), StandardCharsets.UTF_8));
        }
    }

    public static void write(File files, String key, List<CheatList.Cheat> cheats) throws IOException {
        File path = file(files, key);
        AtomicFile atomic = new AtomicFile(path);
        if (cheats.isEmpty()) {
            atomic.delete();
            return;
        }
        byte[] bytes = CheatList.format(cheats).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IOException("Liste de codes de triche trop grande.");
        File folder = path.getParentFile();
        if (!folder.isDirectory() && !folder.mkdirs())
            throw new IOException("Impossible de créer le dossier des codes de triche.");
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
