package com.ravenemu.app.data.skins;

import com.ravenemu.app.data.skins.DeltaSkin.Console;
import com.ravenemu.app.data.skins.DeltaSkin.Kind;
import com.ravenemu.app.data.skins.DeltaSkin.Manifest;
import com.ravenemu.app.data.skins.DeltaSkinException.Code;

import java.io.File;
import java.io.IOException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** The beta's DeltaSkinRepository: installed skins in private storage, one
 * folder each, named after the SHA-256 of the skin's identifier (never its
 * name or identifier). Installing moves a whole checked folder in place, and a
 * replacement keeps the old one until the new one reads back. A folder that
 * does not read back whole (metadata, manifest, archive, PDFs) is not listed.
 * Disk I/O: off the UI thread. */
public final class DeltaSkinRepository {
    private static final long MAX_METADATA_BYTES = 2L * 1024 * 1024;
    private static final String REPLACED_DIRECTORY = ".replaced";
    static final int SCHEMA_VERSION = 1;

    /** What the app keeps beside the skin's own files. */
    public static final class Metadata {
        public final String storageKey, identifier, archiveSha256;
        public final Console console;
        public final long importedAt, archiveSize;
        /** The fixed names of the portrait PDFs, null when the skin has none of that kind. */
        public final String standardPortrait, edgePortrait;
        public final List<String> ignoredInputs;

        Metadata(String storageKey, String identifier, Console console, String archiveSha256, long importedAt,
                 long archiveSize, String standardPortrait, String edgePortrait, List<String> ignoredInputs) {
            this.storageKey = storageKey;
            this.identifier = identifier;
            this.console = console;
            this.archiveSha256 = archiveSha256;
            this.importedAt = importedAt;
            this.archiveSize = archiveSize;
            this.standardPortrait = standardPortrait;
            this.edgePortrait = edgePortrait;
            this.ignoredInputs = Collections.unmodifiableList(new ArrayList<>(ignoredInputs));
        }

        public String asset(Kind kind) { return kind == Kind.STANDARD ? standardPortrait : edgePortrait; }

        String toJson() {
            try {
                JSONObject json = new JSONObject();
                json.put("schemaVersion", SCHEMA_VERSION);
                json.put("storageKey", storageKey);
                json.put("identifier", identifier);
                json.put("console", console.name());
                json.put("archiveSha256", archiveSha256);
                json.put("importedAtEpochMillis", importedAt);
                json.put("archiveSizeBytes", archiveSize);
                if (standardPortrait != null) json.put("standardPortraitAsset", standardPortrait);
                if (edgePortrait != null) json.put("edgeToEdgePortraitAsset", edgePortrait);
                json.put("ignoredInputs", new JSONArray(ignoredInputs));
                return json.toString(2);
            } catch (JSONException impossible) {
                throw new IllegalStateException(impossible);
            }
        }

        static Metadata fromJson(String text) throws JSONException {
            JSONObject json = new JSONObject(text);
            if (json.getInt("schemaVersion") != SCHEMA_VERSION) throw new JSONException("schéma inconnu");
            JSONArray ignored = json.getJSONArray("ignoredInputs");
            List<String> inputs = new ArrayList<>();
            for (int i = 0; i < ignored.length(); i++) inputs.add(ignored.getString(i));
            return new Metadata(json.getString("storageKey"), json.getString("identifier"),
                    Console.valueOf(json.getString("console")), json.getString("archiveSha256"),
                    json.getLong("importedAtEpochMillis"), json.getLong("archiveSizeBytes"),
                    json.optString("standardPortraitAsset", null), json.optString("edgeToEdgePortraitAsset", null),
                    inputs);
        }
    }

    public static final class Installed {
        public final File directory;
        public final Metadata metadata;
        public final Manifest manifest;
        public final long occupiedBytes;

        Installed(File directory, Metadata metadata, Manifest manifest, long occupiedBytes) {
            this.directory = directory;
            this.metadata = metadata;
            this.manifest = manifest;
            this.occupiedBytes = occupiedBytes;
        }

        /** The portrait PDF of this kind, or null when the skin has none. */
        public File pdf(Kind kind) {
            String name = metadata.asset(kind);
            return name == null ? null : new File(new File(directory, DeltaSkinArchive.ASSETS_DIRECTORY), name);
        }
    }

    public enum Outcome { INSTALLED, REPLACED, DUPLICATE, CONFLICT }

