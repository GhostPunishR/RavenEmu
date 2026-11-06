package com.ravenemu.app.ui.settings;

import com.ravenemu.app.ui.settings.LegalMarkdown.Block;
import com.ravenemu.app.ui.settings.LegalMarkdown.Kind;
import com.ravenemu.app.ui.settings.LegalMarkdown.Span;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** After the beta's LegalMarkdownTest; plain JVM. */
public final class LegalMarkdownTest {
    private static final String SAMPLE = String.join("\n",
            "# Politique",
            "",
            "Date d’entrée en vigueur : 13 octobre 2026",
            "",
            "## Champ d’application",
            "",
            "Première ligne",
            "suite du paragraphe avec **gras** et `code`.",
            "",
            "- une puce vers [le site](https://example.org/page) ;",
            "- une autre.",
            "",
            "---",
            "### Détail",
            "Un lien [sans adresse web](mailto:x@y.z) reste du texte.");

    @Test public void theTitleIsSkippedAndTheDateBecomesACaption() {
        List<Block> blocks = LegalMarkdown.parse(SAMPLE);
        assertEquals(Kind.CAPTION, blocks.get(0).kind);
        assertEquals("Date d’entrée en vigueur : 13 octobre 2026", blocks.get(0).text());
        assertEquals(Kind.HEADING, blocks.get(1).kind);
        assertEquals(2, blocks.get(1).level);
        assertEquals(Arrays.asList(new Span("Champ d’application")), blocks.get(1).spans);
    }

    @Test public void paragraphLinesJoinAndInlineStylesSplit() {
        Block paragraph = LegalMarkdown.parse(SAMPLE).get(2);
        assertEquals(Kind.PARAGRAPH, paragraph.kind);
        assertEquals(Arrays.asList(
                new Span("Première ligne suite du paragraphe avec "),
                new Span("gras", true, false, null),
                new Span(" et "),
                new Span("code", false, true, null),
                new Span(".")), paragraph.spans);
    }

    @Test public void bulletsKeepTheirLinksAndOnlyWebLinksAreLinks() {
        List<Block> blocks = LegalMarkdown.parse(SAMPLE);
        long bullets = blocks.stream().filter(block -> block.kind == Kind.BULLET).count();
        assertEquals(2, bullets);
        assertTrue(blocks.get(3).spans.contains(new Span("le site", false, false, "https://example.org/page")));
        Block last = blocks.get(blocks.size() - 1);
        assertEquals(Kind.PARAGRAPH, last.kind);
        assertTrue(last.spans.stream().allMatch(span -> span.url == null));
        assertEquals("Un lien [sans adresse web](mailto:x@y.z) reste du texte.", last.text());
        Block detail = blocks.get(blocks.size() - 2);
        assertEquals(Kind.HEADING, detail.kind);
        assertEquals(3, detail.level);
    }

    @Test public void aSecondTitleIsKeptAsAHeadingAndLevelsStopAtThree() {
        List<Block> blocks = LegalMarkdown.parse("# Un\n\n# Deux\n\n#### Quatre\r\n");
        assertEquals(2, blocks.size());
        assertEquals(1, blocks.get(0).level);
        assertEquals("Deux", blocks.get(0).text());
        assertEquals(3, blocks.get(1).level);
        assertTrue(LegalMarkdown.parse("").isEmpty());
    }

    @Test public void theRepositoryDocumentsParse() throws IOException {
        String terms = read("TERMS.md"), privacy = read("PRIVACY.md");
        assertTrue(terms.startsWith("# Conditions d'utilisation de RavenEmu"));
        assertTrue(privacy.startsWith("# Politique de confidentialité de RavenEmu"));
        for (String document : new String[]{terms, privacy}) {
            List<Block> blocks = LegalMarkdown.parse(document);
            assertTrue(blocks.stream().filter(block -> block.kind == Kind.HEADING).count() >= 5);
            assertTrue("No raw Markdown left in a heading",
                    blocks.stream().noneMatch(block -> block.kind == Kind.HEADING && block.text().startsWith("#")));
        }
    }

    /** The repository's file, from the module or from the repository root. */
    static String read(String name) throws IOException {
        for (File directory = new File(System.getProperty("user.dir")).getAbsoluteFile(); directory != null;
             directory = directory.getParentFile()) {
            File file = new File(directory, name);
            if (file.isFile()) return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        }
        throw new IOException(name + " introuvable");
    }
}
