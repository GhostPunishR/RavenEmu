package com.ravenemu.shared

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val CardSurface = Color(0xFF1A1B28)
private val CardOutline = Color(0xFF3C4052)
private val TextMain = Color(0xFFF5F2FF)
private val Muted = Color(0xFFAAA2BC)
private val Accent = Color(0xFFA78BFA)

private const val REPO = "https://github.com/GhostPunishR/RavenEmu"

/**
 * Réglages → À propos: who makes RavenEmu, under which terms, with which
 * third-party code, and where its source lives. Laid out as the settings
 * categories are: rounded cards of rows, each with an icon. Legal documents
 * and licences open in the app's own reader; only the source code leaves it.
 */
@Composable
internal fun AboutContent(
    onOpenLink: ((String) -> Unit)?,
    onOpenDocument: (ReaderDocument) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        AboutSection("Informations légales") {
            AboutRow(AboutIcon.TERMS, "Conditions d'utilisation", "Ce que RavenEmu fournit, et votre responsabilité",
                trailing = "›", onClick = { onOpenDocument(LegalDocuments.terms) })
            AboutRow(AboutIcon.PRIVACY, "Politique de confidentialité", "Données échangées et services tiers",
                trailing = "›", onClick = { onOpenDocument(LegalDocuments.privacy) })
            AboutRow(AboutIcon.DOCUMENT, "Licence AGPL-3.0", "Licence du projet, texte complet",
                trailing = "›", onClick = { onOpenDocument(LegalDocuments.agpl) })
        }

        AboutSection("Licences et crédits") {
            for (notice in OpenSourceNotices.all) {
                AboutRow(AboutIcon.LICENSES, notice.name, "${notice.license} · ${notice.usage}",
                    trailing = "›", onClick = { onOpenDocument(LegalDocuments.of(notice)) })
            }
        }

        AboutSection("Code source") {
            AboutRow(AboutIcon.SOURCE, "RavenEmu", "github.com/GhostPunishR/RavenEmu",
                trailing = "↗", onClick = onOpenLink?.let { open -> { open(REPO) } })
            AboutRow(AboutIcon.SOURCE, "RavenCore", "github.com/GhostPunishR/RavenCore",
                trailing = "↗", onClick = onOpenLink?.let { open -> { open("https://github.com/GhostPunishR/RavenCore") } })
        }
    }
}

@Composable
private fun AboutSection(title: String, rows: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title.uppercase(), color = Accent, fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp,
            modifier = Modifier.padding(start = 6.dp))
        Column(
            Modifier.fillMaxWidth()
                .background(CardSurface, RoundedCornerShape(16.dp))
                .border(1.dp, CardOutline.copy(alpha = 0.7f), RoundedCornerShape(16.dp)),
        ) { rows() }
    }
}

@Composable
private fun AboutRow(
    icon: AboutIcon,
    title: String,
    summary: String,
    trailing: String? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Box(
            Modifier.size(38.dp).background(Accent.copy(alpha = 0.13f), RoundedCornerShape(11.dp)),
            contentAlignment = Alignment.Center,
        ) { LineIcon(icon, Accent, Modifier.size(22.dp)) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(summary, color = Muted, fontSize = 12.sp, lineHeight = 16.sp)
        }
        if (trailing != null) Text(trailing, color = Accent, fontSize = 18.sp)
    }
    HorizontalDivider(Modifier.padding(start = 65.dp), color = CardOutline.copy(alpha = 0.45f))
}

/**
 * Outline icons of the About page, on a 24x24 grid. Same drawings as the RavenEmu 0.8.1
 * About screen's own icons (res/drawable/ic_about_*.xml).
 */
private enum class AboutIcon(val strokes: List<String>, val dots: List<Triple<Float, Float, Float>> = emptyList()) {
    TERMS(listOf("M14,2.8H6.4V21.2H17.6V6.4ZM14,2.8V6.4H17.6", "M9.2,11.4H14.8M9.2,14.6H14.8M9.2,17.8H12.6")),
    PRIVACY(listOf("M12,2.6L20,5.8V12C20,16.6 16.6,20.1 12,21.4C7.4,20.1 4,16.6 4,12V5.8Z", "M12,11.2V15"),
        listOf(Triple(12f, 9.2f, 1.7f))),
    DOCUMENT(listOf(
        "M18.6,12.6V6.4L14,2.8H6.4V21.2H12", "M14,2.8V6.4H18.6",
        "M17,17.4m-3.2,0a3.2,3.2 0,1 1,6.4 0a3.2,3.2 0,1 1,-6.4 0", "M15.2,20V23L17,21.8L18.8,23V20",
    )),
    LICENSES(listOf("M12,2.8V21.2M5.4,6.2L18.6,4.2", "M5.4,6.2L2.4,13.4H8.4ZM18.6,4.2L15.6,11.4H21.6Z", "M8,21.2H16")),
    SOURCE(listOf("M8.2,7.4L3.2,12L8.2,16.6M15.8,7.4L20.8,12L15.8,16.6M13.6,4.4L10.4,19.6")),
}

@Composable
private fun LineIcon(icon: AboutIcon, tint: Color, modifier: Modifier) {
    val paths = remember(icon) { icon.strokes.map { PathParser().parsePathString(it).toPath() } }
    Canvas(modifier) {
        val unit = size.minDimension / 24f
        scale(scaleX = unit, scaleY = unit, pivot = Offset.Zero) {
            for (path in paths) {
                drawPath(path, tint, style = Stroke(width = 1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            for ((x, y, r) in icon.dots) drawCircle(tint, r, Offset(x, y))
        }
    }
}
