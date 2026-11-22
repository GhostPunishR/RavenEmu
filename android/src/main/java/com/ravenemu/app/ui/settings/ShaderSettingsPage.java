package com.ravenemu.app.ui.settings;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import com.ravenemu.app.bridge.NativeShaders;
import com.ravenemu.app.data.shaders.ShaderPacks;
import com.ravenemu.app.platform.DocumentTree;
import com.ravenemu.app.settings.ShaderSettings;
import com.ravenemu.app.ui.common.RavenTheme;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import static com.ravenemu.app.ui.settings.PlaybackSettingsActivity.PICK_SHADER_FOLDER;
import static com.ravenemu.app.ui.settings.PlaybackSettingsActivity.PICK_SHADER_ZIP;
import static com.ravenemu.app.ui.settings.PlaybackSettingsActivity.VIDEO_SHADERS;
import static com.ravenemu.app.ui.settings.PlaybackSettingsActivity.VIDEO_SHADER_PARAMETERS;

/** Settings → Vidéo → Shaders: the RetroArch GLSL presets (.glslp) the
 * player imported, as a .zip or a folder (libretro/glsl-shaders, for one);
 * the one the picture is drawn with, its parameters, and the packs. No shader
 * is bundled. A native addition: the beta had the built-in effects only. */
final class ShaderSettingsPage {
    private final PlaybackSettingsActivity activity;
    private final SettingsWidgets widgets;
    private final RavenTheme theme;
    private final ShaderPacks packs;
    private ShaderSettings settings;
    /** Read on the IO executor; null until then. */
    private List<ShaderPacks.Pack> installed;
    private boolean busy;
    private String importing;
    /** The chosen preset's parameters, read by the native driver. */
    private List<NativeShaders.Parameter> parameters;
    private String parametersOf, parametersError;
    private AlertDialog dialog;

    ShaderSettingsPage(PlaybackSettingsActivity activity, SettingsWidgets widgets) {
        this.activity = activity;
        this.widgets = widgets;
        this.theme = activity.theme();
        this.packs = new ShaderPacks(activity.getFilesDir());
        this.settings = ShaderSettings.load(activity);
    }

    void dismiss() {
        if (dialog != null) dialog.dismiss();
    }