    public static final class Result {
        public final Outcome outcome;
        /** The skin now installed, or already there; null for a conflict with an unreadable folder. */
        public final Installed skin;
        /** For a replacement: the archive it replaced, whose rendered pages are now stale. */
        public final String previousSha256;

        Result(Outcome outcome, Installed skin, String previousSha256) {
            this.outcome = outcome;
            this.skin = skin;
            this.previousSha256 = previousSha256;
        }
    }

    private final File root;

    public DeltaSkinRepository(File root) { this.root = root; }

    public File root() { return root; }

    /** GB/GBC skins first, then GBA, each by name. */
    public List<Installed> list() {
        List<Installed> skins = new ArrayList<>();
        File[] children = root.listFiles();
        if (children == null) return skins;
        for (File child : children) {
            if (!child.isDirectory() || child.getName().startsWith(".")) continue;
            Installed installed = read(child);
            if (installed != null) skins.add(installed);
        }
        skins.sort((left, right) -> {
            int console = left.metadata.console.compareTo(right.metadata.console);
            if (console != 0) return console;
            int name = String.CASE_INSENSITIVE_ORDER.compare(left.manifest.name, right.manifest.name);
            return name != 0 ? name : left.metadata.identifier.compareTo(right.metadata.identifier);
        });
        return skins;
    }

    public Installed find(String identifier) {
        if (identifier == null) return null;
        Installed installed = read(new File(root, DeltaSkinArchive.storageKey(identifier)));
        return installed != null && installed.metadata.identifier.equals(identifier) ? installed : null;
    }

    public Installed findByHash(String sha256) {
        for (Installed skin : list()) if (skin.metadata.archiveSha256.equals(sha256)) return skin;
        return null;
    }

    /** The same archive is a duplicate (the staging folder is discarded); the
     * same identifier from another archive is a conflict unless replacing. */
    public Result install(DeltaSkinArchive.Prepared prepared, boolean replace) throws DeltaSkinException {
        requireStaged(prepared);
        if (!root.isDirectory() && !root.mkdirs())
            throw new DeltaSkinException(Code.STORAGE_ERROR, "Impossible de préparer le stockage des skins");
        Installed duplicate = findByHash(prepared.metadata.archiveSha256);
        if (duplicate != null) {
            discard(prepared);
            return new Result(Outcome.DUPLICATE, duplicate, null);
        }
        File target = new File(root, prepared.metadata.storageKey);
        Installed existing = read(target);
        if (!replace && (existing != null || target.exists())) return new Result(Outcome.CONFLICT, existing, null);
        if (existing == null && !target.exists()) {
            move(prepared.staging, target);
            Installed installed = read(target);
            if (installed == null) {
                DeltaSkinArchive.deleteTree(target);
                throw new DeltaSkinException(Code.STORAGE_ERROR, "Le skin installé ne peut pas être relu");
            }
            return new Result(Outcome.INSTALLED, installed, null);
        }
        File replaced = new File(root, REPLACED_DIRECTORY);
        if (!replaced.isDirectory() && !replaced.mkdirs())
            throw new DeltaSkinException(Code.STORAGE_ERROR, "Le remplacement du skin a échoué");
        File backup = new File(replaced, target.getName() + "-" + UUID.randomUUID());
        move(target, backup);
        try {
            move(prepared.staging, target);
        } catch (DeltaSkinException error) {
            move(backup, target);
            throw error;
        }
        Installed installed = read(target);
        if (installed == null) {
            DeltaSkinArchive.deleteTree(target);
            move(backup, target);
            throw new DeltaSkinException(Code.STORAGE_ERROR, "Le skin remplacé ne peut pas être relu");
        }
        DeltaSkinArchive.deleteTree(backup);
        return new Result(Outcome.REPLACED, installed, existing != null ? existing.metadata.archiveSha256 : "");
    }

    /** The removed skin, or null when it was not installed. */
    public Installed delete(String identifier) throws DeltaSkinException {
        Installed installed = find(identifier);
        if (installed == null) return null;
        try {
            if (!installed.directory.getCanonicalFile().getParentFile().equals(root.getCanonicalFile()))
                throw new DeltaSkinException(Code.STORAGE_ERROR, "Le dossier du skin sort du stockage privé");
        } catch (IOException error) {
            throw new DeltaSkinException(Code.STORAGE_ERROR, "Le skin n'a pas pu être supprimé", error);
        }
        DeltaSkinArchive.deleteTree(installed.directory);
        if (installed.directory.exists())
            throw new DeltaSkinException(Code.STORAGE_ERROR, "Le skin n'a pas pu être supprimé");
        return installed;
    }

