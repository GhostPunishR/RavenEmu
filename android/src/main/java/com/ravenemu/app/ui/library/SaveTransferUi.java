package com.ravenemu.app.ui.library;

import com.ravenemu.app.data.library.RomLibrary;
import com.ravenemu.app.data.saves.SaveRepository;
import com.ravenemu.app.data.saves.SaveStorage;
import com.ravenemu.app.data.saves.SaveTransfer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;

/** The game sheet's « Sauvegardes » section, after the beta's
 * GbSaveTransfersUi and SaveTransferSection (RavenEmu 0.8.1): the game's
 * save exported as .sav or .srm, and a .sav, .srm or RavenEmu .zip imported
 * after a review that names what it replaces. File work runs on
 * SaveStorage.IO, after the player's last SRAM write; the host forwards its
 * picker results and saved state. */
final class SaveTransferUi {
    static final int EXPORT_SAVE = 73, IMPORT_SAVE = 74;
    static final int DANGER = 0xffff928b;
    private static final String[] FORMATS = {SaveTransfer.SAV, SaveTransfer.SRM};
    private static final String[] FORMAT_LABELS = {
            "Fichier .sav (la plupart des émulateurs)", "Fichier .srm (RetroArch)"};

    private final Activity host;
    private final ExecutorService io = SaveStorage.IO;
    private boolean destroyed, busy;
    /** The game a picker was opened for, and the export's extension. */
    private String pendingKey, pendingFormat;
    private AlertDialog review;

    SaveTransferUi(Activity host, Bundle state) {
        this.host = host;
        if (state != null) {
            pendingKey = state.getString("saves.game");
            pendingFormat = state.getString("saves.format");
        }
    }

    void save(Bundle state) {
        state.putString("saves.game", pendingKey);
        state.putString("saves.format", pendingFormat);
    }

    void destroy() {
        destroyed = true;
        if (review != null) review.dismiss();
        review = null;
    }

