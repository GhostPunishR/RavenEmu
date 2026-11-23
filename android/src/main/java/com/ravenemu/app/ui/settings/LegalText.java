package com.ravenemu.app.ui.settings;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.BackgroundColorSpan;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.view.View;
import android.widget.Toast;
import com.ravenemu.app.ui.common.RavenTheme;
import java.util.List;

/** Settings → À propos → Licences et crédits: a licence or the credits, read
 * from their Markdown (LegalMarkdown), as one selectable text. Headings in
 * the accent, "•" bullets with their wrapped lines aligned, code in
 * monospace and links that open in the browser. */
final class LegalText {
    private LegalText() {}

    static SpannableStringBuilder render(Activity activity, List<LegalMarkdown.Block> blocks, RavenTheme theme) {
        SpannableStringBuilder text = new SpannableStringBuilder();
        LegalMarkdown.Kind previous = null;
        int indent = Math.round(16 * activity.getResources().getDisplayMetrics().density);
        for (LegalMarkdown.Block block : blocks) {
            if (previous != null)
                text.append(previous == LegalMarkdown.Kind.BULLET && block.kind == LegalMarkdown.Kind.BULLET ? "\n" : "\n\n");
            int start = text.length();
            if (block.kind == LegalMarkdown.Kind.BULLET) {
                text.append("•  ");
                text.setSpan(new ForegroundColorSpan(theme.accent), start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            append(activity, text, block.spans, theme);
            int end = text.length();
            switch (block.kind) {
                case HEADING: {
                    boolean major = block.level <= 2;
                    text.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    text.setSpan(new RelativeSizeSpan(major ? 1.22f : 1.08f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    if (major)
                        text.setSpan(new ForegroundColorSpan(theme.accent), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    break;
                }
                case CAPTION:
                    text.setSpan(new ForegroundColorSpan(theme.muted), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    break;
                case BULLET:
                    // The wrapped lines start under the text, not under the bullet.
                    text.setSpan(new LeadingMarginSpan.Standard(0, indent), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    break;
                default:
                    break;
            }
            previous = block.kind;
        }
        return text;
    }

    /** Whether the text holds a link: its view then needs LinkMovementMethod. */
    static boolean hasLinks(Spanned text) {
        return text.getSpans(0, text.length(), ClickableSpan.class).length > 0;
    }

    private static void append(Activity activity, SpannableStringBuilder text, List<LegalMarkdown.Span> spans,
                               RavenTheme theme) {
        for (LegalMarkdown.Span span : spans) {
            int start = text.length();
            text.append(span.text);
            int end = text.length();
            if (span.url != null) {
                text.setSpan(new Link(activity, span.url, theme.accent), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (span.code) {
                text.setSpan(new TypefaceSpan("monospace"), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                text.setSpan(new BackgroundColorSpan(theme.raised), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (span.bold) {
                text.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
    }

    /** A link in the accent, underlined; without a browser, a message. */
    static final class Link extends ClickableSpan {
        final String url;
        private final Activity activity;
        private final int color;

        Link(Activity activity, String url, int color) {
            this.activity = activity;
            this.url = url;
            this.color = color;
        }

        @Override public void onClick(View widget) {
            try {
                activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (ActivityNotFoundException missing) {
                Toast.makeText(activity, "Aucune application pour ouvrir ce lien", Toast.LENGTH_LONG).show();
            }
        }

        @Override public void updateDrawState(TextPaint paint) {
            paint.setColor(color);
            paint.setUnderlineText(true);
        }
    }
}
