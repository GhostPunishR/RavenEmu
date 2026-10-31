package com.ravenemu.app.data.saves;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.json.JSONException;
import org.json.JSONObject;

/** A game's cartridge save in and out of the app, after the beta's
 * GbSaveTransfer (RavenEmu 0.8.1): a raw .sav or .srm, or the battery of a
 * RavenEmu .zip archive bound to the exact SHA-256 of the ROM, a single
 * game's package or a library archive with a folder per game. Everything is
 * checked in memory before anything is written; never a ROM. The archive's
 * save states are checked but not imported: they are the beta's own states,
 * without the identity of the core that wrote them (StateSlotRepository).
 * Free of Android types. */
public final class SaveTransfer {
    public static final String SAV = ".sav", SRM = ".srm";
    static final String FORMAT = "RavenEmu-GB-Saves";
    static final int VERSION = 1;
    static final int MAX_BATTERY = SaveRepository.BATTERY_LIMIT_BYTES;
    private static final int MAX_STATE = 4 * 1024 * 1024;
    private static final int MAX_PREVIEW = 512 * 1024;
    private static final int MAX_MANIFEST = 16 * 1024;
    private static final int MAX_UNPACKED = 21 * 1024 * 1024;
    /** Every game's saves read through to find one game's folder. */
    private static final long MAX_LIBRARY = 2L * 1024 * 1024 * 1024;
    private static final int MAX_FOLDER_CHARS = 120;

    private SaveTransfer() {}

    /** What an import would write, for the review the player confirms. */
    public static final class Payload {
        public final byte[] battery;
        /** A bare .sav or .srm: nothing in it names its ROM. */
        public final boolean raw;
        /** Save-state slots of the archive, 1 to 4, left out. */
        public final int[] skippedSlots;

        Payload(byte[] battery, boolean raw, int[] skippedSlots) {
            this.battery = battery;
            this.raw = raw;
            this.skippedSlots = skippedSlots;
        }
    }

    /** A library game with a save, as the library archive names it. */
    public static final class Game {
        final String title, romSha256;
        final byte[] battery;

        public Game(String title, String romSha256, byte[] battery) {
            this.title = title;
            this.romSha256 = romSha256;
            this.battery = battery;
        }
    }

    /** Every game's save in one archive, as the beta's exportLibrary writes
     * it, so that both the beta and this app import it: a folder per game,
     * « name (start of the ROM's SHA-256) », holding its manifest first, then
     * its battery.sav. Only the cartridge saves, by the owner's choice: this
     * app's save states are not the beta's. Returns the games written. */
    public static int exportLibrary(List<Game> games, OutputStream output) throws IOException {
        if (games.isEmpty()) throw new IOException("Aucun jeu de la bibliothèque n'a de sauvegarde à exporter.");
        Set<String> folders = new HashSet<>();
        ZipOutputStream zip = new ZipOutputStream(output);
        for (Game game : games) {
            if (game.romSha256 == null || !game.romSha256.matches("[0-9a-f]{64}") || game.battery == null
                    || game.battery.length == 0 || game.battery.length > MAX_BATTERY)
                throw new IOException("Sauvegarde invalide : " + game.title + ".");
            String base = libraryFolder(game.title, game.romSha256), folder = base;
            for (int suffix = 2; !folders.add(folder); suffix++) folder = base + " " + suffix;
            JSONObject manifest;
            try {
                manifest = new JSONObject().put("format", FORMAT).put("version", VERSION)
                        .put("romSha256", game.romSha256)
                        .put("entries", new JSONObject().put("battery.sav", sha256(game.battery)));
            } catch (JSONException impossible) {
                throw new IOException("Manifeste impossible à écrire.", impossible);
            }
            put(zip, folder + "/manifest.json", manifest.toString().getBytes(StandardCharsets.UTF_8));
            put(zip, folder + "/battery.sav", game.battery);
        }
        zip.finish();
        return games.size();
    }

    /** Readable and unique: the ROM's name, then the start of its SHA-256. */
    static String libraryFolder(String title, String romSha256) {
        String stem = title == null ? "" : title.replace('\\', '/');
        stem = stem.substring(stem.lastIndexOf('/') + 1);
        int dot = stem.lastIndexOf('.');
        if (dot >= 0) stem = stem.substring(0, dot);
        StringBuilder safe = new StringBuilder();
        for (int i = 0; i < stem.length(); i++) {
            char c = stem.charAt(i);
            safe.append(Character.isISOControl(c) || "/\\:*?\"<>|".indexOf(c) >= 0 ? '_' : c);
        }
        String name = safe.toString().trim();
        while (name.endsWith(".")) name = name.substring(0, name.length() - 1);
        if (name.length() > 80) name = name.substring(0, 80);
        return (name.trim().isEmpty() ? "Jeu" : name) + " (" + romSha256.substring(0, 8) + ")";
    }

