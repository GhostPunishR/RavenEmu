package com.ravenemu.app.data.skins;

import com.ravenemu.app.data.skins.DeltaSkin.Console;
import com.ravenemu.app.data.skins.DeltaSkin.Kind;
import com.ravenemu.app.data.skins.DeltaSkinException.Code;
import com.ravenemu.app.settings.PlaybackSettings;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.StatFs;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** The beta's DeltaSkins: import a .deltaskin, list, delete, and the skin
 * chosen for each console (GB and GBC share theirs, as in Delta). The app's
 * own storage and preferences: the beta's skins are never read. Every call
 * does disk I/O: never on the UI thread. */
public final class DeltaSkins {
    /** An installed skin, as the settings list it. */
    public static final class Entry {
        public final String id, name, console;
        /** In use for its console instead of the classic controls. */
        public final boolean selected;

        Entry(String id, String name, String console, boolean selected) {
            this.id = id;
            this.name = name;
            this.console = console;
            this.selected = selected;
        }
    }

    /** One portrait representation and its PDF. */
    public static final class Asset {
        public final DeltaSkin.Representation representation;
        public final File pdf;

        Asset(DeltaSkin.Representation representation, File pdf) {
            this.representation = representation;
            this.pdf = pdf;
        }
    }

    /** What the player shows for a console: the skin's portrait PDFs, and the
     * console's own screen size for the picture. */
    public static final class Configuration {
        public final String sha256;
        public final Console console;
        public final Map<Kind, Asset> assets;
        public final double screenWidth, screenHeight;

        Configuration(String sha256, Console console, Map<Kind, Asset> assets) {
            this.sha256 = sha256;
            this.console = console;
            this.assets = Collections.unmodifiableMap(assets);
            screenWidth = console == Console.GBA ? 240 : 160;
            screenHeight = console == Console.GBA ? 160 : 144;
        }
    }

    private final Context context;
    private final DeltaSkinRepository repository;
    private final DeltaSkinArchive archive;
    private final SharedPreferences preferences;

    public DeltaSkins(Context context) {
        this(context, DeltaSkinPdf::inspect);
    }

    /** The skins' folder in private storage. */
    public static File root(Context context) {
        return new File(context.getApplicationContext().getFilesDir(), "delta-skins");
    }

    /** Tests replace the platform's PdfRenderer. */
    DeltaSkins(Context context, DeltaSkinArchive.PdfInspector inspector) {
        this.context = context.getApplicationContext();
        File root = root(this.context);
        repository = new DeltaSkinRepository(root);
        archive = new DeltaSkinArchive(root, inspector);
        preferences = this.context.getSharedPreferences(PlaybackSettings.PREFERENCES, Context.MODE_PRIVATE);
    }

    public static Console console(String system) {
        return "gba".equals(system) ? Console.GBA : Console.GB_GBC;
    }

    /** Imports a .deltaskin and selects it for its console; returns the message to show. */
    public String importSkin(Uri uri) {
        DeltaSkinArchive.Prepared prepared = null;
        File copy = null;
        try {
            String name = displayName(uri);
            if (name == null || !name.toLowerCase(Locale.ROOT).endsWith(".deltaskin"))
                throw new DeltaSkinException(Code.INVALID_EXTENSION, "Le fichier doit porter l'extension .deltaskin");
            Long size = size(uri);
            if (size != null && size > DeltaSkinArchive.MAX_ARCHIVE_BYTES)
                throw new DeltaSkinException(Code.ARCHIVE_TOO_LARGE, "Le skin dépasse 25 Mio");
            if (new StatFs(context.getFilesDir().getAbsolutePath()).getAvailableBytes() < DeltaSkinArchive.MAX_ARCHIVE_BYTES)
                throw new DeltaSkinException(Code.STORAGE_INSUFFICIENT, "Le stockage disponible est insuffisant");
            repository.clearStaging();
            copy = copy(uri);
            prepared = archive.prepare(copy, name);
            DeltaSkinRepository.Result result = repository.install(prepared, false);
            // Same identifier, another archive: an update of that skin.
            if (result.outcome == DeltaSkinRepository.Outcome.CONFLICT) result = repository.install(prepared, true);
            switch (result.outcome) {
                case INSTALLED:
                    select(result.skin.metadata.console, result.skin.metadata.identifier);
                    return "Skin importé et sélectionné";
                case REPLACED:
                    DeltaSkinPdf.invalidate(result.previousSha256);
                    select(result.skin.metadata.console, result.skin.metadata.identifier);
                    return "Skin mis à jour et sélectionné";
                case DUPLICATE:
                    return "Ce skin est déjà installé";
                default:
                    return DeltaSkinException.userMessage(Code.IDENTIFIER_ALREADY_INSTALLED);
            }
        } catch (DeltaSkinException error) {
            return DeltaSkinException.userMessage(error.code);
        } catch (IOException | RuntimeException error) {
            return DeltaSkinException.userMessage(Code.STORAGE_ERROR);
        } finally {
            if (prepared != null && prepared.staging.exists()) DeltaSkinArchive.deleteTree(prepared.staging);
            if (copy != null) copy.delete();
        }
    }