    void shadersPage() {
        widgets.add(widgets.text("Les shaders GLSL de RetroArch (.glslp) dessinent l'image du jeu : écrans CRT, "
                + "grilles LCD, agrandissement xBR ou ScaleFX… Importez un pack, par exemple l'archive .zip du "
                + "dépôt libretro/glsl-shaders, ou un dossier : aucun shader n'est fourni. Les presets .slangp ne "
                + "sont pas pris en charge. Avec un shader, la luminosité, le contraste et l'effet d'écran "
                + "intégré attendent.", 13, theme.muted), 0);
        if (installed == null) {
            widgets.add(widgets.text("Lecture des shaders…", 12, theme.muted), 8);
            if (!busy) reload(null);
            return;
        }
        String chosen = settings.preset.isEmpty() ? "Aucun (effets intégrés)" : settings.preset;
        TextView current = widgets.text("Shader : " + chosen, 14, settings.preset.isEmpty() ? theme.muted : theme.accent);
        current.setTag("shaders.current");
        widgets.add(current, 12);
        Button choose = widgets.textButton("Choisir un preset…", theme.accent);
        choose.setEnabled(!busy && !installed.isEmpty());
        choose.setOnClickListener(view -> chooseFolder());
        if (!settings.preset.isEmpty()) {
            Button none = widgets.textButton("Aucun shader : effets intégrés", theme.accent);
            none.setOnClickListener(view -> select(""));
            widgets.category("Paramètres du shader", "Les réglages que le preset propose",
                    VIDEO_SHADER_PARAMETERS, null);
        }
        widgets.group("Packs importés");
        Button zip = widgets.textButton("Importer un .zip", theme.accent);
        zip.setEnabled(!busy);
        zip.setOnClickListener(view -> pick(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip"), PICK_SHADER_ZIP));
        Button folder = widgets.textButton("Importer un dossier", theme.accent);
        folder.setEnabled(!busy);
        folder.setOnClickListener(view -> pick(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), PICK_SHADER_FOLDER));
        if (importing != null) widgets.add(widgets.text("Importation de " + importing + "…", 12, theme.muted), 4);
        if (installed.isEmpty()) widgets.add(widgets.text("Aucun pack importé.", 12, theme.muted), 8);
        for (ShaderPacks.Pack pack : installed) {
            LinearLayout row = new LinearLayout(activity);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(widgets.dp(56));
            LinearLayout texts = widgets.column();
            texts.addView(widgets.text(pack.id, 14, theme.text));
            texts.addView(widgets.text(pack.presets.size() + (pack.presets.size() > 1 ? " presets" : " preset"),
                    12, theme.muted));
            row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
            Button delete = widgets.rowButton("Supprimer", theme.muted);
            delete.setContentDescription("Supprimer le pack " + pack.id);
            delete.setEnabled(!busy);
            delete.setOnClickListener(view -> confirmDeletion(pack));
            row.addView(delete, new LinearLayout.LayoutParams(-2, -2));
            widgets.add(row, 8);
        }
    }

    void parametersPage() {
        if (settings.preset.isEmpty()) {
            widgets.add(widgets.text("Aucun shader choisi.", 13, theme.muted), 0);
            return;
        }
        widgets.add(widgets.text(settings.preset, 13, theme.muted), 0);
        if (!settings.preset.equals(parametersOf)) {
            widgets.add(widgets.text("Lecture des paramètres…", 12, theme.muted), 8);
            readParameters(settings.preset);
            return;
        }
        if (parametersError != null) {
            widgets.add(widgets.text(parametersError, 12, theme.muted), 8);
            return;
        }
        if (parameters.isEmpty()) widgets.add(widgets.text("Ce preset n'a pas de paramètre.", 12, theme.muted), 8);
        for (NativeShaders.Parameter parameter : parameters) {
            Float chosen = settings.parameters.get(parameter.name);
            float value = chosen != null ? chosen : parameter.initial;
            widgets.floatSlider(parameter.description.isEmpty() ? parameter.name : parameter.description, value,
                    parameter.minimum, parameter.maximum, parameter.step,
                    changed -> settings = settings.withParameter(activity, parameter.name, changed));
        }
        if (!parameters.isEmpty()) {
            Button reset = widgets.textButton("Valeurs du preset", theme.accent);
            reset.setOnClickListener(view -> {
                settings = settings.withoutParameters(activity);
                activity.showPage(VIDEO_SHADER_PARAMETERS);
            });
        }
    }

    /** A pack's folders, then the presets of the one chosen: hundreds of
     * presets would not fit one page. */
    private void chooseFolder() {
        Map<String, List<String>> folders = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (ShaderPacks.Pack pack : installed)
            for (String preset : pack.presets) {
                int slash = preset.lastIndexOf('/');
                String folder = slash > 0 ? preset.substring(0, slash) : pack.id;
                folders.computeIfAbsent(folder, key -> new ArrayList<>()).add(preset);
            }
        final List<String> names = new ArrayList<>(folders.keySet());
        String[] labels = new String[names.size()];
        for (int i = 0; i < labels.length; i++)
            labels[i] = names.get(i) + " (" + folders.get(names.get(i)).size() + ")";
        dialog = new AlertDialog.Builder(activity).setTitle("Dossier du preset")
                .setItems(labels, (shown, which) -> choosePreset(folders.get(names.get(which))))
                .setNegativeButton("Annuler", null).create();
        dialog.show();
    }

    private void choosePreset(List<String> presets) {
        final List<String> sorted = new ArrayList<>(presets);
        Collections.sort(sorted, String.CASE_INSENSITIVE_ORDER);
        String[] labels = new String[sorted.size()];
        for (int i = 0; i < labels.length; i++) {
            String name = sorted.get(i).substring(sorted.get(i).lastIndexOf('/') + 1);
            labels[i] = name.substring(0, name.length() - ".glslp".length());
        }
        dialog = new AlertDialog.Builder(activity).setTitle("Preset")
                .setItems(labels, (shown, which) -> select(sorted.get(which)))
                .setNegativeButton("Annuler", null).create();
        dialog.show();
    }

    private void select(String preset) {
        settings = ShaderSettings.choose(activity, preset);
        parametersOf = null;
        activity.showPage(VIDEO_SHADERS);
    }

    private void confirmDeletion(ShaderPacks.Pack pack) {
        if (dialog != null && dialog.isShowing()) return;
        dialog = new AlertDialog.Builder(activity).setTitle("Supprimer ce pack ?")
                .setMessage(pack.id + " sera supprimé de l'appareil. Si l'un de ses presets dessine l'image, "
                        + "les effets intégrés reviennent.")
                .setPositiveButton("Supprimer", (shown, which) -> change(() -> {
                    packs.deletePack(pack.id);
                    return "Pack supprimé";
                }))
                .setNegativeButton("Annuler", null).create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(theme.accent);
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(theme.secondary);
    }

    private void pick(Intent request, int code) {
        try {
            activity.startActivityForResult(request, code);
        } catch (ActivityNotFoundException missing) {
            Toast.makeText(activity, "Aucune application pour choisir un fichier ou un dossier",
                    Toast.LENGTH_LONG).show();
        }
    }

    /** From onActivityResult: the .zip or the folder chosen. */
    void picked(int request, Uri uri) {
        if (busy) return;
        importing = request == PICK_SHADER_ZIP ? displayName(uri) : "dossier";
        change(() -> {
            try {
                String id;
                if (request == PICK_SHADER_ZIP) {
                    try (InputStream input = activity.getContentResolver().openInputStream(uri)) {
                        if (input == null) throw new IOException("Archive illisible");
                        id = packs.importZip(displayName(uri), input);
                    }
                } else {
                    DocumentTree tree = new DocumentTree(activity.getContentResolver(), uri);
                    id = packs.importFolder(folderName(uri), tree);
                }
                return "Pack importé : " + id;
            } catch (IOException | RuntimeException failure) {
                return failure.getMessage() != null ? failure.getMessage() : "Importation impossible";
            }
        });
    }

    private interface Change { String run(); }

    /** IO executor: the change, the packs read again, the message shown. */
    private void change(Change work) {
        busy = true;
        if (VIDEO_SHADERS.equals(activity.page())) activity.showPage(VIDEO_SHADERS);
        activity.io.execute(() -> reload(work.run()));
    }

    /** From the UI thread it queues the read; from the IO executor it reads. */
    private void reload(String message) {
        Runnable read = () -> {
            List<ShaderPacks.Pack> listed = packs.list();
            // A preset whose pack is gone draws no more.
            final boolean gone = !settings.preset.isEmpty() && !packs.exists(settings.preset);
            activity.runOnUiThread(() -> {
                if (activity.isDestroyed()) return;
                installed = listed;
                busy = false;
                importing = null;
                if (gone) settings = ShaderSettings.choose(activity, "");
                if (message != null) Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
                if (VIDEO_SHADERS.equals(activity.page())) activity.showPage(VIDEO_SHADERS);
            });
        };
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            busy = true;
            activity.io.execute(read);
        } else read.run();
    }

