package com.ravenemu.shared

/**
 * A document read inside the app (Réglages → À propos): the legal documents
 * of the repository, and the licence texts of the third-party code.
 */
data class ReaderDocument(
    val title: String,
    val text: String,
    /** Markdown (TERMS.md, PRIVACY.md) or plain text kept as is (licences). */
    val markdown: Boolean,
)

object LegalDocuments {
    val terms = ReaderDocument("Conditions d'utilisation", LegalTexts.TERMS, markdown = true)
    val privacy = ReaderDocument("Politique de confidentialité", LegalTexts.PRIVACY, markdown = true)
    val agpl = ReaderDocument("Licence AGPL-3.0", LegalTexts.AGPL, markdown = false)

    fun of(notice: OpenSourceNotice) = ReaderDocument(notice.name, notice.text, markdown = false)
}

/**
 * The small Markdown subset the repository's documents use: headings,
 * paragraphs, "- " bullets, **bold**, `code` and [links](https://…).
 * Anything else stays as plain text. Pure, shared by Android and iOS.
 */
object LegalMarkdown {
    data class Span(val text: String, val bold: Boolean = false, val code: Boolean = false, val url: String? = null)

    sealed interface Block {
        data class Heading(val level: Int, val spans: List<Span>) : Block
        data class Paragraph(val spans: List<Span>) : Block
        data class Bullet(val spans: List<Span>) : Block
        /** The "Date d'entrée en vigueur" line under the title. */
        data class Caption(val text: String) : Block
    }

    fun parse(text: String): List<Block> {
        val blocks = mutableListOf<Block>()
        val paragraph = StringBuilder()
        fun flush() {
            if (paragraph.isNotBlank()) {
                val content = paragraph.toString().trim()
                blocks += if (blocks.isEmpty() && content.startsWith("Date d")) Block.Caption(content)
                    else Block.Paragraph(inline(content))
            }
            paragraph.clear()
        }
        var titleSkipped = false
        for (raw in text.lines()) {
            val line = raw.trimEnd()
            when {
                line.isBlank() || line.trim() == "---" -> flush()
                line.startsWith("#") -> {
                    flush()
                    val level = line.takeWhile { it == '#' }.length
                    val title = line.drop(level).trim()
                    // The reader shows the document title in its bar already.
                    if (level == 1 && !titleSkipped && blocks.isEmpty()) titleSkipped = true
                    else blocks += Block.Heading(level.coerceIn(1, 3), inline(title))
                }
                line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") -> {
                    flush()
                    blocks += Block.Bullet(inline(line.trimStart().drop(2).trim()))
                }
                else -> {
                    if (paragraph.isNotEmpty()) paragraph.append(' ')
                    paragraph.append(line.trim())
                }
            }
        }
        flush()
        return blocks
    }

    private val token = Regex("""\*\*(.+?)\*\*|`([^`]+)`|\[([^\]]+)]\((https?://[^)\s]+)\)""")

    fun inline(text: String): List<Span> {
        val spans = mutableListOf<Span>()
        var index = 0
        for (match in token.findAll(text)) {
            if (match.range.first > index) spans += Span(text.substring(index, match.range.first))
            val (bold, code, label, url) = match.destructured
            spans += when {
                bold.isNotEmpty() -> Span(bold, bold = true)
                code.isNotEmpty() -> Span(code, code = true)
                else -> Span(label, url = url)
            }
            index = match.range.last + 1
        }
        if (index < text.length) spans += Span(text.substring(index))
        return spans
    }
}
