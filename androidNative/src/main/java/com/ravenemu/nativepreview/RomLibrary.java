package com.ravenemu.nativepreview;

import android.util.AtomicFile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Offline-only ROM catalogue in this preview app's private files directory.
 * User-imported ROM bytes are copied; no persistent SAF URI access is needed.
 * The catalogue never reads or modifies the existing RavenEmu beta library. */
final class RomLibrary {
    static final int MAX_ROM_BYTES = 64 * 1024 * 1024;
    private static final int MAX_GAMES = 256;
    private static final int MAX_INDEX_BYTES = 256 * 1024;

    static final class Entry {
        final String key, title, system;
        final int size;
        Entry(String key, String title, String system, int size) {
            this.key = key;
            this.title = title;
            this.system = system;
            this.size = size;
        }
        String label() { return system.toUpperCase(Locale.ROOT) + "  ·  " + title; }
    }

    private RomLibrary() {}

    static String detectSystem(String name) {
        if (name == null) return null;
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".gba")) return "gba";
        if (lower.endsWith(".gbc")) return "gbc";
        if (lower.endsWith(".gb") || lower.endsWith(".sgb")) return "gb";
        return null;
    }

    static String safeTitle(String name) {
        if (name == null) return "Jeu";
        String title = name.replace('\\', '/');
        title = title.substring(title.lastIndexOf('/') + 1).trim();
        // A title is display-only; reject controls and restrict length.
        title = title.replaceAll("[\\p{Cntrl}]", "");
        return title.isEmpty() ? "Jeu" :
                title.substring(0, Math.min(title.length(), 128));
    }

    private static File directory(File privateFiles) {
        return new File(privateFiles, "native-library");
    }

    private static File romFile(File files, String key) {
        if (!key.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid ROM key");
        return new File(directory(files), key + ".rom");
    }

    private static File indexFile(File files) {
        return new File(directory(files), "index.json");
    }

    static List<Entry> list(File files) throws IOException {
        File path = indexFile(files);
        if (!path.exists() && !new File(path.getPath() + ".bak").exists())
            return new ArrayList<>();
        AtomicFile index = new AtomicFile(path);
        try (InputStream input = index.openRead();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[4096];
            int n;
            while ((n = input.read(chunk)) != -1) {
                if (output.size() > MAX_INDEX_BYTES - n)
                    throw new IOException("Catalogue trop volumineux");
                output.write(chunk, 0, n);
            }
            JSONArray items = new JSONArray(output.toString("UTF-8"));
            if (items.length() > MAX_GAMES) throw new IOException("Catalogue trop grand");
            List<Entry> entries = new ArrayList<>();
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i);
                String key = item.getString("key");
                String system = item.getString("system");
                String title = item.getString("title");
                int size = item.getInt("size");
                if (!key.matches("[a-f0-9]{64}") ||
                    !("gb".equals(system) || "gbc".equals(system) || "gba".equals(system)) ||
                    size <= 0 || size > MAX_ROM_BYTES ||
                    title.length() > 128 || title.trim().isEmpty())
                    throw new IOException("Entrée de catalogue invalide");
                entries.add(new Entry(key, title, system, size));
            }
            Collections.sort(entries, Comparator.comparing(e -> e.title.toLowerCase(Locale.ROOT)));
            return entries;
        } catch (JSONException error) {
            throw new IOException("Catalogue illisible", error);
        }
    }

    private static void atomicWrite(File path, byte[] bytes) throws IOException {
        File parent = path.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs())
            throw new IOException("Répertoire inaccessible");
        AtomicFile atomic = new AtomicFile(path);
        FileOutputStream stream = atomic.startWrite();
        try {
            stream.write(bytes);
            atomic.finishWrite(stream);
        } catch (IOException | RuntimeException error) {
            atomic.failWrite(stream);
            throw error;
        }
    }

    static Entry importRom(File files, String name, byte[] rom) throws IOException {
        String system = detectSystem(name);
        if (system == null || rom == null || rom.length == 0 || rom.length > MAX_ROM_BYTES)
            throw new IOException("ROM GB/GBC/GBA non valide");
        String key = SaveRepository.keyFor(system, rom);
        List<Entry> games = list(files);
        for (Entry existing : games)
            if (existing.key.equals(key)) return existing;
        if (games.size() >= MAX_GAMES) throw new IOException("Bibliothèque complète");
        File path = romFile(files, key);
        if (!path.exists()) atomicWrite(path, rom);
        Entry added = new Entry(key, safeTitle(name), system, rom.length);
        games.add(added);
        JSONArray json = new JSONArray();
        for (Entry entry : games) {
            JSONObject item = new JSONObject();
            try {
                item.put("key", entry.key);
                item.put("title", entry.title);
                item.put("system", entry.system);
                item.put("size", entry.size);
            } catch (JSONException error) {
                throw new IOException("Indexation impossible", error);
            }
            json.put(item);
        }
        byte[] metadata = json.toString().getBytes(StandardCharsets.UTF_8);
        if (metadata.length > MAX_INDEX_BYTES) throw new IOException("Catalogue trop volumineux");
        atomicWrite(indexFile(files), metadata);
        return added;
    }

    static byte[] readRom(File files, Entry entry) throws IOException {
        File path = romFile(files, entry.key);
        if (!path.isFile() || path.length() != entry.size || entry.size > MAX_ROM_BYTES)
            throw new IOException("ROM absente ou modifiée");
        byte[] bytes = new byte[entry.size];
        try (InputStream input = new java.io.FileInputStream(path)) {
            int offset = 0;
            while (offset < bytes.length) {
                int n = input.read(bytes, offset, bytes.length - offset);
                if (n < 0) throw new IOException("ROM tronquée");
                offset += n;
            }
            if (input.read() != -1) throw new IOException("ROM modifiée");
        }
        if (!SaveRepository.keyFor(entry.system, bytes).equals(entry.key))
            throw new IOException("Empreinte de ROM incorrecte");
        return bytes;
    }
}
