package com.ravenemu.app.data.skins;

import com.ravenemu.app.data.skins.DeltaSkin.Manifest;
import com.ravenemu.app.data.skins.DeltaSkin.Representation;
import com.ravenemu.app.data.skins.DeltaSkinException.Code;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/** The beta's DeltaSkinArchiveImporter: reads a .deltaskin (a ZIP) under
 * strict limits, then prepares its installation in a private staging folder.
 * No name from the archive or the manifest becomes a destination path: the
 * portrait PDFs are copied under fixed names, after the whole archive was
 * checked (sizes, compression ratio, paths, links, nested archives, duplicates,
 * one info.json, one-page PDFs at the mapping's aspect). Disk I/O: off the UI
 * thread. */
public final class DeltaSkinArchive {
    public static final long MAX_ARCHIVE_BYTES = 25L * 1024 * 1024;
    public static final int MAX_ENTRIES = 64;
    public static final long MAX_TOTAL_UNCOMPRESSED_BYTES = 50L * 1024 * 1024;
    public static final long MAX_INFO_JSON_BYTES = 1024L * 1024;
    public static final long MAX_ENTRY_UNCOMPRESSED_BYTES = 25L * 1024 * 1024;

    static final String ARCHIVE_FILE = "skin.deltaskin";
    static final String MANIFEST_FILE = "info.json";
    static final String METADATA_FILE = "metadata.json";
    static final String ASSETS_DIRECTORY = "assets";
    static final String STANDARD_PORTRAIT_FILE = "standard-portrait.pdf";
    static final String EDGE_PORTRAIT_FILE = "edge-to-edge-portrait.pdf";
    static final String STAGING_DIRECTORY = ".staging";

    private static final int COPY_BUFFER = 32 * 1024;
    private static final long COMPRESSION_RATIO_MINIMUM_BYTES = 1024L * 1024;
    private static final long MAX_COMPRESSION_RATIO = 200;
    private static final double PDF_ASPECT_TOLERANCE = 0.01;
    private static final String[] NESTED_ARCHIVES = {".zip", ".deltaskin", ".jar", ".apk", ".tar", ".tgz", ".gz",
            ".7z", ".rar"};

    /** A PDF's page count and first page size; the platform's PdfRenderer in the app. */
    public interface PdfInspector {
        PdfInfo inspect(File pdf) throws IOException;
    }

    public static final class PdfInfo {
        public final int pages, width, height;

        public PdfInfo(int pages, int width, int height) {
            this.pages = pages;
            this.width = width;
            this.height = height;
        }
    }

    /** A checked skin waiting in its staging folder, installed or discarded by the repository. */
    public static final class Prepared {
        public final File staging;
        public final DeltaSkinRepository.Metadata metadata;
        public final Manifest manifest;

        Prepared(File staging, DeltaSkinRepository.Metadata metadata, Manifest manifest) {
            this.staging = staging;
            this.metadata = metadata;
            this.manifest = manifest;
        }
    }

    private final File root;
    private final PdfInspector inspector;
    private final Clock clock;

    interface Clock { long now(); }

    public DeltaSkinArchive(File root, PdfInspector inspector) {
        this(root, inspector, System::currentTimeMillis);
    }

    DeltaSkinArchive(File root, PdfInspector inspector, Clock clock) {
        this.root = root;
        this.inspector = inspector;
        this.clock = clock;
    }