    private void readParameters(String preset) {
        final String folder = packs.root().getAbsolutePath();
        activity.io.execute(() -> {
            List<NativeShaders.Parameter> found = Collections.emptyList();
            String error = null;
            try {
                found = NativeShaders.describe(folder, preset);
            } catch (IOException failure) {
                error = failure.getMessage();
            } catch (LinkageError withoutNativeLibrary) {
                error = "Paramètres indisponibles sur cet appareil";
            }
            final List<NativeShaders.Parameter> read = found;
            final String failure = error;
            activity.runOnUiThread(() -> {
                if (activity.isDestroyed() || !preset.equals(settings.preset)) return;
                parameters = read;
                parametersError = failure;
                parametersOf = preset;
                if (VIDEO_SHADER_PARAMETERS.equals(activity.page())) activity.showPage(VIDEO_SHADER_PARAMETERS);
            });
        });
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = activity.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getString(0);
        } catch (RuntimeException unreadable) {
            // The pack is then named after its last path segment.
        }
        String last = uri.getLastPathSegment();
        return last == null ? "shaders" : last;
    }

    private static String folderName(Uri uri) {
        String last = uri.getLastPathSegment();
        if (last == null) return "shaders";
        int colon = last.lastIndexOf(':'), slash = last.lastIndexOf('/');
        String name = last.substring(Math.max(colon, slash) + 1);
        return name.isEmpty() ? "shaders" : name.toLowerCase(Locale.ROOT).equals("primary") ? "shaders" : name;
    }
}
