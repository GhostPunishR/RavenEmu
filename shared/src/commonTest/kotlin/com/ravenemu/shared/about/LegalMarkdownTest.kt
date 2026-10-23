package com.ravenemu.shared.about

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LegalMarkdownTest {
    private val sample = """
        # Politique

        Date d’entrée en vigueur : 13 octobre 2026

        ## Champ d’application

        Première ligne
        suite du paragraphe avec **gras** et `code`.

        - une puce vers [le site](https://example.org/page) ;
        - une autre.
    """.trimIndent()

    @Test
    fun titleIsSkippedAndTheDateBecomesACaption() {
        val blocks = LegalMarkdown.parse(sample)
        assertEquals(LegalMarkdown.Block.Caption("Date d’entrée en vigueur : 13 octobre 2026"), blocks[0])
        assertEquals(LegalMarkdown.Block.Heading(2, listOf(LegalMarkdown.Span("Champ d’application"))), blocks[1])
    }

    @Test
    fun paragraphLinesJoinAndInlineStylesSplit() {
        val paragraph = assertIs<LegalMarkdown.Block.Paragraph>(LegalMarkdown.parse(sample)[2])
        assertEquals(
            listOf(
                LegalMarkdown.Span("Première ligne suite du paragraphe avec "),
                LegalMarkdown.Span("gras", bold = true),
                LegalMarkdown.Span(" et "),
                LegalMarkdown.Span("code", code = true),
                LegalMarkdown.Span("."),
            ),
            paragraph.spans,
        )
    }

    @Test
    fun bulletsKeepTheirLinks() {
        val bullets = LegalMarkdown.parse(sample).filterIsInstance<LegalMarkdown.Block.Bullet>()
        assertEquals(2, bullets.size)
        assertTrue(bullets[0].spans.any { it.url == "https://example.org/page" && it.text == "le site" })
    }

    @Test
    fun theRepositoryDocumentsAreEmbeddedAndParse() {
        assertTrue(LegalTexts.TERMS.startsWith("# Conditions d'utilisation de RavenEmu"))
        assertTrue(LegalTexts.PRIVACY.startsWith("# Politique de confidentialité de RavenEmu"))
        assertTrue("GNU AFFERO GENERAL PUBLIC LICENSE" in LegalTexts.AGPL)
        for (document in listOf(LegalDocuments.terms, LegalDocuments.privacy)) {
            val blocks = LegalMarkdown.parse(document.text)
            assertTrue(blocks.count { it is LegalMarkdown.Block.Heading } >= 5, document.title)
        }
    }
}
