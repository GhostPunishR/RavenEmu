package com.ravenemu.shared.library

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ravenemu.shared.saves.BatteryFileFormat
import com.ravenemu.shared.saves.GbSaveImportReview
import com.ravenemu.shared.theme.RavenAccent
import com.ravenemu.shared.theme.RavenBackground
import com.ravenemu.shared.theme.RavenDanger
import com.ravenemu.shared.theme.RavenMuted
import com.ravenemu.shared.theme.RavenSecondary
import com.ravenemu.shared.theme.RavenSurface
import com.ravenemu.shared.theme.RavenText

/** Sheet of one library game: cover, launch and save transfers. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GameDetailsSheet(
    entry: GameEntry,
    cover: ImageBitmap?,
    coverBusy: Boolean,
    importBusy: Boolean,
    saveTransferBusy: Boolean,
    /** Starts the game; null when it cannot be played from here. */
    onPlay: (() -> Unit)?,
    /** Picks a second game to play alongside, linked by cable; null when unavailable. */
    onPlayLinked: (() -> Unit)? = null,
    onChooseCover: ((String) -> Unit)?,
    onRemoveCover: ((String) -> Unit)?,
    onExportBatterySave: ((String, BatteryFileFormat) -> Unit)?,
    onImportGameSave: ((String) -> Unit)?,
    /** Offered on export; with only one, the button exports it directly. */
    batteryFormats: List<BatteryFileFormat> = BatteryFileFormat.entries,
    onDismiss: () -> Unit,
) {
    // Opens straight at its full height: no half step to drag up before
    // Jouer can be reached. It still follows the finger down to close, but
    // while it settles a tap goes to the button instead of grabbing it.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetGesturesEnabled = !sheetState.isAnimationRunning,
        containerColor = RavenSurface,
        contentColor = RavenText,
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(entry.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(platformLabel(entry.platform), color = RavenSecondary, fontSize = 13.sp)
            CoverSection(entry, cover, coverBusy, importBusy, onChooseCover, onRemoveCover)
            LaunchButtons(importBusy, saveTransferBusy, onPlay, onPlayLinked, onDismiss)
            if (onExportBatterySave != null || onImportGameSave != null) {
                SaveTransferSection(entry, importBusy, saveTransferBusy, batteryFormats,
                    onExportBatterySave, onImportGameSave)
            }
            Text(
                "Vidéo, commandes et audio Game Boy, Game Boy Color et Game Boy Advance disponibles sur Android. La sauvegarde interne du jeu est automatique et un save state manuel est disponible dans le menu ⋮.",
                color = RavenMuted,
                fontSize = 13.sp,
            )
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Fermer")
            }
        }
    }
}

/** The cover, and how to change or remove it. */
@Composable
private fun CoverSection(
    entry: GameEntry,
    cover: ImageBitmap?,
    coverBusy: Boolean,
    importBusy: Boolean,
    onChooseCover: ((String) -> Unit)?,
    onRemoveCover: ((String) -> Unit)?,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        CoverArtwork(
            entry.platform, cover,
            Modifier.width(90.dp).height(105.dp),
        )
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                when {
                    cover != null ->
                        "Jaquette enregistrée dans RavenEmu"
                    coverBusy -> "Recherche de jaquette…"
                    else -> "Jaquette introuvable automatiquement"
                },
                color = RavenMuted, fontSize = 13.sp,
            )
            if (onChooseCover != null) {
                OutlinedButton(
                    onClick = { onChooseCover(entry.id) },
                    enabled = !coverBusy && !importBusy,
                ) {
                    Text(
                        if (cover != null)
                            "Changer l'image"
                        else "Image manuelle (facultatif)",
                        fontSize = 12.sp,
                    )
                }
            }
            if (cover != null && onRemoveCover != null) {
                TextButton(
                    onClick = { onRemoveCover(entry.id) },
                    enabled = !coverBusy,
                ) { Text("Retirer la jaquette", color = RavenAccent, fontSize = 12.sp) }
            }
        }
    }
}

/** Each button closes the sheet before the game starts. */
@Composable
private fun LaunchButtons(
    importBusy: Boolean,
    saveTransferBusy: Boolean,
    onPlay: (() -> Unit)?,
    onPlayLinked: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    if (onPlay != null) {
        Button(
            onClick = {
                onDismiss()
                onPlay()
            },
            enabled = !importBusy && !saveTransferBusy,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = RavenAccent,
                contentColor = RavenBackground,
            ),
        ) {
            Text("Jouer")
        }
    }
    if (onPlayLinked != null) {
        OutlinedButton(
            onClick = {
                onDismiss()
                onPlayLinked()
            },
            enabled = !importBusy && !saveTransferBusy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Jouer à deux (câble link)")
        }
    }
}

@Composable
private fun SaveTransferSection(
    entry: GameEntry,
    importBusy: Boolean,
    saveTransferBusy: Boolean,
    batteryFormats: List<BatteryFileFormat>,
    onExportBatterySave: ((String, BatteryFileFormat) -> Unit)?,
    onImportGameSave: ((String) -> Unit)?,
) {
    Text("Sauvegardes", color = RavenText,
        fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    Text(
        "Transférez votre progression sans exporter la ROM.",
        color = RavenMuted, fontSize = 12.sp,
    )
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (onExportBatterySave != null && batteryFormats.isNotEmpty()) {
            BatteryExportButton(entry, batteryFormats, importBusy, saveTransferBusy, onExportBatterySave)
        }
        if (onImportGameSave != null) {
            OutlinedButton(
                modifier = Modifier.weight(1f),
                enabled = !importBusy && !saveTransferBusy,
                onClick = { onImportGameSave(entry.id) },
            ) { Text("Importer", fontSize = 12.sp) }
        }
    }
}

