package com.ravenemu.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Ce que le lecteur montre du câble link sans fil : sur Android, deux
 * téléphones du même réseau Wi-Fi se relient d'eux-mêmes quand le jeu se sert
 * de son port link. L'hôte décide de tout ; le lecteur ne fait qu'afficher.
 */
data class WirelessLinkView(
    /** Une ligne sur l'image du jeu ; l'hôte la retire après quelques secondes. */
    val banner: String? = null,
    /** Plusieurs téléphones attendent : le joueur en choisit un. */
    val choices: List<WirelessLinkChoice> = emptyList(),
    /** Ce téléphone, tel que les autres le listent. */
    val ownLabel: String = "",
    /** Le partenaire relié, pour le menu du jeu ; null sans câble. */
    val partner: String? = null,
    /** Le menu du jeu du partenaire est ouvert : ce jeu l'attend. */
    val partnerPaused: Boolean = false,
)

data class WirelessLinkChoice(val id: Long, val label: String)

/** Le bandeau passager et la pause imposée par le partenaire, sur l'image du jeu. */
@Composable
internal fun BoxScope.WirelessLinkOverlay(view: WirelessLinkView) {
    if (view.partnerPaused) {
        Box(
            Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.55f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "Partenaire en pause",
                Modifier.background(RavenSurface.copy(alpha = 0.92f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                color = RavenText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            )
        }
    }
    view.banner?.let { line ->
        Text(
            line,
            Modifier.align(Alignment.TopCenter).padding(top = 10.dp, start = 12.dp, end = 12.dp)
                .background(RavenSurface.copy(alpha = 0.9f), RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 7.dp),
            color = RavenText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Le choix du partenaire, quand plusieurs téléphones attendent. Null : plus tard. */
@Composable
internal fun WirelessLinkChooser(view: WirelessLinkView, onChoose: (Long?) -> Unit) {
    if (view.choices.isEmpty()) return
    AlertDialog(
        onDismissRequest = { onChoose(null) },
        title = { Text("Relier le câble à…") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Plusieurs téléphones attendent un câble link. Ce téléphone apparaît chez eux sous le nom ${view.ownLabel}.",
                    color = RavenMuted, fontSize = 13.sp,
                )
                for (choice in view.choices) {
                    TextButton(onClick = { onChoose(choice.id) }, modifier = Modifier.fillMaxWidth()) {
                        Text(choice.label, color = RavenAccent)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { onChoose(null) }) { Text("Plus tard") } },
        containerColor = RavenSurface,
        titleContentColor = RavenText,
        textContentColor = RavenText,
    )
}

/** Dans le menu du jeu : le partenaire relié, et de quoi débrancher. */
@Composable
internal fun WirelessLinkMenuRow(view: WirelessLinkView, onUnplug: (() -> Unit)?) {
    val partner = view.partner ?: return
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "Câble sans fil relié à $partner. Le chargement d'une sauvegarde d'état est désactivé.",
            Modifier.weight(1f), color = RavenSecondary, fontSize = 13.sp,
        )
        if (onUnplug != null) OutlinedButton(onClick = onUnplug) { Text("Débrancher") }
    }
}
