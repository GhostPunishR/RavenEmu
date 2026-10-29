package com.ravenemu.nativepreview;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.Menu;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Java-only adaptation of RavenEmuApp's EXISTING library design.
 * Visual source of truth: shared/theme/RavenColors.kt and
 * shared/library/LibraryComponents.kt, not a new or generic Android UI.
 *
 * The shell uses Android Views; the emulation path remains C/JNI/C++.
 * Never reads the beta package's library or saved data.
 */
public final class NativeHomeActivity extends Activity {
    static final int BACKGROUND = 0xff0b0c12;
    static final int SURFACE = 0xff191b28;
    static final int ACCENT = 0xffa78bfa;
    static final int TEXT = 0xfff5f2ff;
    static final int SECONDARY = 0xffcbc4dc;
    static final int MUTED = 0xffaaa2bc;
    static final int OUTLINE = 0xff3c4052;

    private static final int PICK_ROM = 71;
    private static final int PICK_COVER = 72;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final List<RomLibrary.Entry> all = new ArrayList<>();
    private final List<RomLibrary.Entry> shown = new ArrayList<>();
    private boolean destroyed;
    private String selectedCoverKey;
    private String searchText = "";
    private String selectedPage = "";
    private boolean listMode;

    private TextView heading, footnote;
    private EditText search;
    private LinearLayout tabs;
    private GridView cards;
    private RavenLibraryAdapter adapter;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) {
            searchText = state.getString("library.search", "");
            selectedPage = state.getString("library.page", "");
            listMode = state.getBoolean("library.list");
            selectedCoverKey = state.getString("library.cover");
        }
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        createLibrary();
        reload();
    }

    @Override protected void onResume() {
        super.onResume();
        if (cards != null) reload();
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    static GradientDrawable shape(int color, float radiusDp, float density) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(radiusDp * density);
        return background;
    }

    private TextView text(String value, int size, int color) {
        TextView result = new TextView(this);
        result.setText(value);
        result.setTextSize(size);
        result.setTextColor(color);
        result.setGravity(Gravity.CENTER_VERTICAL);
        return result;
    }

    private TextView iconButton(String symbol, String description, Runnable click) {
        TextView button = text(symbol, 27, ACCENT);
        button.setGravity(Gravity.CENTER);
        button.setContentDescription(description);
        button.setOnClickListener(view -> click.run());
        return button;
    }

    private void createLibrary() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BACKGROUND);

        // RavenEmu 0.8.1: settings left, centered page title, + and ⋮ right.
        FrameLayout toolbar = new FrameLayout(this);
        root.addView(toolbar, new LinearLayout.LayoutParams(-1, dp(48)));

        heading = text("Tous les jeux", 20, TEXT);
        heading.setGravity(Gravity.CENTER);
        heading.setSingleLine(true);
        heading.setEllipsize(android.text.TextUtils.TruncateAt.END);
        heading.setTypeface(null, android.graphics.Typeface.BOLD);
        FrameLayout.LayoutParams center = new FrameLayout.LayoutParams(-1, -1);
        center.leftMargin = dp(100);
        center.rightMargin = dp(102);
        toolbar.addView(heading, center);

        TextView settings = iconButton("⚙", "Paramètres", this::showSettings);
        toolbar.addView(settings, new FrameLayout.LayoutParams(dp(48), -1, Gravity.LEFT));

        LinearLayout actions = new LinearLayout(this);
        FrameLayout.LayoutParams right = new FrameLayout.LayoutParams(-2, -1, Gravity.RIGHT);
        toolbar.addView(actions, right);
        actions.addView(iconButton("+", "Ajouter une ROM", this::pickRom),
                new LinearLayout.LayoutParams(dp(48), -1));
        TextView more = iconButton("⋮", "Plus d'options", () -> {});
        more.setTextColor(TEXT);
        more.setOnClickListener(this::showOptions);
        actions.addView(more, new LinearLayout.LayoutParams(dp(48), -1));

        search = new EditText(this);
        search.setSingleLine(true);
        search.setTextColor(TEXT);
        search.setHintTextColor(MUTED);
        search.setTextSize(15);
        search.setHint("⌕  Rechercher un jeu");
        search.setContentDescription("Rechercher un jeu");
        search.setText(searchText);
        search.setPadding(dp(12), 0, dp(12), 0);
        search.setBackground(shape(SURFACE, 14, getResources().getDisplayMetrics().density));
        LinearLayout.LayoutParams field = new LinearLayout.LayoutParams(-1, dp(44));
        field.setMargins(dp(12), 0, dp(12), dp(8));
        root.addView(search, field);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) {
                searchText = value.toString();
                updateCards();
            }
            @Override public void afterTextChanged(Editable value) {}
        });

        cards = new GridView(this);
        cards.setBackgroundColor(BACKGROUND);
        cards.setVerticalScrollBarEnabled(false);
        cards.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        cards.setGravity(Gravity.CENTER_HORIZONTAL);
        cards.setHorizontalSpacing(dp(8));
        cards.setVerticalSpacing(dp(6));
        adapter = new RavenLibraryAdapter(this, getFilesDir(), io);
        cards.setAdapter(adapter);
        cards.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < shown.size()) showDetails(shown.get(position));
        });
        root.addView(cards, new LinearLayout.LayoutParams(-1, 0, 1));

        tabs = new LinearLayout(this);
        tabs.setGravity(Gravity.CENTER);
        root.addView(tabs, new LinearLayout.LayoutParams(-1, dp(40)));

        footnote = text("RavenEmu By GhostPunishR", 10, MUTED);
        footnote.setLetterSpacing(.08f);
        footnote.setGravity(Gravity.CENTER);
        root.addView(footnote, new LinearLayout.LayoutParams(-1, dp(28)));

        // Reserve actual status bar, cutout, nav buttons and keyboard space.
        // Android 15 edge-to-edge would otherwise overlay the toolbar/footer.
        SystemBarInsets.install(this, root);
        setContentView(root);
        updateCards();
    }

    private void reload() {
        if (destroyed) return;
        io.execute(() -> {
            try {
                List<RomLibrary.Entry> loaded = RomLibrary.list(getFilesDir());
                runOnUiThread(() -> {
                    if (destroyed) return;
                    all.clear();
                    all.addAll(loaded);
                    updatePages();
                    updateCards();
                });
            } catch (Exception failure) {
                status("Bibliothèque indisponible : " + failure.getMessage());
            }
        });
    }

    private void updatePages() {
        List<String> populated = new ArrayList<>();
        for (String system : new String[]{"gb", "gbc", "gba"}) {
            for (RomLibrary.Entry entry : all) {
                if (entry.system.equals(system)) {
                    populated.add(system);
                    break;
                }
            }
        }
        List<String> pages = new ArrayList<>();
        if (populated.size() != 1) pages.add("");
        pages.addAll(populated);
        if (pages.isEmpty()) pages.add("");
        if (!pages.contains(selectedPage)) selectedPage = pages.get(0);
        heading.setText(pageTitle(selectedPage));

        tabs.removeAllViews();
        if (pages.size() <= 1) return;
        for (String page : pages) {
            TextView option = text(page.isEmpty() ? "●" : page.toUpperCase(Locale.ROOT),
                    13, page.equals(selectedPage) ? ACCENT : MUTED);
            option.setGravity(Gravity.CENTER);
            option.setContentDescription(pageTitle(page));
            option.setTypeface(null, page.equals(selectedPage)
                    ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            option.setBackground(shape(page.equals(selectedPage)
                    ? 0xff28213f : BACKGROUND, 10, getResources().getDisplayMetrics().density));
            LinearLayout.LayoutParams parameter = new LinearLayout.LayoutParams(dp(54), dp(36));
            parameter.setMargins(dp(4), 0, dp(4), 0);
            tabs.addView(option, parameter);
            option.setOnClickListener(view -> {
                selectedPage = page;
                updatePages();
                updateCards();
            });
        }
    }

    private String pageTitle(String system) {
        switch (system) {
            case "gb": return "Game Boy";
            case "gbc": return "Game Boy Color";
            case "gba": return "Game Boy Advance";
            default: return "Tous les jeux";
        }
    }

    private void updateCards() {
        if (cards == null) return;
        shown.clear();
        shown.addAll(RomLibrary.query(all, searchText, selectedPage,
                RomLibrary.SortOrder.TITLE_ASC));
        cards.setNumColumns(listMode ? 1 : GridView.AUTO_FIT);
        cards.setColumnWidth(dp(listMode ? 280 : 106));
        cards.setHorizontalSpacing(dp(listMode ? 0 : 8));
        adapter.setListMode(listMode);
        adapter.replace(shown);
    }

    private void showOptions(View anchor) {
        PopupMenu options = new PopupMenu(this, anchor);
        options.getMenu().add(listMode ? "Vue grille" : "Vue liste").setOnMenuItemClickListener(item -> {
            listMode = !listMode;
            updateCards();
            return true;
        });
        options.getMenu().add("Actualiser la bibliothèque de jeux")
                .setOnMenuItemClickListener(item -> { reload(); return true; });
        options.show();
    }

    private void showDetails(RomLibrary.Entry entry) {
        float density = getResources().getDisplayMetrics().density;
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20), dp(12), dp(20), dp(16));
        panel.setBackgroundColor(SURFACE);

        TextView title = text(entry.title, 18, TEXT);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        panel.addView(title);
        panel.addView(text(pageTitle(entry.system), 13, SECONDARY));

        LinearLayout coverRow = new LinearLayout(this);
        coverRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams spacing = new LinearLayout.LayoutParams(-1, -2);
        spacing.topMargin = dp(12);
        panel.addView(coverRow, spacing);

        android.widget.ImageView cover = new android.widget.ImageView(this);
        cover.setBackground(shape(OUTLINE, 10, density));
        cover.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        coverRow.addView(cover, new LinearLayout.LayoutParams(dp(90), dp(105)));
        io.execute(() -> {
            try {
                android.graphics.Bitmap image = CoverRepository.load(getFilesDir(), entry.key, 160);
                if (image != null) runOnUiThread(() -> {
                    if (!destroyed) cover.setImageBitmap(image);
                });
            } catch (IOException ignored) {}
        });

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(dp(14), 0, 0, 0);
        coverRow.addView(controls, new LinearLayout.LayoutParams(0, -2, 1));
        controls.addView(text("Jaquette enregistrée localement ou facultative", 12, MUTED));
        Button changeCover = action("Image manuelle (facultatif)");
        controls.addView(changeCover);
        TextView removeCover = text("Retirer la jaquette", 12, ACCENT);
        removeCover.setPadding(dp(8), dp(8), 0, dp(8));
        controls.addView(removeCover);

        Button play = action("Jouer");
        play.setTextColor(BACKGROUND);
        play.setBackground(shape(ACCENT, 12, density));
        LinearLayout.LayoutParams playParams = new LinearLayout.LayoutParams(-1, dp(48));
        playParams.topMargin = dp(16);
        panel.addView(play, playParams);

        // Keep every action reachable in landscape and with large system text.
        ScrollView scroll = new ScrollView(this);
        scroll.addView(panel);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(scroll)
                .setNegativeButton("Fermer", null)
                .create();
        play.setOnClickListener(view -> {
            dialog.dismiss();
            startActivity(new Intent(this, MainActivity.class)
                    .putExtra(MainActivity.EXTRA_ROM_KEY, entry.key));
        });
        changeCover.setOnClickListener(view -> {
            dialog.dismiss();
            selectedCoverKey = entry.key;
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            pick.addCategory(Intent.CATEGORY_OPENABLE);
            pick.setType("image/*");
            startActivityForResult(pick, PICK_COVER);
        });
        removeCover.setOnClickListener(view -> {
            dialog.dismiss();
            io.execute(() -> {
                CoverRepository.remove(getFilesDir(), entry.key);
                runOnUiThread(() -> {
                    adapter.invalidateCover(entry.key);
                    status("Jaquette retirée");
                });
            });
        });
        dialog.show();
    }

    private Button action(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(13);
        button.setAllCaps(false);
        return button;
    }

    private void showSettings() {
        new AlertDialog.Builder(this)
                .setTitle("Paramètres")
                .setItems(new String[]{"Général", "Audio", "Vidéo", "Console",
                        "Interface", "À propos"}, (dialog, index) -> {
                    String[] sections = {"Général", "Audio", "Vidéo", "Console",
                            "Interface", "À propos"};
                    new AlertDialog.Builder(this)
                        .setTitle(sections[index])
                        .setMessage(index == 5
                          ? "RavenEmu By GhostPunishR\nVersion native expérimentale Java / C / C++"
                          : "Les paramètres de cette section seront activés après leur "
                              + "raccordement aux pilotes natifs. "
                              + "Aucune préférence incompatible n'est enregistrée.")
                        .setPositiveButton("Retour", null).show();
                })
                .setNegativeButton("Fermer", null).show();
    }

    private void pickRom() {
        Intent select = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        select.addCategory(Intent.CATEGORY_OPENABLE);
        select.setType("*/*");
        startActivityForResult(select, PICK_ROM);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) {
            if (request == PICK_COVER) selectedCoverKey = null;
            return;
        }
        Uri uri = data.getData();
        if (request == PICK_ROM) {
            io.execute(() -> {
                try {
                    String name = displayName(uri);
                    RomLibrary.importRom(getFilesDir(), name,
                            readBounded(uri, RomLibrary.MAX_ROM_BYTES));
                    status("Jeu importé : " + RomLibrary.safeTitle(name));
                    runOnUiThread(this::reload);
                } catch (Exception error) {
                    status("Import impossible : " + error.getMessage());
                }
            });
        } else if (request == PICK_COVER) {
            String key = selectedCoverKey;
            selectedCoverKey = null;
            if (key == null) return;
            io.execute(() -> {
                try {
                    boolean found = false;
                    for (RomLibrary.Entry entry : RomLibrary.list(getFilesDir())) {
                        if (entry.key.equals(key)) { found = true; break; }
                    }
                    if (!found) throw new IOException("Jeu introuvable");
                    CoverRepository.importImage(getFilesDir(), key,
                            readBounded(uri, CoverRepository.MAX_SOURCE_BYTES));
                    runOnUiThread(() -> {
                        if (destroyed) return;
                        adapter.invalidateCover(key);
                        status("Jaquette enregistrée");
                    });
                } catch (Exception error) {
                    status("Jaquette invalide : " + error.getMessage());
                }
            });
        }
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0))
                return cursor.getString(0);
        } catch (Exception ignored) {}
        return uri.getLastPathSegment() == null ? "jeu.gb" : uri.getLastPathSegment();
    }

    private byte[] readBounded(Uri uri, int maximum) throws IOException {
        try (InputStream input = getContentResolver().openInputStream(uri);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) throw new IOException("Document introuvable");
            byte[] buffer = new byte[32 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() > maximum - count)
                    throw new IOException("Fichier trop volumineux");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private void status(String message) {
        runOnUiThread(() -> {
            if (destroyed) return;
            android.widget.Toast.makeText(this, message,
                    android.widget.Toast.LENGTH_LONG).show();
        });
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("library.search", searchText);
        state.putString("library.page", selectedPage);
        state.putBoolean("library.list", listMode);
        state.putString("library.cover", selectedCoverKey);
    }

    @Override protected void onDestroy() {
        destroyed = true;
        io.shutdown();
        super.onDestroy();
    }
}