    /** Adds the section under the sheet's actions; `close` dismisses the sheet. */
    void addSection(LinearLayout panel, RomLibrary.Entry entry, Runnable close) {
        float density = host.getResources().getDisplayMetrics().density;
        TextView heading = label("Sauvegardes", 16, HomeActivity.TEXT);
        heading.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams top = new LinearLayout.LayoutParams(-1, -2);
        top.topMargin = Math.round(18 * density);
        panel.addView(heading, top);
        panel.addView(label("Transférez votre progression sans exporter la ROM.", 12, HomeActivity.MUTED));
        LinearLayout row = new LinearLayout(host);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
        rowParams.topMargin = Math.round(6 * density);
        panel.addView(row, rowParams);
        Button export = button("Exporter", density);
        Button load = button("Importer", density);
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, Math.round(44 * density), 1);
        LinearLayout.LayoutParams second = new LinearLayout.LayoutParams(0, Math.round(44 * density), 1);
        second.leftMargin = Math.round(8 * density);
        row.addView(export, half);
        row.addView(load, second);
        export.setOnClickListener(view -> chooseFormat(view, entry, close));
        load.setOnClickListener(view -> {
            if (busy) return;
            close.run();
            pickImport(entry.key);
        });
    }

    private void chooseFormat(View anchor, RomLibrary.Entry entry, Runnable close) {
        if (busy) return;
        PopupMenu menu = new PopupMenu(host, anchor);
        for (int i = 0; i < FORMATS.length; i++) {
            final String format = FORMATS[i];
            // Ids 1 and 2: one per format.
            menu.getMenu().add(0, i + 1, i, FORMAT_LABELS[i]).setOnMenuItemClickListener(item -> {
                close.run();
                startExport(entry, format);
                return true;
            });
        }
        menu.show();
    }

    private void startExport(RomLibrary.Entry entry, String format) {
        busy = true;
        File files = host.getFilesDir();
        io.execute(() -> {
            String problem = null;
            try {
                if (SaveRepository.read(files, entry.key, "srm") == null)
                    problem = "Ce jeu n'a pas encore de sauvegarde.";
            } catch (IOException unreadable) {
                problem = "Export " + format + " impossible : " + unreadable.getMessage();
            }
            final String result = problem;
            ui(() -> {
                busy = false;
                if (result != null) { toast(result); return; }
                pendingKey = entry.key;
                pendingFormat = format;
                Intent create = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                create.addCategory(Intent.CATEGORY_OPENABLE);
                create.setType("application/octet-stream");
                create.putExtra(Intent.EXTRA_TITLE, SaveTransfer.exportName(entry.title, format));
                host.startActivityForResult(create, EXPORT_SAVE);
            });
        });
    }

    private void pickImport(String key) {
        pendingKey = key;
        pendingFormat = null;
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        pick.setType("*/*");
        pick.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip", "application/octet-stream", "*/*"});
        host.startActivityForResult(pick, IMPORT_SAVE);
    }

    /** The host's onActivityResult: true when the request was one of these. */
    boolean onResult(int request, int result, Intent data) {
        if (request != EXPORT_SAVE && request != IMPORT_SAVE) return false;
        String key = pendingKey, format = pendingFormat;
        pendingKey = null;
        pendingFormat = null;
        Uri uri = result == Activity.RESULT_OK && data != null ? data.getData() : null;
        if (uri == null || key == null) return true;
        if (request == EXPORT_SAVE) export(key, format == null ? SaveTransfer.SAV : format, uri);
        else preview(key, uri);
        return true;
    }

    private void export(String key, String format, Uri uri) {
        busy = true;
        File files = host.getFilesDir();
        io.execute(() -> {
            String message;
            try {
                byte[] battery = SaveRepository.read(files, key, "srm");
                if (battery == null) throw new IOException("Ce jeu n'a pas encore de sauvegarde.");
                try (OutputStream out = host.getContentResolver().openOutputStream(uri, "wt")) {
                    if (out == null) throw new IOException("Destination d'export inaccessible.");
                    out.write(battery);
                }
                message = "Fichier " + format + " exporté";
            } catch (IOException | RuntimeException failure) {
                message = "Export " + format + " impossible : " + reason(failure);
            }
            final String result = message;
            ui(() -> { busy = false; toast(result); });
        });
    }

    private void preview(String key, Uri uri) {
        busy = true;
        File files = host.getFilesDir();
        io.execute(() -> {
            try {
                RomLibrary.Entry game = find(files, key);
                String name = displayName(uri);
                // Only an archive names its ROM: by the SHA-256 of its bytes.
                String romSha = SaveTransfer.isArchive(name)
                        ? SaveTransfer.sha256(RomLibrary.readRom(files, game)) : null;
                SaveTransfer.Payload payload;
                try (InputStream in = host.getContentResolver().openInputStream(uri)) {
                    if (in == null) throw new IOException("Sauvegarde inaccessible.");
                    payload = SaveTransfer.read(in, name, romSha);
                }
                byte[] current = SaveRepository.read(files, key, "srm");
                ui(() -> { busy = false; showReview(game, payload, current); });
            } catch (IOException | RuntimeException failure) {
                ui(() -> { busy = false; toast("Import refusé : " + reason(failure)); });
            }
        });
    }

    /** The beta's SaveImportReviewDialog. */
    private void showReview(RomLibrary.Entry game, SaveTransfer.Payload payload, byte[] current) {
        float density = host.getResources().getDisplayMetrics().density;
        LinearLayout body = new LinearLayout(host);
        body.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(22 * density);
        body.setPadding(pad, Math.round(8 * density), pad, 0);
        body.addView(line("Jeu : " + game.title, 14, HomeActivity.TEXT, density));
        body.addView(line("Contenu : sauvegarde du jeu (" + payload.battery.length + " octets)",
                14, HomeActivity.TEXT, density));
        if (payload.skippedSlots.length > 0)
            body.addView(line("États " + slots(payload.skippedSlots) + " de l'archive non importés : "
                    + "ce sont des états de la bêta, liés à son propre core.", 13, HomeActivity.SECONDARY, density));
        if (payload.raw)
            body.addView(line("Un fichier .sav ou .srm seul n'identifie pas sa ROM. "
                    + "Vérifiez que vous avez choisi le bon jeu.", 13, DANGER, density));
        if (current != null) {
            body.addView(line("Des données existantes seront remplacées : sauvegarde du jeu.", 13, DANGER, density));
            if (current.length != payload.battery.length)
                body.addView(line("Le jeu utilise " + current.length + " octets : RavenEmu lira le début du "
                        + "fichier, comme le jeu le ferait.", 13, HomeActivity.SECONDARY, density));
        } else {
            body.addView(line("Les autres sauvegardes resteront inchangées.", 13, HomeActivity.SECONDARY, density));
        }
        review = new AlertDialog.Builder(host).setTitle("Importer les sauvegardes ?").setView(body)
                .setNegativeButton("Annuler", null)
                .setPositiveButton("Confirmer l'import", (dialog, which) -> apply(game, payload))
                .create();
        AlertDialog shown = review;
        shown.setOnDismissListener(ignored -> { if (review == shown) review = null; });
        shown.show();
        shown.getWindow().setBackgroundDrawable(HomeActivity.shape(HomeActivity.SURFACE, 18, density));
        shown.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(HomeActivity.ACCENT);
        shown.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(HomeActivity.SECONDARY);
    }

    private void apply(RomLibrary.Entry game, SaveTransfer.Payload payload) {
        if (busy) return;
        busy = true;
        File files = host.getFilesDir();
        io.execute(() -> {
            String message;
            try {
                // Never write for a game removed meanwhile.
                find(files, game.key);
                SaveRepository.write(files, game.key, "srm", payload.battery);
                message = "Sauvegardes importées avec succès";
            } catch (IOException | RuntimeException failure) {
                message = "Import impossible : " + reason(failure);
            }
            final String result = message;
            ui(() -> { busy = false; toast(result); });
        });
    }

    private static RomLibrary.Entry find(File files, String key) throws IOException {
        for (RomLibrary.Entry entry : RomLibrary.list(files))
            if (entry.key.equals(key)) return entry;
        throw new IOException("Ce jeu n'est plus dans la bibliothèque.");
    }

    private String displayName(Uri uri) throws IOException {
        try (Cursor cursor = host.getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getString(0);
        } catch (RuntimeException refused) {
            // A provider without the column: the document's path names it.
        }
        String last = uri.getLastPathSegment();
        if (last == null) throw new IOException("Nom de fichier inaccessible.");
        return last;
    }

    private static String slots(int[] slots) {
        StringBuilder text = new StringBuilder();
        for (int slot : slots) text.append(text.length() == 0 ? "" : ", ").append(slot);
        return text.toString();
    }

    private static String reason(Exception failure) {
        return failure.getMessage() == null ? "erreur inconnue" : failure.getMessage();
    }

    private void ui(Runnable work) {
        host.runOnUiThread(() -> { if (!destroyed) work.run(); });
    }

    private void toast(String message) {
        Toast.makeText(host, message, Toast.LENGTH_LONG).show();
    }

    private TextView label(String value, int size, int color) {
        TextView view = new TextView(host);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private TextView line(String value, int size, int color, float density) {
        TextView view = label(value, size, color);
        view.setPadding(0, Math.round(4 * density), 0, Math.round(4 * density));
        return view;
    }

    private Button button(String text, float density) {
        Button button = new Button(host);
        button.setText(text);
        button.setTextSize(12);
        button.setAllCaps(false);
        button.setTextColor(HomeActivity.ACCENT);
        android.graphics.drawable.GradientDrawable outline =
                HomeActivity.shape(HomeActivity.BACKGROUND, 12, density);
        outline.setStroke(Math.max(1, Math.round(density)), HomeActivity.OUTLINE);
        button.setBackground(outline);
        return button;
    }
}
