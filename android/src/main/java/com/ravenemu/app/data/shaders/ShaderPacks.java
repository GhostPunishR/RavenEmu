package com.ravenemu.app.data.shaders;

import com.ravenemu.app.data.library.FolderImport;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** The RetroArch GLSL shader packs the player imported, in files/shaders/:
 * one folder per pack, as its .zip or its folder held it, with only what a
 * preset reads (.glslp, .glsl, images) and the licence files. No shader is
 * bundled with RavenEmu. A preset is named by its path from this root
 * ("glsl-shaders/crt/crt-geom.glslp"), the folder the native driver reads
 * from. Imports are written aside, then moved in whole. Off the UI thread. */
public final class ShaderPacks {
    public static final String FOLDER = "shaders";
    public static final int MAX_FILES = 8000;
    public static final long MAX_FILE_BYTES = 16L * 1024 * 1024, MAX_TOTAL_BYTES = 192L * 1024 * 1024;
    static final int MAX_DEPTH = 12;
    private static final Pattern EXTENSIONS = Pattern.compile(
            "(?i).*\\.(glslp|glsl|png|jpg|jpeg|bmp|webp|gif|txt|md)$");
    private static final Pattern LICENCE = Pattern.compile("(?i)(licen[cs]e|copying|readme|authors)(\\..*)?");
    private static final Pattern SAFE_SEGMENT = Pattern.compile("[^/\\\\\\x00-\\x1f]{1,120}");

    /** A pack and its presets, sorted. */
    public static final class Pack {
        public final String id;
        public final List<String> presets;

        Pack(String id, List<String> presets) {
            this.id = id;
            this.presets = Collections.unmodifiableList(presets);
        }
    }

    private final File root;

    public ShaderPacks(File files) { this.root = new File(files, FOLDER); }

    /** The folder presets are read from: give it to the native driver. */
    public File root() { return root; }

    public List<Pack> list() {
        List<Pack> packs = new ArrayList<>();
        File[] folders = root.listFiles(file -> file.isDirectory() && !file.getName().startsWith("."));
        if (folders == null) return packs;
        for (File folder : folders) {
            List<String> presets = new ArrayList<>();
            collect(folder, folder.getName(), presets, 0);
            Collections.sort(presets, String.CASE_INSENSITIVE_ORDER);
            packs.add(new Pack(folder.getName(), presets));
        }
        Collections.sort(packs, (a, b) -> a.id.compareToIgnoreCase(b.id));
        return packs;
    }

    /** Whether a preset id still names an imported file. */
    public boolean exists(String preset) {
        return preset != null && valid(preset) && preset.toLowerCase(Locale.ROOT).endsWith(".glslp")
                && new File(root, preset).isFile();
    }

