package com.ravenemu.app.ui.settings;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import com.ravenemu.app.data.skins.DeltaSkinException;
import com.ravenemu.app.data.skins.DeltaSkins;
import com.ravenemu.app.ui.common.RavenTheme;
import java.util.Collections;
import java.util.List;

import static com.ravenemu.app.ui.settings.PlaybackSettingsActivity.INTERFACE_SKINS;
import static com.ravenemu.app.ui.settings.PlaybackSettingsActivity.PICK_SKIN;

/** Settings → Interface → Skin Delta: import a .deltaskin, use it for its
 * console or go back to the classic controls, delete it. In portrait only. */
final class SkinSettingsPage {
    private final PlaybackSettingsActivity activity;
    private final SettingsWidgets widgets;
    private final RavenTheme theme;
    /** The installed Delta skins, read on the IO executor; an import or a change in progress. */
    private DeltaSkins skins;
    private List<DeltaSkins.Entry> skinEntries = Collections.emptyList();
    private boolean skinsRead, skinBusy, skinImporting;
    private AlertDialog skinDialog;

    SkinSettingsPage(PlaybackSettingsActivity activity, SettingsWidgets widgets) {
        this.activity = activity;
        this.widgets = widgets;
        this.theme = activity.theme();
        skins = new DeltaSkins(activity);
    }

    /** The beta's Skin Delta section: import a .deltaskin, use it for its
     * console or go back to the classic controls, delete it. In portrait only. */
    void interfaceSkinsPage() {
        widgets.add(widgets.text("RavenEmu lit les skins au format .deltaskin, en portrait. Ce projet n'est ni affilié à Delta, "
                + "ni approuvé par ses auteurs : aucun skin n'est fourni, vous importez les vôtres. Game Boy et "
                + "Game Boy Color partagent leur skin.", 13, theme.muted), 0);
        Button pick = widgets.textButton("Importer un fichier .deltaskin", theme.accent);
        pick.setEnabled(!skinBusy);
        pick.setOnClickListener(view -> pickSkin());
        if (skinImporting) widgets.add(widgets.text("Importation du skin…", 12, theme.muted), 4);
        if (!skinsRead) {
            widgets.add(widgets.text("Lecture des skins…", 12, theme.muted), 8);
            if (!skinBusy) reloadSkins(null);
            return;
        }
        if (skinEntries.isEmpty()) widgets.add(widgets.text("Aucun skin installé : commandes classiques.", 12, theme.muted), 8);
        for (DeltaSkins.Entry skin : skinEntries) {
            LinearLayout row = new LinearLayout(activity);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(widgets.dp(56));
            LinearLayout texts = widgets.column();
            TextView name = widgets.text(skin.name, 14, theme.text);
            name.setTypeface(null, Typeface.BOLD);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            texts.addView(name);
            texts.addView(widgets.text(skin.console + (skin.selected ? " · utilisé" : ""), 12,
                    skin.selected ? theme.accent : theme.muted));
            row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
            Button use = widgets.rowButton(skin.selected ? "Retirer" : "Utiliser", theme.accent);
            use.setContentDescription((skin.selected ? "Retirer le skin " : "Utiliser le skin ") + skin.name);
            use.setEnabled(!skinBusy);
            use.setOnClickListener(view -> changeSkins(() -> skins.toggle(skin.id), null));
            row.addView(use, new LinearLayout.LayoutParams(-2, -2));
            Button delete = widgets.rowButton("Supprimer", theme.muted);
            delete.setContentDescription("Supprimer le skin " + skin.name);
            delete.setEnabled(!skinBusy);
            delete.setOnClickListener(view -> confirmSkinDeletion(skin));
            row.addView(delete, new LinearLayout.LayoutParams(-2, -2));
            widgets.add(row, 8);
        }
    }

    private void confirmSkinDeletion(DeltaSkins.Entry skin) {
        if (skinDialog != null && skinDialog.isShowing()) return;
        skinDialog = new AlertDialog.Builder(activity).setTitle("Supprimer ce skin ?")
                .setMessage(skin.name + " sera supprimé de l'appareil. Les commandes classiques reviennent s'il "
                        + "était utilisé.")
                .setPositiveButton("Supprimer", (dialog, which) -> changeSkins(() -> {
                    try {
                        skins.delete(skin.id);
                        return "Skin supprimé";
                    } catch (DeltaSkinException error) {
                        return DeltaSkinException.userMessage(error.code);
                    }
                }))
                .setNegativeButton("Annuler", null).create();
        skinDialog.show();
        skinDialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(theme.accent);
        skinDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(theme.secondary);
    }

    private interface SkinChange { String run(); }

    private void changeSkins(Runnable change, String message) {
        changeSkins(() -> {
            change.run();
            return message;
        });
    }

    /** IO executor: the change, then the list read again and the message shown. */
    private void changeSkins(SkinChange change) {
        if (skinBusy) return;
        skinBusy = true;
        if (INTERFACE_SKINS.equals(activity.page())) activity.showPage(INTERFACE_SKINS);
        activity.io.execute(() -> {
            String message;
            try {
                message = change.run();
            } catch (RuntimeException error) {
                message = DeltaSkinException.userMessage(DeltaSkinException.Code.STORAGE_ERROR);
            }
            reloadSkins(message);
        });
    }

    /** Off the UI thread when called from the IO executor; from the UI thread it queues the read. */
    private void reloadSkins(String message) {
        Runnable read = () -> {
            List<DeltaSkins.Entry> entries;
            try {
                entries = skins.entries();
            } catch (RuntimeException unreadable) {
                entries = Collections.emptyList();
            }
            final List<DeltaSkins.Entry> listed = entries;
            activity.runOnUiThread(() -> {
                if (activity.isDestroyed()) return;
                skinEntries = listed;
                skinsRead = true;
                skinBusy = false;
                skinImporting = false;
                if (message != null) Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
                if (INTERFACE_SKINS.equals(activity.page())) activity.showPage(INTERFACE_SKINS);
            });
        };
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            skinBusy = true;
            activity.io.execute(read);
        } else read.run();
    }

    private void pickSkin() {
        if (skinBusy) return;
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        pick.setType("*/*");
        try {
            activity.startActivityForResult(pick, PICK_SKIN);
        } catch (ActivityNotFoundException missing) {
            Toast.makeText(activity, "Aucune application pour choisir un fichier", Toast.LENGTH_LONG).show();
        }
    }

    /** The .deltaskin picked, imported off the UI thread. */
    void skinPicked(Uri uri) {
        if (skinBusy) return;
        skinImporting = true;
        changeSkins(() -> skins.importSkin(uri));
    }

    void dismiss() {
        if (skinDialog != null) skinDialog.dismiss();
    }
}