    public Prepared prepare(File archive, String originalName) throws DeltaSkinException {
        validateExtension(originalName);
        validateArchiveFile(archive);
        List<CentralEntry> central = CentralDirectory.read(archive);
        Inspection inspection = inspect(archive, central);
        byte[] manifestBytes = inspection.manifest;
        Manifest manifest = DeltaSkin.parse(utf8(manifestBytes));
        DeltaSkinValidator.Validation validation = DeltaSkinValidator.validate(manifest);

        String standardAsset = manifest.standardPortrait != null ? manifest.standardPortrait.resizable : null;
        String edgeAsset = manifest.edgePortrait != null ? manifest.edgePortrait.resizable : null;
        // Every representation's PDF, landscape included: all are checked, the
        // portrait ones kept.
        Set<String> referenced = new LinkedHashSet<>();
        for (Representation representation : manifest.representations()) referenced.add(representation.resizable);
        Map<String, ZipEntry> available = new HashMap<>();
        for (ZipEntry entry : inspection.entries) if (!ignored(entry.getName())) available.put(entry.getName(), entry);
        long required = archive.length() + MAX_INFO_JSON_BYTES;
        for (String name : referenced) {
            ZipEntry entry = available.get(name);
            if (entry == null || entry.isDirectory())
                throw new DeltaSkinException(Code.RESIZABLE_ASSET_MISSING, "Un asset PDF référencé est absent de l'archive");
            required += entry.getSize();
        }
        long usable = root.getUsableSpace();
        if (usable > 0 && usable < required)
            throw new DeltaSkinException(Code.STORAGE_INSUFFICIENT, "Le stockage disponible est insuffisant");

        File stagingRoot = new File(root, STAGING_DIRECTORY);
        if (!stagingRoot.isDirectory() && !stagingRoot.mkdirs())
            throw new DeltaSkinException(Code.STORAGE_ERROR, "Impossible de préparer le stockage des skins");
        File staging = new File(stagingRoot, UUID.randomUUID().toString());
        if (!staging.mkdir())
            throw new DeltaSkinException(Code.STORAGE_ERROR, "Impossible de créer le dossier temporaire du skin");
        try {
            File copy = new File(staging, ARCHIVE_FILE);
            Files.copy(archive.toPath(), copy.toPath());
            write(new File(staging, MANIFEST_FILE), manifestBytes);
            File assets = new File(staging, ASSETS_DIRECTORY);
            if (!assets.mkdir()) throw new DeltaSkinException(Code.STORAGE_ERROR, "Impossible de préparer les assets du skin");
            Map<String, PdfInfo> infos = new HashMap<>();
            try (ZipFile zip = new ZipFile(archive, StandardCharsets.UTF_8)) {
                if (standardAsset != null)
                    infos.put(standardAsset, extract(zip, standardAsset, new File(assets, STANDARD_PORTRAIT_FILE)));
                if (edgeAsset != null && !infos.containsKey(edgeAsset))
                    infos.put(edgeAsset, extract(zip, edgeAsset, new File(assets, EDGE_PORTRAIT_FILE)));
                else if (edgeAsset != null)
                    Files.copy(new File(assets, STANDARD_PORTRAIT_FILE).toPath(), new File(assets, EDGE_PORTRAIT_FILE).toPath());
                int index = 0;
                for (String name : referenced) {
                    if (infos.containsKey(name)) continue;
                    File check = new File(staging, "validation-" + index++ + ".pdf");
                    try { infos.put(name, extract(zip, name, check)); }
                    finally { check.delete(); }
                }
            }
            for (Representation representation : manifest.representations())
                checkAspect(infos.get(representation.resizable), representation.mapping);

            DeltaSkinRepository.Metadata metadata = new DeltaSkinRepository.Metadata(
                    storageKey(manifest.identifier), manifest.identifier, validation.console, sha256(copy),
                    clock.now(), copy.length(), standardAsset != null ? STANDARD_PORTRAIT_FILE : null,
                    edgeAsset != null ? EDGE_PORTRAIT_FILE : null, validation.ignoredInputs);
            write(new File(staging, METADATA_FILE), metadata.toJson().getBytes(StandardCharsets.UTF_8));
            return new Prepared(staging, metadata, manifest);
        } catch (DeltaSkinException error) {
            deleteTree(staging);
            throw error;
        } catch (IOException error) {
            deleteTree(staging);
            long space = root.getUsableSpace();
            throw new DeltaSkinException(space > 0 && space < archive.length() ? Code.STORAGE_INSUFFICIENT
                    : Code.STORAGE_ERROR, "Impossible de stocker le skin", error);
        } catch (RuntimeException error) {
            deleteTree(staging);
            throw new DeltaSkinException(Code.INVALID_ARCHIVE, "L'archive du skin est illisible", error);
        }
    }

