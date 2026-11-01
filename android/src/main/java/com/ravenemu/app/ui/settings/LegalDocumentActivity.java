package com.ravenemu.app.ui.settings;

import com.ravenemu.app.platform.SystemBarInsets;
import com.ravenemu.app.ui.common.RavenIcon;
import com.ravenemu.app.ui.common.RavenTheme;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.method.LinkMovementMethod;
import android.text.style.BackgroundColorSpan;
import android.text.style.ClickableSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The beta's DocumentReader for the repository's TERMS.md and PRIVACY.md,
 * read inside the app (Settings → À propos): headings, spaced paragraphs,
 * bullets and working links, in the theme's colours. Gradle copies both
 * files into the APK's assets at build time (android/build.gradle.kts), so
 * the app shows what GitHub publishes, never a hand-kept copy. */
public final class LegalDocumentActivity extends Activity implements RavenTheme.Host {
    public static final String EXTRA_DOCUMENT = "com.ravenemu.app.LEGAL_DOCUMENT";
    public static final String TERMS = "terms";
    public static final String PRIVACY = "privacy";
    private static final int MAX_BYTES = 1024 * 1024;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private RavenTheme theme;
    private LinearLayout content;
    private ScrollView scroll;
    private int restoredScroll;
    private boolean destroyed;

    @Override public RavenTheme theme() { return theme; }

    /** The asset and the title of a document, or null for an unknown one. */
    static String[] document(String name) {
        if (TERMS.equals(name)) return new String[]{"legal/TERMS.md", "Conditions d'utilisation"};
        if (PRIVACY.equals(name)) return new String[]{"legal/PRIVACY.md", "Politique de confidentialité"};
        return null;
    }

    @Override public void onCreate(Bundle state) {
        theme = RavenTheme.apply(this);
        super.onCreate(state);
        if (state != null) restoredScroll = Math.max(0, state.getInt("legal.reader.scroll"));
        final String[] document = document(getIntent().getStringExtra(EXTRA_DOCUMENT));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(theme.background);
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setBackgroundColor(theme.surface);
        View back = RavenIcon.button(this, RavenIcon.Glyph.BACK, theme.accent, 24, "Retour aux paramètres");
        back.setOnClickListener(view -> finish());
        toolbar.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView title = text(document != null ? document[1] : "Document", 19, theme.text);
        title.setTypeface(null, Typeface.BOLD);
        toolbar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(toolbar, new LinearLayout.LayoutParams(-1, dp(54)));

        scroll = new ScrollView(this);
        scroll.setTag("legal.reader.scroll");
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(12), dp(18), dp(28));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        SystemBarInsets.install(this, root);
        setContentView(root);

        content.addView(text("Chargement…", 14, theme.muted));
        if (document == null) {
            show(null);
            return;
        }
        io.execute(() -> {
            String text;
            try { text = readAsset(document[0]); }
            catch (IOException unreadable) { text = null; }
            final List<LegalMarkdown.Block> blocks = text == null ? null : LegalMarkdown.parse(text);
            runOnUiThread(() -> { if (!destroyed) show(blocks); });
        });
    }

    private void show(List<LegalMarkdown.Block> blocks) {
        content.removeAllViews();
        if (blocks == null || blocks.isEmpty()) {
            content.addView(text("Ce document est indisponible.", 14, theme.muted));
            return;
        }
        for (LegalMarkdown.Block block : blocks) {
            switch (block.kind) {
                case CAPTION:
                    add(text(block.text(), 12, theme.muted), 0);
                    break;
                case HEADING: {
                    boolean major = block.level <= 2;
                    TextView heading = styled(block.spans, major ? 17 : 15, major ? theme.accent : theme.text);
                    heading.setTypeface(null, Typeface.BOLD);
                    if (Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
                    add(heading, 22);
                    break;
                }
                case BULLET: {
                    LinearLayout row = new LinearLayout(this);
                    TextView mark = text("•", 14, theme.accent);
                    mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                    row.addView(mark, new LinearLayout.LayoutParams(dp(18), -2));
                    row.addView(paragraph(block.spans), new LinearLayout.LayoutParams(0, -2, 1));
                    add(row, 12);
                    break;
                }
                default:
                    add(paragraph(block.spans), 12);
            }
        }
        final int position = restoredScroll;
        restoredScroll = 0;
        scroll.post(() -> { if (!destroyed) scroll.scrollTo(0, position); });
    }

    private TextView paragraph(List<LegalMarkdown.Span> spans) {
        TextView view = styled(spans, 14, theme.text);
        // The beta's 21 sp lines for 14 sp text.
        int line = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 21,
                getResources().getDisplayMetrics()));
        if (Build.VERSION.SDK_INT >= 28) view.setLineHeight(line);
        else view.setLineSpacing(0, 1.28f);
        return view;
    }

    private TextView styled(List<LegalMarkdown.Span> spans, int size, int color) {
        SpannableStringBuilder text = new SpannableStringBuilder();
        boolean links = false;
        for (LegalMarkdown.Span span : spans) {
            int start = text.length();
            text.append(span.text);
            int end = text.length();
            if (span.url != null) {
                text.setSpan(new Link(span.url), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                links = true;
            } else if (span.code) {
                text.setSpan(new TypefaceSpan("monospace"), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                text.setSpan(new BackgroundColorSpan(theme.raised), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (span.bold) {
                text.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        TextView view = text("", size, color);
        view.setText(text);
        if (links) view.setMovementMethod(LinkMovementMethod.getInstance());
        return view;
    }

    /** A link in the accent, underlined; without a browser, a message. */
    private final class Link extends ClickableSpan {
        final String url;

        Link(String url) { this.url = url; }

        @Override public void onClick(View widget) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (ActivityNotFoundException missing) {
                Toast.makeText(LegalDocumentActivity.this, "Aucune application pour ouvrir ce lien",
                        Toast.LENGTH_LONG).show();
            }
        }

        @Override public void updateDrawState(TextPaint paint) {
            paint.setColor(theme.accent);
            paint.setUnderlineText(true);
        }
    }

    private String readAsset(String name) throws IOException {
        try (InputStream input = getAssets().open(name); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() > MAX_BYTES - count) throw new IOException("Document trop volumineux");
                output.write(buffer, 0, count);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private void add(View view, int top) {
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, -2);
        size.topMargin = content.getChildCount() == 0 ? 0 : dp(top);
        content.addView(view, size);
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putInt("legal.reader.scroll", scroll.getScrollY());
    }

    @Override protected void onDestroy() {
        destroyed = true;
        io.shutdown();
        super.onDestroy();
    }
}