    public List<Entry> entries() {
        List<Entry> entries = new ArrayList<>();
        for (DeltaSkinRepository.Installed skin : repository.list()) {
            Console console = skin.metadata.console;
            entries.add(new Entry(skin.metadata.identifier, skin.manifest.name,
                    console == Console.GBA ? "Game Boy Advance" : "Game Boy / Game Boy Color",
                    skin.metadata.identifier.equals(selected(console))));
        }
        return entries;
    }

    /** Use this skin for its console, or go back to the classic controls. */
    public void toggle(String identifier) {
        DeltaSkinRepository.Installed skin = repository.find(identifier);
        if (skin == null) return;
        Console console = skin.metadata.console;
        select(console, identifier.equals(selected(console)) ? null : identifier);
    }

    public void delete(String identifier) throws DeltaSkinException {
        DeltaSkinRepository.Installed removed = repository.delete(identifier);
        if (removed == null) return;
        DeltaSkinPdf.invalidate(removed.metadata.archiveSha256);
        for (Console console : Console.values()) if (identifier.equals(selected(console))) select(console, null);
    }

    /** The console's skin, or null for the classic controls. A skin that no
     * longer reads back is unselected, as in the beta. */
    public Configuration configuration(Console console) {
        String identifier = selected(console);
        if (identifier == null) return null;
        DeltaSkinRepository.Installed installed = repository.find(identifier);
        Map<Kind, Asset> assets = new EnumMap<>(Kind.class);
        if (installed != null && installed.metadata.console == console) {
            for (Kind kind : Kind.values()) {
                DeltaSkin.Representation representation = installed.manifest.portrait(kind);
                File pdf = installed.pdf(kind);
                if (representation != null && pdf != null) assets.put(kind, new Asset(representation, pdf));
            }
        }
        if (installed == null || assets.isEmpty()) {
            select(console, null);
            return null;
        }
        return new Configuration(installed.metadata.archiveSha256, console, assets);
    }

    /** The skin failed while playing: that console goes back to the classic controls. */
    public void fallback(Console console, String sha256) {
        DeltaSkinPdf.invalidate(sha256);
        select(console, null);
    }

    private String selected(Console console) { return preferences.getString(key(console), null); }

    private void select(Console console, String identifier) {
        SharedPreferences.Editor editor = preferences.edit();
        if (identifier == null) editor.remove(key(console));
        else editor.putString(key(console), identifier);
        editor.apply();
    }

    private static String key(Console console) {
        return "skins.selected." + console.name().toLowerCase(Locale.ROOT);
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (column >= 0 && !cursor.isNull(column)) return cursor.getString(column);
            }
        }
        return uri.getLastPathSegment();
    }

    private Long size(Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(uri, new String[]{OpenableColumns.SIZE},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (column >= 0 && !cursor.isNull(column)) return cursor.getLong(column);
            }
        }
        return null;
    }

    /** A bounded private copy: the chosen document is read once, never kept. */
    private File copy(Uri uri) throws IOException, DeltaSkinException {
        File folder = new File(context.getCacheDir(), "delta-skin-import");
        if (!folder.isDirectory() && !folder.mkdirs())
            throw new DeltaSkinException(Code.STORAGE_ERROR, "Impossible de préparer le cache d'importation");
        File copy = new File(folder, UUID.randomUUID() + ".deltaskin");
        try (InputStream input = context.getContentResolver().openInputStream(uri);
             OutputStream output = new FileOutputStream(copy)) {
            if (input == null) throw new IOException("Document sans contenu");
            byte[] buffer = new byte[32 * 1024];
            long total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > DeltaSkinArchive.MAX_ARCHIVE_BYTES)
                    throw new DeltaSkinException(Code.ARCHIVE_TOO_LARGE, "Le skin dépasse 25 Mio");
                output.write(buffer, 0, read);
            }
        } catch (IOException | DeltaSkinException | RuntimeException error) {
            copy.delete();
            throw error;
        }
        return copy;
    }
}