    /** The .zip of a pack, `name` its file name; the pack's id is returned. */
    public String importZip(String name, InputStream input) throws IOException {
        File staging = staging();
        try (ZipInputStream zip = new ZipInputStream(input)) {
            Counter counter = new Counter();
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                if (entry.isDirectory()) continue;
                String path = normalize(entry.getName());
                if (path == null) throw new IOException("Chemin refusé dans l'archive : " + entry.getName());
                if (!wanted(path)) continue;
                write(staging, path, zip, counter);
            }
            return install(staging, name);
        } catch (IOException | RuntimeException failure) {
            delete(staging);
            throw failure instanceof IOException ? (IOException) failure
                    : new IOException("Archive illisible", failure);
        }
    }

    /** A folder chosen in the documents picker, `name` its display name. */
    public String importFolder(String name, FolderImport.Tree tree) throws IOException {
        File staging = staging();
        try {
            Counter counter = new Counter();
            ArrayDeque<String[]> folders = new ArrayDeque<>();
            folders.add(new String[]{tree.root(), ""});
            while (!folders.isEmpty()) {
                String[] folder = folders.poll();
                int depth = folder[1].isEmpty() ? 0 : folder[1].split("/").length;
                for (FolderImport.Child child : tree.children(folder[0])) {
                    if (!SAFE_SEGMENT.matcher(child.name).matches() || child.name.equals(".")
                            || child.name.equals("..")) continue;
                    String path = folder[1].isEmpty() ? child.name : folder[1] + "/" + child.name;
                    if (child.folder) {
                        if (depth + 1 < MAX_DEPTH && !child.name.startsWith(".")) folders.add(new String[]{child.id, path});
                    } else if (wanted(path)) {
                        try (InputStream input = tree.open(child.id)) { write(staging, path, input, counter); }
                    }
                }
            }
            return install(staging, name);
        } catch (IOException | RuntimeException failure) {
            delete(staging);
            throw failure instanceof IOException ? (IOException) failure
                    : new IOException("Dossier illisible", failure);
        }
    }

    public void deletePack(String id) {
        if (id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) return;
        delete(new File(root, id));
    }

    /** "a/b/../c.glsl" as "a/c.glsl"; null for a path that leaves the pack. */
    static String normalize(String name) {
        if (name == null || name.isEmpty() || name.indexOf('\0') >= 0) return null;
        String path = name.replace('\\', '/');
        if (path.startsWith("/") || path.contains(":")) return null;
        ArrayDeque<String> segments = new ArrayDeque<>();
        for (String segment : path.split("/")) {
            if (segment.isEmpty() || segment.equals(".")) continue;
            if (segment.equals("..")) {
                if (segments.isEmpty()) return null;
                segments.removeLast();
            } else {
                if (!SAFE_SEGMENT.matcher(segment).matches()) return null;
                segments.addLast(segment);
            }
        }
        if (segments.isEmpty() || segments.size() > MAX_DEPTH) return null;
        return String.join("/", segments);
    }

    static boolean wanted(String path) {
        String file = path.substring(path.lastIndexOf('/') + 1);
        if (file.startsWith(".")) return false;
        return EXTENSIONS.matcher(file).matches() || LICENCE.matcher(file).matches();
    }

    /** A pack's id from its file or folder name: letters, digits, dots and dashes. */
    static String idFor(String name) {
        String base = name == null ? "" : name.trim();
        if (base.toLowerCase(Locale.ROOT).endsWith(".zip")) base = base.substring(0, base.length() - 4);
        base = base.replaceAll("[^A-Za-z0-9._-]+", "-").replaceAll("[-]+", "-")
                .replaceAll("^[._-]+", "").replaceAll("[._-]+$", "");
        if (base.length() > 48) base = base.substring(0, 48);
        return base.isEmpty() ? "shaders" : base;
    }

    private static final class Counter {
        int files;
        long bytes;
    }

    private File staging() throws IOException {
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("Impossible de créer le dossier des shaders");
        File staging = new File(root, ".import-" + System.nanoTime());
        if (!staging.mkdirs()) throw new IOException("Impossible de préparer l'importation");
        return staging;
    }

    private static void write(File staging, String path, InputStream input, Counter counter) throws IOException {
        if (++counter.files > MAX_FILES) throw new IOException("Trop de fichiers (" + MAX_FILES + " au plus)");
        File target = new File(staging, path);
        if (!target.getCanonicalPath().startsWith(staging.getCanonicalPath() + File.separator))
            throw new IOException("Chemin refusé : " + path);
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs())
            throw new IOException("Impossible de créer " + path);
        byte[] chunk = new byte[64 * 1024];
        long written = 0;
        try (OutputStream output = new FileOutputStream(target)) {
            for (int n; (n = input.read(chunk)) != -1; ) {
                written += n;
                counter.bytes += n;
                if (written > MAX_FILE_BYTES) throw new IOException("Fichier trop volumineux : " + path);
                if (counter.bytes > MAX_TOTAL_BYTES) throw new IOException("Pack trop volumineux (192 Mio au plus)");
                output.write(chunk, 0, n);
            }
        }
    }

    /** The staged pack, under a single top folder unwrapped, takes its id. */
    private String install(File staging, String name) throws IOException {
        File content = staging;
        File[] top = staging.listFiles();
        if (top != null && top.length == 1 && top[0].isDirectory()) content = top[0];
        List<String> presets = new ArrayList<>();
        collect(content, "", presets, 0);
        if (presets.isEmpty()) throw new IOException("Aucun preset .glslp dans ce pack");
        String base = idFor(name), id = base;
        for (int n = 2; new File(root, id).exists(); n++) id = base + "-" + n;
        if (!content.renameTo(new File(root, id))) throw new IOException("Impossible d'installer le pack");
        delete(staging);
        return id;
    }

    private static void collect(File folder, String prefix, List<String> presets, int depth) {
        File[] children = folder.listFiles();
        if (children == null || depth > MAX_DEPTH) return;
        for (File child : children) {
            String path = prefix.isEmpty() ? child.getName() : prefix + "/" + child.getName();
            if (child.isDirectory()) collect(child, path, presets, depth + 1);
            else if (child.getName().toLowerCase(Locale.ROOT).endsWith(".glslp")) presets.add(path);
        }
    }

    private static boolean valid(String preset) {
        return normalize(preset) != null && normalize(preset).equals(preset);
    }

    private static void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
