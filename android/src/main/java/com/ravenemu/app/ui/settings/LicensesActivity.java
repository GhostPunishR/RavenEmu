package com.ravenemu.app.ui.settings;
import com.ravenemu.app.BuildConfig;
import com.ravenemu.app.R;
import com.ravenemu.app.platform.SystemBarInsets;
import com.ravenemu.app.ui.common.RavenIcon;
import com.ravenemu.app.ui.library.HomeActivity;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.Selection;
import android.text.Spannable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Local, selectable legal notices. Loading a long licence never blocks UI. */
public final class LicensesActivity extends Activity {
    private static final String[] ASSETS = {
            "NOTICE.txt", "GPL-3.0.txt", "AGPL-3.0.txt", "MIT-LIBRETRO.txt", "MIT-RCHEEVOS.txt", "APACHE-2.0.txt"
    };
    private static final String[] LABELS = {"Crédits", "GPLv3", "AGPLv3", "MIT", "MIT rcheevos", "Apache 2"};
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Button[] choices = new Button[ASSETS.length];
    private TextView body, documentTitle;
    private ScrollView documentScroll;
    private int selectedDocument, requestSerial;
    private int restoredStart = -1, restoredEnd = -1, restoredScroll;
    private boolean destroyed;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) {
            selectedDocument = Math.max(0, Math.min(ASSETS.length - 1, state.getInt("legal.document")));
            restoredStart = state.getInt("legal.selection.start", -1);
            restoredEnd = state.getInt("legal.selection.end", -1);
            restoredScroll = state.getInt("legal.scroll");
        }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(HomeActivity.BACKGROUND);

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        View back = RavenIcon.button(this, RavenIcon.Glyph.BACK, HomeActivity.ACCENT, 24, "Retour aux paramètres");
        back.setOnClickListener(view -> finish());
        toolbar.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView heading = text("Licences et crédits", 19, HomeActivity.TEXT);
        heading.setTypeface(null, Typeface.BOLD);
        toolbar.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(toolbar, new LinearLayout.LayoutParams(-1, -2));

        TextView revision = text(BuildConfig.SOURCE_MODIFIED
                ? "Version locale modifiée · base " + shortRevision()
                : "Révision " + shortRevision(), 12, HomeActivity.MUTED);
        revision.setTextIsSelectable(true);
        revision.setPadding(dp(16), dp(8), dp(16), dp(8));
        root.addView(revision, new LinearLayout.LayoutParams(-1, -2));

        Button sources = action(BuildConfig.SOURCE_MODIFIED
                ? "Sources de la révision de base" : "Sources de cette version");
        sources.setContentDescription("Consulter le code source");
        Uri source = sourceUri();
        sources.setEnabled(source != null);
        sources.setOnClickListener(view -> {
            try { startActivity(new Intent(Intent.ACTION_VIEW, source)); }
            catch (ActivityNotFoundException error) {
                Toast.makeText(this, "Aucune application pour ouvrir ce lien", Toast.LENGTH_LONG).show();
            }
        });
        LinearLayout.LayoutParams sourceSize = new LinearLayout.LayoutParams(-1, -2);
        sourceSize.setMargins(dp(12), 0, dp(12), dp(8));
        root.addView(sources, sourceSize);

        HorizontalScrollView tabs = new HorizontalScrollView(this);
        LinearLayout row = new LinearLayout(this);
        row.setPadding(dp(12), 0, dp(12), dp(8));
        for (int i = 0; i < choices.length; i++) {
            final int document = i;
            Button choice = action(LABELS[i]);
            choice.setContentDescription("Afficher " + LABELS[i]);
            choice.setOnClickListener(view -> {
                restoredStart = restoredEnd = -1;
                restoredScroll = 0;
                selectDocument(document);
            });
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-2, -2);
            if (i > 0) size.leftMargin = dp(8);
            row.addView(choice, size);
            choices[i] = choice;
        }
        tabs.addView(row);
        root.addView(tabs, new LinearLayout.LayoutParams(-1, -2));
        documentTitle = text("", 16, HomeActivity.TEXT);
        documentTitle.setTypeface(null, Typeface.BOLD);
        documentTitle.setPadding(dp(16), dp(8), dp(16), dp(8));
        root.addView(documentTitle);
        documentScroll = new ScrollView(this);
        body = text("", 13, HomeActivity.TEXT);
        body.setId(android.R.id.text1);
        body.setTextIsSelectable(true);
        body.setPadding(dp(16), dp(8), dp(16), dp(24));
        documentScroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        root.addView(documentScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        SystemBarInsets.install(this, root);
        setContentView(root);
        selectDocument(selectedDocument);
    }

    private void selectDocument(int document) {
        if (destroyed) return;
        selectedDocument = document;
        final int request = ++requestSerial;
        documentTitle.setText(LABELS[document]);
        for (int i = 0; i < choices.length; i++) {
            boolean selected = i == document;
            choices[i].setSelected(selected);
            choices[i].setTextColor(selected ? HomeActivity.BACKGROUND : HomeActivity.TEXT);
            choices[i].setBackground(background(selected ? HomeActivity.ACCENT : HomeActivity.SURFACE));
        }
        body.setText("Chargement…");
        io.execute(() -> {
            String content;
            try { content = readAsset(ASSETS[document]); }
            catch (IOException error) { content = "Ce texte de licence est indisponible."; }
            final String loaded = content;
            runOnUiThread(() -> {
                if (destroyed || request != requestSerial) return;
                body.setText(loaded, TextView.BufferType.SPANNABLE);
                if (restoredStart >= 0 && restoredEnd >= 0 &&
                        restoredStart <= loaded.length() && restoredEnd <= loaded.length())
                    Selection.setSelection((Spannable)body.getText(), restoredStart, restoredEnd);
                restoredStart = restoredEnd = -1;
                final int scroll = restoredScroll;
                restoredScroll = 0;
                documentScroll.post(() -> {
                    if (!destroyed && request == requestSerial) documentScroll.scrollTo(0, scroll);
                });
            });
        });
    }

    private String readAsset(String name) throws IOException {
        try (InputStream input = getAssets().open("licenses/" + name);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() > 1024 * 1024 - count) throw new IOException("Licence trop volumineuse");
                output.write(buffer, 0, count);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static Uri sourceUri() {
        return BuildConfig.SOURCE_REVISION.matches("[a-fA-F0-9]{40}")
                ? Uri.parse("https://github.com/GhostPunishR/RavenEmu/tree/" + BuildConfig.SOURCE_REVISION) : null;
    }

    private static String shortRevision() {
        return BuildConfig.SOURCE_REVISION.length() >= 7
                ? BuildConfig.SOURCE_REVISION.substring(0, 7) : "indisponible";
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value); view.setTextSize(size); view.setTextColor(color);
        return view;
    }

    private Button action(String title) {
        Button button = new Button(this);
        button.setText(title); button.setAllCaps(false); button.setTextSize(13);
        button.setTextColor(HomeActivity.TEXT); button.setMinHeight(dp(48));
        button.setBackground(background(HomeActivity.SURFACE));
        return button;
    }

    private GradientDrawable background(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color); drawable.setCornerRadius(dp(10));
        drawable.setStroke(dp(1), HomeActivity.OUTLINE);
        return drawable;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putInt("legal.document", selectedDocument);
        state.putInt("legal.selection.start", body.getSelectionStart());
        state.putInt("legal.selection.end", body.getSelectionEnd());
        state.putInt("legal.scroll", documentScroll.getScrollY());
    }

    @Override protected void onDestroy() {
        destroyed = true;
        io.shutdown();
        super.onDestroy();
    }
}