    private static final class Inspection {
        final List<ZipEntry> entries;
        final byte[] manifest;

        Inspection(List<ZipEntry> entries, byte[] manifest) {
            this.entries = entries;
            this.manifest = manifest;
        }
    }

    private static Inspection inspect(File archive, List<CentralEntry> central) throws DeltaSkinException {
        if (central.size() > MAX_ENTRIES) throw new DeltaSkinException(Code.TOO_MANY_ENTRIES, "Le skin contient trop de fichiers");
        for (CentralEntry entry : central) {
            if (entry.encrypted)
                throw new DeltaSkinException(Code.INVALID_ARCHIVE, "Les archives chiffrées ne sont pas acceptées");
            if (entry.symbolicLink)
                throw new DeltaSkinException(Code.SYMBOLIC_LINK, "Les liens symboliques ne sont pas acceptés");
        }
        try (ZipFile zip = new ZipFile(archive, StandardCharsets.UTF_8)) {
            List<ZipEntry> entries = new ArrayList<>();
            for (Enumeration<? extends ZipEntry> all = zip.entries(); all.hasMoreElements(); ) entries.add(all.nextElement());
            if (entries.size() != central.size())
                throw new DeltaSkinException(Code.INVALID_ARCHIVE, "Le répertoire ZIP est incohérent");
            Set<String> names = new HashSet<>();
            int manifests = 0;
            long declared = 0, actual = 0;
            byte[] manifest = null;
            for (int index = 0; index < entries.size(); index++) {
                ZipEntry entry = entries.get(index);
                if (!entry.getName().equals(central.get(index).name))
                    throw new DeltaSkinException(Code.INVALID_ARCHIVE, "Le répertoire ZIP est incohérent");
                checkMetadata(entry);
                declared = add(declared, entry.getSize());
                if (declared > MAX_TOTAL_UNCOMPRESSED_BYTES)
                    throw new DeltaSkinException(Code.DECOMPRESSED_TOO_LARGE,
                            "Le contenu décompressé du skin est trop volumineux");
                boolean ignored = ignored(entry.getName());
                if (!ignored) {
                    if (entry.isDirectory() || !DeltaSkinValidator.isSafeRootAssetName(entry.getName()))
                        throw new DeltaSkinException(Code.UNSAFE_ARCHIVE_PATH, "L'archive contient un chemin non sûr");
                    String canonical = Normalizer.normalize(entry.getName(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
                    if (!names.add(canonical))
                        throw new DeltaSkinException(canonical.equals(MANIFEST_FILE) ? Code.MANIFEST_DUPLICATE
                                : Code.INVALID_ARCHIVE, "L'archive contient des fichiers en doublon");
                    if (entry.getName().equals(MANIFEST_FILE)) manifests++;
                    if (nestedName(entry.getName()))
                        throw new DeltaSkinException(Code.NESTED_ARCHIVE, "Les archives imbriquées ne sont pas acceptées");
                }
                if (entry.isDirectory()) continue;
                ByteArrayOutputStream output = !ignored && entry.getName().equals(MANIFEST_FILE)
                        ? new ByteArrayOutputStream((int) Math.max(32, entry.getSize())) : null;
                long size = 0;
                byte[] prefix = new byte[4];
                int prefixCount = 0;
                try (InputStream input = zip.getInputStream(entry)) {
                    byte[] buffer = new byte[COPY_BUFFER];
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        if (read == 0) continue;
                        if (prefixCount < prefix.length) {
                            int copy = Math.min(read, prefix.length - prefixCount);
                            System.arraycopy(buffer, 0, prefix, prefixCount, copy);
                            prefixCount += copy;
                        }
                        size += read;
                        actual += read;
                        if (size > MAX_ENTRY_UNCOMPRESSED_BYTES || actual > MAX_TOTAL_UNCOMPRESSED_BYTES)
                            throw new DeltaSkinException(Code.DECOMPRESSED_TOO_LARGE,
                                    "Le contenu décompressé du skin est trop volumineux");
                        if (output != null) output.write(buffer, 0, read);
                    }
                }
                if (!ignored && zipSignature(prefix, prefixCount))
                    throw new DeltaSkinException(Code.NESTED_ARCHIVE, "Les archives imbriquées ne sont pas acceptées");
                if (size != entry.getSize())
                    throw new DeltaSkinException(Code.INVALID_ARCHIVE, "La taille déclarée d'un fichier ZIP est incohérente");
                if (output != null) manifest = output.toByteArray();
            }
            if (manifests == 0 || manifest == null)
                throw new DeltaSkinException(Code.MANIFEST_MISSING, "Le fichier info.json est absent");
            if (manifests != 1)
                throw new DeltaSkinException(Code.MANIFEST_DUPLICATE, "L'archive contient plusieurs info.json");
            return new Inspection(entries, manifest);
        } catch (ZipException error) {
            throw new DeltaSkinException(Code.INVALID_ARCHIVE, "La structure ZIP est invalide", error);
        } catch (IOException error) {
            throw new DeltaSkinException(Code.INVALID_ARCHIVE, "L'archive ne peut pas être lue", error);
        }
    }

    private static void checkMetadata(ZipEntry entry) throws DeltaSkinException {
        if (entry.getSize() < 0 || entry.getCompressedSize() < 0)
            throw new DeltaSkinException(Code.INVALID_ARCHIVE, "Une entrée ZIP ne déclare pas sa taille");
        if (entry.getMethod() != ZipEntry.STORED && entry.getMethod() != ZipEntry.DEFLATED)
            throw new DeltaSkinException(Code.INVALID_ARCHIVE, "Une méthode de compression ZIP n'est pas prise en charge");
        boolean manifest = entry.getName().equals(MANIFEST_FILE);
        if (entry.getSize() > (manifest ? MAX_INFO_JSON_BYTES : MAX_ENTRY_UNCOMPRESSED_BYTES))
            throw new DeltaSkinException(manifest ? Code.MANIFEST_TOO_LARGE : Code.ENTRY_TOO_LARGE,
                    "Un fichier du skin est trop volumineux");
        if (entry.getCompressedSize() > 0 && entry.getSize() > COMPRESSION_RATIO_MINIMUM_BYTES
                && entry.getSize() / entry.getCompressedSize() > MAX_COMPRESSION_RATIO)
            throw new DeltaSkinException(Code.DECOMPRESSED_TOO_LARGE, "Le taux de compression du skin est excessif");
    }

    private PdfInfo extract(ZipFile zip, String name, File destination) throws DeltaSkinException, IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) throw new DeltaSkinException(Code.RESIZABLE_ASSET_MISSING, "Un asset PDF référencé est absent");
        try (InputStream input = zip.getInputStream(entry); OutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[COPY_BUFFER];
            long total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > MAX_ENTRY_UNCOMPRESSED_BYTES)
                    throw new DeltaSkinException(Code.ENTRY_TOO_LARGE, "L'asset PDF est trop volumineux");
                output.write(buffer, 0, read);
            }
        }
        PdfInfo info;
        try {
            info = inspector.inspect(destination);
        } catch (IOException | RuntimeException error) {
            throw new DeltaSkinException(Code.PDF_INVALID, "L'asset PDF est illisible", error);
        }
        if (info.pages != 1) throw new DeltaSkinException(Code.PDF_MULTIPLE_PAGES, "Seuls les PDF d'une page sont acceptés");
        if (info.width <= 0 || info.height <= 0)
            throw new DeltaSkinException(Code.PDF_INVALID, "L'asset PDF n'a pas de dimensions valides");
        return info;
    }

    private static void checkAspect(PdfInfo info, DeltaSkin.Size mapping) throws DeltaSkinException {
        double pdf = (double) info.width / info.height, expected = mapping.width / mapping.height;
        if (Math.abs(pdf / expected - 1.0) > PDF_ASPECT_TOLERANCE)
            throw new DeltaSkinException(Code.PDF_INVALID, "Le ratio de l'asset PDF ne correspond pas à mappingSize");
    }

    private static void validateExtension(String name) throws DeltaSkinException {
        if (name == null || !name.toLowerCase(Locale.ROOT).endsWith(".deltaskin"))
            throw new DeltaSkinException(Code.INVALID_EXTENSION, "Le fichier doit porter l'extension .deltaskin");
    }

    private static void validateArchiveFile(File file) throws DeltaSkinException {
        if (!file.isFile() || file.length() <= 4)
            throw new DeltaSkinException(Code.NOT_A_ZIP, "Le fichier sélectionné n'est pas une archive ZIP");
        if (file.length() > MAX_ARCHIVE_BYTES)
            throw new DeltaSkinException(Code.ARCHIVE_TOO_LARGE, "Le skin dépasse la taille maximale autorisée");
        byte[] signature = new byte[4];
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            input.readFully(signature);
        } catch (IOException error) {
            throw new DeltaSkinException(Code.NOT_A_ZIP, "Le fichier sélectionné n'est pas une archive ZIP", error);
        }
        if (!zipSignature(signature, signature.length))
            throw new DeltaSkinException(Code.NOT_A_ZIP, "Le fichier sélectionné n'est pas une archive ZIP");
    }

    private static String utf8(byte[] bytes) throws DeltaSkinException {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException error) {
            throw new DeltaSkinException(Code.MALFORMED_JSON, "info.json n'est pas encodé en UTF-8 valide", error);
        }
    }

    static String sha256(File file) throws IOException {
        MessageDigest digest = digest();
        try (InputStream input = Files.newInputStream(file.toPath())) {
            byte[] buffer = new byte[COPY_BUFFER];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        return hex(digest.digest());
    }

    /** The skin's folder name: the SHA-256 of its identifier, never the identifier itself. */
    public static String storageKey(String identifier) {
        return hex(digest().digest(identifier.getBytes(StandardCharsets.UTF_8)));
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) text.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return text.toString();
    }

    private static long add(long left, long right) throws DeltaSkinException {
        if (right < 0 || left > Long.MAX_VALUE - right)
            throw new DeltaSkinException(Code.DECOMPRESSED_TOO_LARGE, "Les tailles ZIP sont incohérentes");
        return left + right;
    }

    /** macOS leaves these in archives; they are skipped, never extracted. */
    private static boolean ignored(String name) {
        String base = name.substring(name.lastIndexOf('/') + 1);
        return name.equals("__MACOSX") || name.startsWith("__MACOSX/") || base.equals(".DS_Store") || base.startsWith("._");
    }

    private static boolean nestedName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String extension : NESTED_ARCHIVES) if (lower.endsWith(extension)) return true;
        return false;
    }

    private static boolean zipSignature(byte[] prefix, int count) {
        if (count < 4 || prefix[0] != 0x50 || prefix[1] != 0x4b) return false;
        return (prefix[2] == 3 && prefix[3] == 4) || (prefix[2] == 5 && prefix[3] == 6) || (prefix[2] == 7 && prefix[3] == 8);
    }

    private static void write(File file, byte[] bytes) throws IOException {
        try (OutputStream output = new FileOutputStream(file)) {
            output.write(bytes);
        }
    }

    static void deleteTree(File root) {
        if (!root.exists()) return;
        try (Stream<Path> paths = Files.walk(root.toPath())) {
            List<Path> all = new ArrayList<>();
            paths.forEach(all::add);
            Collections.reverse(all);
            for (Path path : all) Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // A leftover staging folder is cleared at the next import.
        }
    }

    private static final class CentralEntry {
        final String name;
        final boolean encrypted, symbolicLink;

        CentralEntry(String name, boolean encrypted, boolean symbolicLink) {
            this.name = name;
            this.encrypted = encrypted;
            this.symbolicLink = symbolicLink;
        }
    }

    /** The central directory, read by hand: ZipEntry hides the Unix mode
     * that tells a symbolic link, and the encryption flag. */
    private static final class CentralDirectory {
        private static final int END_SIGNATURE = 0x06054b50;
        private static final int ENTRY_SIGNATURE = 0x02014b50;
        private static final long MAX_END_SEARCH = 65_557;

        static List<CentralEntry> read(File file) throws DeltaSkinException {
            try (RandomAccessFile archive = new RandomAccessFile(file, "r")) {
                long end = findEnd(archive);
                archive.seek(end + 4);
                int disk = u16(archive), centralDisk = u16(archive), onDisk = u16(archive), count = u16(archive);
                long size = u32(archive), offset = u32(archive);
                int comment = u16(archive);
                if (disk != 0 || centralDisk != 0 || onDisk != count || count == 0xffff || size == 0xffffffffL
                        || offset == 0xffffffffL || end + 22 + comment != archive.length())
                    throw new DeltaSkinException(Code.INVALID_ARCHIVE, "Les archives multi-disques ou ZIP64 ne sont pas acceptées");
                if (offset + size > end)
                    throw new DeltaSkinException(Code.INVALID_ARCHIVE, "Le répertoire central ZIP est hors limites");
                archive.seek(offset);
                List<CentralEntry> entries = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    if (s32(archive) != ENTRY_SIGNATURE)
                        throw new DeltaSkinException(Code.INVALID_ARCHIVE, "Le répertoire central ZIP est invalide");
                    int madeBy = u16(archive);
                    archive.skipBytes(2);
                    int flags = u16(archive);
                    archive.skipBytes(18);
                    int nameLength = u16(archive), extraLength = u16(archive), commentLength = u16(archive);
                    archive.skipBytes(4);
                    long external = u32(archive);
                    archive.skipBytes(4);
                    byte[] name = new byte[nameLength];
                    archive.readFully(name);
                    Charset charset = (flags & 0x0800) != 0 ? StandardCharsets.UTF_8 : Charset.forName("Cp437");
                    archive.skipBytes(extraLength + commentLength);
                    int host = madeBy >>> 8;
                    int mode = (int) (external >>> 16);
                    entries.add(new CentralEntry(new String(name, charset), (flags & 1) != 0,
                            host == 3 && (mode & 0xf000) == 0xa000));
                }
                if (archive.getFilePointer() != offset + size)
                    throw new DeltaSkinException(Code.INVALID_ARCHIVE, "La taille du répertoire central ZIP est incohérente");
                return entries;
            } catch (IOException error) {
                throw new DeltaSkinException(Code.INVALID_ARCHIVE, "Le répertoire central ZIP est illisible", error);
            }
        }

        private static long findEnd(RandomAccessFile archive) throws IOException, DeltaSkinException {
            long minimum = Math.max(0, archive.length() - MAX_END_SEARCH);
            for (long position = archive.length() - 22; position >= minimum; position--) {
                archive.seek(position);
                if (s32(archive) == END_SIGNATURE) return position;
            }
            throw new DeltaSkinException(Code.INVALID_ARCHIVE, "La fin du répertoire ZIP est absente");
        }

        private static int u16(RandomAccessFile in) throws IOException {
            return Short.toUnsignedInt(Short.reverseBytes(in.readShort()));
        }

        private static int s32(RandomAccessFile in) throws IOException {
            return Integer.reverseBytes(in.readInt());
        }

        private static long u32(RandomAccessFile in) throws IOException {
            return Integer.toUnsignedLong(s32(in));
        }
    }
}