    private static void put(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }

    /** True for a name the import reads as a .zip archive, whose ROM must
     * then be hashed. */
    public static boolean isArchive(String filename) {
        return filename != null && filename.toLowerCase(Locale.ROOT).endsWith(".zip");
    }

    /** Reads `input` as `filename` for the game whose ROM bytes hash to
     * `romSha256` (needed for an archive only); IOException with the reason,
     * in the beta's words, when the file cannot be imported. */
    public static Payload read(InputStream input, String filename, String romSha256) throws IOException {
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        boolean archive = isArchive(name), raw = name.endsWith(SAV) || name.endsWith(SRM);
        if (!archive && !raw)
            throw new IOException("Sélectionnez une sauvegarde .sav ou .srm, ou une archive .zip RavenEmu.");
        BufferedInputStream buffered = new BufferedInputStream(input);
        buffered.mark(4);
        byte[] head = new byte[4];
        int count = 0;
        while (count < head.length) {
            int n = buffered.read(head, count, head.length - count);
            if (n < 0) break;
            count += n;
        }
        buffered.reset();
        if (count == 0) throw new IOException("Le fichier est vide.");
        boolean magic = count == 4 && head[0] == 'P' && head[1] == 'K' && head[2] == 3 && head[3] == 4;
        if (magic != archive) throw new IOException("Le contenu du fichier ne correspond pas à son extension.");
        if (!archive) return new Payload(readLimited(buffered, MAX_BATTERY), true, new int[0]);
        if (romSha256 == null || !romSha256.matches("[0-9a-f]{64}"))
            throw new IOException("Identifiant de ROM invalide.");
        return fromArchive(entries(buffered, romSha256), romSha256);
    }

