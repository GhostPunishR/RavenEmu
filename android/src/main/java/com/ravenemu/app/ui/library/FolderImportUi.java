package com.ravenemu.app.ui.library;

import com.ravenemu.app.data.library.FolderImport;
import com.ravenemu.app.platform.DocumentTree;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import java.io.IOException;
import java.util.concurrent.ExecutorService;

/** The library's « Importer un dossier », after the beta's GbLibraryUi and
 * FolderImportSummaryDialog (RavenEmu 0.8.1): the system folder picker, a
 * progress line under the search field, then the import's summary. The
 * import runs on the library's own executor, after the + button's. */
final class FolderImportUi {
    static final int PICK_FOLDER = 76;

    /** How a chosen folder is read; tests stand in for the documents provider. */
    interface Trees {
        FolderImport.Tree open(Uri uri) throws IOException;
    }

    private final Activity host;
    private final ExecutorService io;
    private final Runnable reload;
    private final LinearLayout row;
    private final TextView line;
    Trees trees;
    private boolean destroyed, busy;
    private AlertDialog summary;

    FolderImportUi(Activity host, ExecutorService io, Runnable reload) {
        this.host = host;
        this.io = io;
        this.reload = reload;
        trees = uri -> new DocumentTree(host.getContentResolver(), uri);
        float density = host.getResources().getDisplayMetrics().density;
        row = new LinearLayout(host);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int side = Math.round(16 * density), vertical = Math.round(6 * density);
        row.setPadding(side, vertical, side, vertical);
        ProgressBar spinner = new ProgressBar(host, null, android.R.attr.progressBarStyleSmall);
        int size = Math.round(18 * density);
        row.addView(spinner, new LinearLayout.LayoutParams(size, size));
        line = new TextView(host);
        line.setTextSize(12);
        line.setTextColor(HomeActivity.MUTED);
        LinearLayout.LayoutParams text = new LinearLayout.LayoutParams(0, -2, 1);
        text.leftMargin = Math.round(10 * density);
        row.addView(line, text);
        row.setVisibility(View.GONE);
    }

    /** The progress line, for the host to place under its search field. */
    View progressRow() { return row; }

    void destroy() {
        destroyed = true;
        if (summary != null) summary.dismiss();
        summary = null;
    }

    void pick() {
        if (busy) return;
        host.startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), PICK_FOLDER);
    }

    /** The host's onActivityResult: true when the request was this one. */
    boolean onResult(int request, int result, Intent data) {
        if (request != PICK_FOLDER) return false;
        Uri uri = result == Activity.RESULT_OK && data != null ? data.getData() : null;
        if (uri != null && !busy) start(uri);
        return true;
    }

    private void start(Uri uri) {
        busy = true;
        progress("Analyse du dossier…");
        io.execute(() -> {
            FolderImport.Result result = null;
            String failure = null;
            try {
                result = FolderImport.run(host.getFilesDir(), trees.open(uri), roms -> {
                    if (roms == 1 || roms % 5 == 0)
                        ui(() -> progress("Importation du dossier… " + roms + " ROM examinées"));
                });
            } catch (IOException | RuntimeException error) {
                failure = "Importation du dossier impossible : "
                        + (error.getMessage() == null ? "erreur inconnue" : error.getMessage());
            }
            final FolderImport.Result done = result;
            final String problem = failure;
            ui(() -> {
                busy = false;
                row.setVisibility(View.GONE);
                // Even a stopped import keeps the games it copied.
                reload.run();
                if (problem != null) Toast.makeText(host, problem, Toast.LENGTH_LONG).show();
                else showSummary(done);
            });
        });
    }

    private void progress(String text) {
        line.setText(text);
        row.setVisibility(View.VISIBLE);
    }

    /** The beta's FolderImportSummaryDialog. */
    private void showSummary(FolderImport.Result result) {
        float density = host.getResources().getDisplayMetrics().density;
        LinearLayout body = new LinearLayout(host);
        body.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(22 * density);
        body.setPadding(pad, Math.round(8 * density), pad, 0);
        body.addView(line("Jeux ajoutés : " + result.added, 14, HomeActivity.TEXT, density));
        body.addView(line("Doublons ignorés : " + result.duplicate, 14, HomeActivity.TEXT, density));
        body.addView(line("ROM invalides : " + result.invalid, 14, HomeActivity.TEXT, density));
        if (result.unreadable > 0)
            body.addView(line("Fichiers ou dossiers illisibles : " + result.unreadable, 14, HomeActivity.TEXT, density));
        body.addView(line("Autres fichiers ignorés : " + result.otherFiles, 13, HomeActivity.MUTED, density));
        body.addView(line("Dossiers parcourus : " + result.folders, 13, HomeActivity.MUTED, density));
        if (result.limited)
            body.addView(line("Parcours arrêté par une limite de sécurité ou de stockage. "
                    + "Les jeux déjà ajoutés sont conservés.", 13, SaveTransferUi.DANGER, density));
        body.addView(line("Les jeux existants, leurs sauvegardes et la partie en cours n'ont pas été modifiés.",
                12, HomeActivity.SECONDARY, density));
        summary = new AlertDialog.Builder(host).setTitle("Importation du dossier terminée").setView(body)
                .setPositiveButton("Terminer", null).create();
        AlertDialog shown = summary;
        shown.setOnDismissListener(ignored -> { if (summary == shown) summary = null; });
        shown.show();
        shown.getWindow().setBackgroundDrawable(HomeActivity.shape(HomeActivity.SURFACE, 18, density));
        shown.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(HomeActivity.ACCENT);
    }

    private TextView line(String value, int size, int color, float density) {
        TextView view = new TextView(host);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setPadding(0, Math.round(3 * density), 0, Math.round(3 * density));
        return view;
    }

    private void ui(Runnable work) {
        host.runOnUiThread(() -> { if (!destroyed) work.run(); });
    }
}
