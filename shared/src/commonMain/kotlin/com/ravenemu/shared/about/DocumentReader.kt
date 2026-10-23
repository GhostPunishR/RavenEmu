package com.ravenemu.shared.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val ReaderText = Color(0xFFE9E5F5)
private val ReaderMuted = Color(0xFFAAA2BC)
private val ReaderAccent = Color(0xFFA78BFA)
private val CodeBackground = Color(0xFF26283A)

/**
 * A legal document or licence, read inside the app: Markdown documents get
 * headings, spaced paragraphs, bullets and working links; licence texts are
 * shown as published, in a fixed-width font. The caller provides the scroll.
 */
@Composable
internal fun DocumentReader(document: ReaderDocument) {
    if (!document.markdown) {
        Text(document.text, color = ReaderText, fontSize = 11.sp, lineHeight = 15.sp,
            fontFamily = FontFamily.Monospace)
        return
    }
    val blocks = remember(document) { LegalMarkdown.parse(document.text) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (block in blocks) {
            when (block) {
                is LegalMarkdown.Block.Caption ->
                    Text(block.text, color = ReaderMuted, fontSize = 12.sp)
                is LegalMarkdown.Block.Heading -> Text(
                    styled(block.spans),
                    color = if (block.level <= 2) ReaderAccent else ReaderText,
                    fontSize = if (block.level <= 2) 17.sp else 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 10.dp),
                )
                is LegalMarkdown.Block.Paragraph ->
                    Text(styled(block.spans), color = ReaderText, fontSize = 14.sp, lineHeight = 21.sp)
                is LegalMarkdown.Block.Bullet -> Row(Modifier.fillMaxWidth()) {
                    Text("•", color = ReaderAccent, fontSize = 14.sp, modifier = Modifier.width(18.dp))
                    Text(styled(block.spans), color = ReaderText, fontSize = 14.sp, lineHeight = 21.sp)
                }
            }
        }
    }
}

private fun styled(spans: List<LegalMarkdown.Span>): AnnotatedString = buildAnnotatedString {
    for (span in spans) {
        when {
            span.url != null -> withLink(
                LinkAnnotation.Url(
                    span.url,
                    TextLinkStyles(SpanStyle(color = ReaderAccent, textDecoration = TextDecoration.Underline)),
                ),
            ) { append(span.text) }
            span.code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = CodeBackground)) {
                append(span.text)
            }
            span.bold -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(span.text) }
            else -> append(span.text)
        }
    }
}