    private static Payload fromArchive(Map<String, byte[]> entries, String romSha256) throws IOException {
        byte[] manifestBytes = entries.remove("manifest.json");
        if (manifestBytes == null)
            throw new IOException("Cette archive n'est pas une sauvegarde de cette version de RavenEmu.");
        JSONObject declared;
        try {
            JSONObject manifest = new JSONObject(new String(manifestBytes, StandardCharsets.UTF_8));
            if (!supported(manifest.getString("format")) || manifest.getInt("version") != VERSION)
                throw new IOException("Format de sauvegarde non pris en charge.");
            if (!romSha256.equals(manifest.getString("romSha256")))
                throw new IOException("Cette sauvegarde appartient à une autre ROM : import refusé.");
            declared = manifest.getJSONObject("entries");
        } catch (JSONException malformed) {
            throw new IOException("Format de sauvegarde non pris en charge.");
        }
        if (entries.isEmpty() || declared.length() != entries.size())
            throw new IOException("Contenu de sauvegarde incomplet.");
        List<Integer> slots = new ArrayList<>();
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            String name = entry.getKey();
            if (!sha256(entry.getValue()).equals(declared.optString(name, null)))
                throw new IOException("Contrôle d'intégrité échoué pour " + name + ".");
            if (name.endsWith(".png") && !entries.containsKey(name.replace(".png", ".state")))
                throw new IOException("Une miniature ne peut pas exister sans save state.");
            if (name.endsWith(".state")) slots.add(name.charAt(4) - '0' + 1);
        }
        byte[] battery = entries.get("battery.sav");
        if (battery == null)
            throw new IOException("Cette archive ne contient que des états de la bêta, sans sauvegarde du jeu.");
        int[] skipped = new int[slots.size()];
        for (int i = 0; i < skipped.length; i++) skipped[i] = slots.get(i);
        Arrays.sort(skipped);
        return new Payload(battery, false, skipped);
    }

    /** The target game's entries, manifest included: a library archive holds
     * a folder per game, each starting with its manifest, and only the
     * target's folder is kept; a single game's package has its files at the
     * root. */
    private static Map<String, byte[]> entries(InputStream input, String romSha256) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        Boolean library = null;
        String selected = null;
        long kept = 0, scanned = 0;
        try (ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) throw new IOException("Dossier inattendu dans l'archive.");
                String path = entry.getName();
                int slash = path.lastIndexOf('/');
                String folder = slash < 0 ? "" : path.substring(0, slash), name = path.substring(slash + 1);
                Integer limit = limit(name);
                if (limit == null) throw new IOException("Fichier inattendu ou doublon dans l'archive.");
                boolean inFolder = !folder.isEmpty();
                if (library != null && library != inFolder) throw new IOException("Archive de sauvegardes incohérente.");
                library = inFolder;
                if (inFolder && !libraryFolder(folder)) throw new IOException("Dossier inattendu dans l'archive.");
                byte[] bytes = readLimited(zip, limit);
                if (inFolder && !folder.equals(selected)) {
                    // Another game, or the target's folder before its manifest.
                    scanned += bytes.length;
                    if (scanned > MAX_LIBRARY) throw new IOException("Archive trop volumineuse une fois décompressée.");
                    if (selected != null || !name.equals("manifest.json")
                            || !romSha256.equals(manifestKey(bytes))) continue;
                    selected = folder;
                }
                if (entries.containsKey(name)) throw new IOException("Fichier inattendu ou doublon dans l'archive.");
                if (bytes.length == 0) throw new IOException("Fichier vide dans l'archive : " + name + ".");
                kept += bytes.length;
                if (kept > MAX_UNPACKED) throw new IOException("Archive trop volumineuse une fois décompressée.");
                entries.put(name, bytes);
            }
        } catch (ZipException malformed) {
            throw new IOException("Archive illisible.");
        }
        if (Boolean.TRUE.equals(library) && selected == null)
            throw new IOException("Cette archive ne contient aucune sauvegarde de ce jeu.");
        return entries;
    }

    /** The bytes a game's SRAM gets from a saved file, as RavenCore's own
     * import does and as the beta loaded such files: the first bytes of the
     * file over the core's fresh SRAM, the rest of the core's SRAM kept. A
     * .sav from another emulator may carry a different clock trailer or none.
     * Null when the game has no SRAM; `saved` itself when the sizes match. */
    public static byte[] fit(byte[] saved, byte[] fresh) {
        if (saved == null || fresh == null || fresh.length == 0) return null;
        if (saved.length == fresh.length) return saved;
        byte[] fitted = fresh.clone();
        System.arraycopy(saved, 0, fitted, 0, Math.min(saved.length, fitted.length));
        return fitted;
    }

    /** The ROM's name with the save's extension, so that other emulators
     * pair them: as the beta's batteryExportName. */
    public static String exportName(String title, String extension) {
        String stem = title == null ? "" : title.replace('\\', '/');
        stem = stem.substring(stem.lastIndexOf('/') + 1);
        int dot = stem.lastIndexOf('.');
        if (dot > 0) stem = stem.substring(0, dot);
        stem = stem.replaceAll("\\p{Cntrl}", "").trim();
        return (stem.isEmpty() ? "GameBoy" : stem) + (SRM.equals(extension) ? SRM : SAV);
    }

    public static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) hex.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            return hex.toString();
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException("SHA-256 unavailable", missing);
        }
    }

    /** The exact historical generation is still read, as in the beta. */
    private static boolean supported(String format) {
        return FORMAT.equals(format) || "RavenEmu-V3-GB-Saves".equals(format);
    }

    private static String manifestKey(byte[] bytes) {
        try {
            return new JSONObject(new String(bytes, StandardCharsets.UTF_8)).getString("romSha256");
        } catch (JSONException malformed) {
            return null;
        }
    }

    private static boolean libraryFolder(String folder) {
        if (folder.length() > MAX_FOLDER_CHARS || folder.equals(".") || folder.equals("..")) return false;
        for (int i = 0; i < folder.length(); i++) {
            char c = folder.charAt(i);
            if (c == '/' || c == '\\' || Character.isISOControl(c)) return false;
        }
        return true;
    }

    /** The size allowed for an archive entry; null for a name not allowed. */
    private static Integer limit(String name) {
        if (name.equals("manifest.json")) return MAX_MANIFEST;
        if (name.equals("battery.sav")) return MAX_BATTERY;
        if (name.matches("slot[0-3]\\.state")) return MAX_STATE;
        if (name.matches("slot[0-3]\\.png")) return MAX_PREVIEW;
        return null;
    }

    private static byte[] readLimited(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = input.read(buffer)) != -1) {
            if (n > limit - out.size()) throw new IOException("Le fichier de sauvegarde est trop volumineux.");
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }
}
