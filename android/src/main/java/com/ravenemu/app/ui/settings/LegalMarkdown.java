package com.ravenemu.app.ui.settings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The small Markdown subset of the repository's TERMS.md and PRIVACY.md,
 * after the beta's LegalMarkdown: headings, paragraphs, "- " bullets,
 * **bold**, `code` and [links](https://…). Anything else stays as plain
 * text. The first title is left out (the reader's bar shows it), and the
 * "Date d'entrée en vigueur" line under it becomes a caption. Free of
 * Android types; immutable results. */
public final class LegalMarkdown {
    private LegalMarkdown() {}

    public static final class Span {
        public final String text;
        public final boolean bold, code;
        /** An http(s) link, or null. */
        public final String url;

        public Span(String text) { this(text, false, false, null); }

        public Span(String text, boolean bold, boolean code, String url) {
            this.text = text;
            this.bold = bold;
            this.code = code;
            this.url = url;
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof Span)) return false;
            Span span = (Span) other;
            return text.equals(span.text) && bold == span.bold && code == span.code && Objects.equals(url, span.url);
        }

        @Override public int hashCode() { return Objects.hash(text, bold, code, url); }

        @Override public String toString() {
            return (bold ? "**" : code ? "`" : "") + text + (url != null ? " <" + url + ">" : "");
        }
    }

    public enum Kind { HEADING, PARAGRAPH, BULLET, CAPTION }

    public static final class Block {
        public final Kind kind;
        /** 1 to 3 for a heading, 0 otherwise. */
        public final int level;
        public final List<Span> spans;

        Block(Kind kind, int level, List<Span> spans) {
            this.kind = kind;
            this.level = level;
            this.spans = Collections.unmodifiableList(spans);
        }

        /** The block's words without their styles. */
        public String text() {
            StringBuilder text = new StringBuilder();
            for (Span span : spans) text.append(span.text);
            return text.toString();
        }
    }

    private static final Pattern TOKEN =
            Pattern.compile("\\*\\*(.+?)\\*\\*|`([^`]+)`|\\[([^\\]]+)]\\((https?://[^)\\s]+)\\)");

    public static List<Block> parse(String text) {
        List<Block> blocks = new ArrayList<>();
        StringBuilder paragraph = new StringBuilder();
        boolean titleSkipped = false;
        for (String raw : text.replace("\r", "").split("\n", -1)) {
            String line = stripEnd(raw);
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.equals("---")) {
                flush(blocks, paragraph);
            } else if (line.startsWith("#")) {
                flush(blocks, paragraph);
                int level = 0;
                while (level < line.length() && line.charAt(level) == '#') level++;
                String title = line.substring(level).trim();
                // The reader shows the document's title in its bar already.
                if (level == 1 && !titleSkipped && blocks.isEmpty()) titleSkipped = true;
                else blocks.add(new Block(Kind.HEADING, Math.max(1, Math.min(3, level)), inline(title)));
            } else if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
                flush(blocks, paragraph);
                blocks.add(new Block(Kind.BULLET, 0, inline(trimmed.substring(2).trim())));
            } else {
                if (paragraph.length() > 0) paragraph.append(' ');
                paragraph.append(trimmed);
            }
        }
        flush(blocks, paragraph);
        return Collections.unmodifiableList(blocks);
    }

    public static List<Span> inline(String text) {
        List<Span> spans = new ArrayList<>();
        Matcher match = TOKEN.matcher(text);
        int index = 0;
        while (match.find()) {
            if (match.start() > index) spans.add(new Span(text.substring(index, match.start())));
            if (match.group(1) != null) spans.add(new Span(match.group(1), true, false, null));
            else if (match.group(2) != null) spans.add(new Span(match.group(2), false, true, null));
            else spans.add(new Span(match.group(3), false, false, match.group(4)));
            index = match.end();
        }
        if (index < text.length()) spans.add(new Span(text.substring(index)));
        return spans;
    }

    private static void flush(List<Block> blocks, StringBuilder paragraph) {
        String content = paragraph.toString().trim();
        paragraph.setLength(0);
        if (content.isEmpty()) return;
        if (blocks.isEmpty() && content.startsWith("Date d")) {
            List<Span> caption = new ArrayList<>();
            caption.add(new Span(content));
            blocks.add(new Block(Kind.CAPTION, 0, caption));
        } else {
            blocks.add(new Block(Kind.PARAGRAPH, 0, inline(content)));
        }
    }

    private static String stripEnd(String line) {
        int end = line.length();
        while (end > 0 && Character.isWhitespace(line.charAt(end - 1))) end--;
        return line.substring(0, end);
    }
}