    public void discard(DeltaSkinArchive.Prepared prepared) throws DeltaSkinException {
        requireStaged(prepared);
        DeltaSkinArchive.deleteTree(prepared.staging);
    }

    /** Leftovers of an interrupted import. A replaced skin's backup stays:
     * after a crash in the middle of a replacement it may be the only copy. */
    public void clearStaging() {
        DeltaSkinArchive.deleteTree(new File(root, DeltaSkinArchive.STAGING_DIRECTORY));
    }

    private Installed read(File directory) {
        try {
            if (!directory.isDirectory()) return null;
            if (!directory.getCanonicalFile().getParentFile().equals(root.getCanonicalFile())) return null;
            File metadataFile = new File(directory, DeltaSkinArchive.METADATA_FILE);
            File manifestFile = new File(directory, DeltaSkinArchive.MANIFEST_FILE);
            if (!metadataFile.isFile() || metadataFile.length() < 1 || metadataFile.length() > MAX_METADATA_BYTES)
                return null;
            if (!manifestFile.isFile() || manifestFile.length() < 1
                    || manifestFile.length() > DeltaSkinArchive.MAX_INFO_JSON_BYTES) return null;
            Metadata metadata = Metadata.fromJson(text(metadataFile));
            if (!metadata.storageKey.equals(directory.getName())
                    || !metadata.storageKey.equals(DeltaSkinArchive.storageKey(metadata.identifier))) return null;
            Manifest manifest = DeltaSkin.parse(text(manifestFile));
            DeltaSkinValidator.Validation validation = DeltaSkinValidator.validate(manifest);
            if (!manifest.identifier.equals(metadata.identifier) || validation.console != metadata.console) return null;
            File archive = new File(directory, DeltaSkinArchive.ARCHIVE_FILE);
            if (!archive.isFile() || archive.length() != metadata.archiveSize) return null;
            for (Kind kind : validation.portraitKinds) {
                String name = metadata.asset(kind);
                if (name == null || !name.equals(kind == Kind.STANDARD ? DeltaSkinArchive.STANDARD_PORTRAIT_FILE
                        : DeltaSkinArchive.EDGE_PORTRAIT_FILE)) return null;
                File pdf = new File(new File(directory, DeltaSkinArchive.ASSETS_DIRECTORY), name);
                if (!pdf.isFile() || pdf.length() < 1 || pdf.length() > DeltaSkinArchive.MAX_ENTRY_UNCOMPRESSED_BYTES)
                    return null;
            }
            return new Installed(directory, metadata, manifest, size(directory));
        } catch (IOException | JSONException | DeltaSkinException | RuntimeException unreadable) {
            return null;
        }
    }

    private void requireStaged(DeltaSkinArchive.Prepared prepared) throws DeltaSkinException {
        try {
            File staging = new File(root, DeltaSkinArchive.STAGING_DIRECTORY).getCanonicalFile();
            if (!prepared.staging.getCanonicalFile().getParentFile().equals(staging)
                    || !prepared.metadata.storageKey.equals(DeltaSkinArchive.storageKey(prepared.metadata.identifier)))
                throw new DeltaSkinException(Code.STORAGE_ERROR, "Le dossier temporaire du skin est invalide");
        } catch (IOException error) {
            throw new DeltaSkinException(Code.STORAGE_ERROR, "Le dossier temporaire du skin est invalide", error);
        }
    }

    private static void move(File source, File destination) throws DeltaSkinException {
        try {
            try {
                Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException notAtomic) {
                Files.move(source.toPath(), destination.toPath());
            }
        } catch (IOException error) {
            throw new DeltaSkinException(Code.STORAGE_ERROR, "Impossible de déplacer le skin dans le stockage privé", error);
        }
    }

    private static String text(File file) throws IOException {
        byte[] bytes = Files.readAllBytes(file.toPath());
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();
    }

    private static long size(File directory) throws IOException {
        try (Stream<Path> paths = Files.walk(directory.toPath())) {
            long total = 0;
            for (Path path : (Iterable<Path>) paths::iterator)
                if (Files.isRegularFile(path)) total += Files.size(path);
            return total;
        }
    }
}
