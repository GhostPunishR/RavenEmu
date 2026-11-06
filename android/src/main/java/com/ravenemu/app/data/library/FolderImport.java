package com.ravenemu.app.data.library;

import com.ravenemu.app.data.saves.SaveRepository;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** One import of a chosen folder, after the beta's GbFolderImporter
 * (RavenEmu 0.8.1): its subfolders are read breadth first, within the beta's
 * limits, and each GB, GBC or GBA ROM is copied into the private library as
 * the + button copies one. Existing games, their saves and any game being
 * played are never touched. The folder is reached through `Tree`, so that the
 * Android documents provider stays out of this class. Run off the UI thread. */
public final class FolderImport {
    public static final int MAX_ENTRIES = 4000, MAX_DIRECTORIES = 128, MAX_DEPTH = 8, MAX_ROM_CANDIDATES = 256;
    public static final long MAX_TOTAL_ROM_BYTES = 256L * 1024 * 1024;

    /** A chosen folder: its documents by id. */
    public interface Tree {
        String root();
        /** The documents of a folder; IOException when it cannot be read. */
        List<Child> children(String folderId) throws IOException;
        InputStream open(String documentId) throws IOException;
    }

    public static final class Child {
        final String id, name;
        final boolean folder;
        /** -1 when the provider does not say. */
        final long size;

        public Child(String id, String name, boolean folder, long size) {
            this.id = id; this.name = name; this.folder = folder; this.size = size;
        }
    }

    /** The beta's GbFolderImportSummary. */
    public static final class Result {
        public final int added, duplicate, invalid, unreadable, otherFiles, folders;
        /** Stopped by a safety or storage limit; the games already added stay. */
        public final boolean limited;

        Result(int added, int duplicate, int invalid, int unreadable, int otherFiles, int folders, boolean limited) {
            this.added = added; this.duplicate = duplicate; this.invalid = invalid; this.unreadable = unreadable;
            this.otherFiles = otherFiles; this.folders = folders; this.limited = limited;
        }
    }

    public interface Progress {
        /** ROMs examined so far. */
        void examined(int roms);
    }

    private final File files;
    private final Tree tree;
    private final Progress progress;
    private final Set<String> existing = new HashSet<>(), seenFolders = new HashSet<>();
    private final ArrayDeque<String[]> pending = new ArrayDeque<>();
    private int added, duplicate, invalid, unreadable, otherFiles, folders, entries, candidates;
    private long bytes;
    private boolean limited;

    private FolderImport(File files, Tree tree, Progress progress) {
        this.files = files; this.tree = tree; this.progress = progress;
    }

    /** IOException only when the chosen folder itself cannot be read. */
    public static Result run(File files, Tree tree, Progress progress) throws IOException {
        return new FolderImport(files, tree, progress).run();
    }

    private Result run() throws IOException {
        for (RomLibrary.Entry entry : RomLibrary.list(files)) existing.add(entry.key);
        String root = tree.root();
        seenFolders.add(root);
        pending.add(new String[]{root, "0"});
        boolean first = true;
        while (!pending.isEmpty()) {
            String[] folder = pending.removeFirst();
            folders++;
            List<Child> children;
            try {
                children = tree.children(folder[0]);
            } catch (IOException | RuntimeException failure) {
                // The chosen folder must open; an unreadable subfolder is counted.
                if (first) throw failure instanceof IOException ? (IOException) failure
                        : new IOException("Le dossier n'est pas accessible.", failure);
                unreadable++;
                continue;
            } finally {
                first = false;
            }
            if (!read(children, Integer.parseInt(folder[1]))) break;
        }
        return new Result(added, duplicate, invalid, unreadable, otherFiles, folders, limited);
    }

    /** Queues the subfolders and imports the ROMs; false once a limit stops the import. */
    private boolean read(List<Child> children, int depth) {
        for (Child child : children) {
            if (++entries > MAX_ENTRIES) { limited = true; return false; }
            if (child.id == null || child.name == null) continue;
            if (child.folder) {
                if (seenFolders.contains(child.id)) continue;
                if (depth >= MAX_DEPTH || seenFolders.size() >= MAX_DIRECTORIES) { limited = true; continue; }
                seenFolders.add(child.id);
                pending.add(new String[]{child.id, Integer.toString(depth + 1)});
                continue;
            }
            String system = RomLibrary.detectSystem(child.name);
            if (system == null) { otherFiles++; continue; }
            if (!candidate(child, system)) return false;
        }
        return true;
    }

    /** Reads, checks and stores one ROM; false once a limit stops the import. */
    private boolean candidate(Child child, String system) {
        if (++candidates > MAX_ROM_CANDIDATES || existing.size() >= RomLibrary.MAX_GAMES
                || bytes >= MAX_TOTAL_ROM_BYTES) {
            limited = true;
            return false;
        }
        try {
            if (child.size == 0 || child.size > RomLibrary.MAX_ROM_BYTES) { invalid++; return true; }
            byte[] rom;
            try (InputStream in = tree.open(child.id)) {
                rom = readBounded(in);
            } catch (IOException | RuntimeException failure) {
                if (failure instanceof IOException && "Fichier trop volumineux".equals(failure.getMessage())) invalid++;
                else unreadable++;
                return true;
            }
            if (!plausible(system, rom)) { invalid++; return true; }
            bytes += rom.length;
            if (bytes > MAX_TOTAL_ROM_BYTES) { limited = true; return false; }
            String key = SaveRepository.keyFor(system, rom);
            if (existing.contains(key)) { duplicate++; return true; }
            try {
                RomLibrary.importRom(files, child.name, rom);
            } catch (IOException | RuntimeException failure) {
                // A full library or full storage: no further copy would fit.
                unreadable++;
                limited = true;
                return false;
            }
            existing.add(key);
            added++;
            return true;
        } finally {
            progress.examined(candidates);
        }
    }

    /** The headers RavenCore requires before it loads a ROM: a GBA image
     * holds its 0x96 marker, a Game Boy one at least its header. */
    static boolean plausible(String system, byte[] rom) {
        if (rom.length == 0 || rom.length > RomLibrary.MAX_ROM_BYTES) return false;
        if ("gba".equals(system)) return rom.length >= 0xc0 && (rom[0xb2] & 0xff) == 0x96;
        return rom.length >= 0x150;
    }

    private static byte[] readBounded(InputStream input) throws IOException {
        if (input == null) throw new IOException("ROM illisible");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[64 * 1024];
        int n;
        while ((n = input.read(buffer)) != -1) {
            if (n > RomLibrary.MAX_ROM_BYTES - out.size()) throw new IOException("Fichier trop volumineux");
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }
}