/** Exports at once with a single format, otherwise lets the player pick one. */
@Composable
private fun RowScope.BatteryExportButton(
    entry: GameEntry,
    batteryFormats: List<BatteryFileFormat>,
    importBusy: Boolean,
    saveTransferBusy: Boolean,
    onExportBatterySave: (String, BatteryFileFormat) -> Unit,
) {
    var formatsOpen by remember { mutableStateOf(false) }
    Box(Modifier.weight(1f)) {
        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            enabled = !importBusy && !saveTransferBusy,
            onClick = {
                val only = batteryFormats.singleOrNull()
                if (only != null) onExportBatterySave(entry.id, only)
                else formatsOpen = true
            },
        ) {
            Text(batteryFormats.singleOrNull()?.let { "Exporter ${it.extension}" }
                ?: "Exporter", fontSize = 12.sp)
        }
        DropdownMenu(
            expanded = formatsOpen,
            onDismissRequest = { formatsOpen = false },
            modifier = Modifier.background(RavenSurface),
        ) {
            for (format in batteryFormats) {
                DropdownMenuItem(
                    text = { Text(format.label, color = RavenText) },
                    onClick = {
                        formatsOpen = false
                        onExportBatterySave(entry.id, format)
                    },
                )
            }
        }
    }
}

@Composable
internal fun FolderImportSummaryDialog(summary: GbFolderImportSummary, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = { onDismiss() },
        title = { Text("Importation du dossier terminée") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("Jeux ajoutés : ${summary.added}")
                Text("Doublons ignorés : ${summary.duplicate}")
                Text("ROM invalides : ${summary.invalid}")
                if (summary.unreadable > 0) {
                    Text("Fichiers ou dossiers illisibles : ${summary.unreadable}")
                }
                Text("Autres fichiers ignorés : ${summary.otherFiles}",
                    color = RavenMuted, fontSize = 13.sp)
                Text("Dossiers parcourus : ${summary.scannedFolders}",
                    color = RavenMuted, fontSize = 13.sp)
                if (summary.limited) {
                    Text("Parcours arrêté par une limite de sécurité ou de stockage. Les jeux déjà ajoutés sont conservés.",
                        color = RavenDanger, fontSize = 13.sp)
                }
                Text("Les jeux existants, leurs sauvegardes et la partie en cours n'ont pas été modifiés.",
                    color = RavenSecondary, fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = { onDismiss() }) {
                Text("Terminer", color = RavenAccent)
            }
        },
        containerColor = RavenSurface,
        titleContentColor = RavenText,
        textContentColor = RavenText,
    )
}

@Composable
internal fun SaveImportReviewDialog(
    review: GbSaveImportReview,
    saveTransferBusy: Boolean,
    onConfirm: (() -> Unit)?,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onCancel() },
        title = { Text("Importer les sauvegardes ?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Jeu : ${review.gameTitle}")
                Text(
                    "Contenu : " +
                        (if (review.hasBattery) "sauvegarde du jeu" else "pas de sauvegarde du jeu") +
                        (if (review.slots.isEmpty()) "" else
                            ", états ${review.slots.joinToString(", ")}"),
                )
                if (review.rawSav) {
                    Text(
                        "Un fichier .sav ou .srm seul n'identifie pas sa ROM. Vérifiez que vous avez choisi le bon jeu.",
                        color = RavenDanger, fontSize = 13.sp,
                    )
                }
                if (review.conflicts.isNotEmpty()) {
                    Text(
                        "Des données existantes seront remplacées : " +
                            review.conflicts.joinToString(", ") + ".",
                        color = RavenDanger, fontSize = 13.sp,
                    )
                } else {
                    Text("Les autres sauvegardes resteront inchangées.",
                        color = RavenSecondary, fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saveTransferBusy && onConfirm != null,
                onClick = { onConfirm?.invoke() },
            ) { Text("Confirmer l'import", color = RavenAccent) }
        },
        dismissButton = {
            TextButton(onClick = { onCancel() }) {
                Text("Annuler", color = RavenSecondary)
            }
        },
        containerColor = RavenSurface,
        titleContentColor = RavenText,
        textContentColor = RavenText,
    )
}

/**
 * Second game for a linked session: [partners] are the library's games that
 * [game] can be linked to (see [LinkCable]).
 */
@Composable
internal fun LinkPartnerDialog(
    game: GameEntry,
    partners: List<GameEntry>,
    onPick: (GameEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Jouer à deux") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "${game.title} et un second jeu tournent ensemble, reliés par un câble " +
                        "link virtuel : échanges et combats entre vos propres sauvegardes. " +
                        "Un jeu est affiché à la fois : le bouton ⇄ passe à l'autre, " +
                        "qui continue de tourner.",
                    color = RavenSecondary, fontSize = 13.sp,
                )
                if (partners.isEmpty()) {
                    Text(
                        if (game.platform == GamePlatform.GBA)
                            "Ajoutez un autre jeu Game Boy Advance à la bibliothèque."
                        else "Ajoutez un autre jeu Game Boy ou Game Boy Color à la bibliothèque.",
                        color = RavenMuted, fontSize = 13.sp,
                    )
                }
                for (partner in partners) {
                    OutlinedButton(
                        onClick = { onPick(partner) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            "${partner.title} · ${platformLabel(partner.platform)}",
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Annuler", color = RavenAccent) }
        },
        containerColor = RavenSurface,
        titleContentColor = RavenText,
        textContentColor = RavenText,
    )
}
