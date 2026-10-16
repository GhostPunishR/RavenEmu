package com.ravenemu.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

private val Background = Color(0xFF0B0C12)
private val Surface = Color(0xFF1A1B28)
private val TextMain = Color(0xFFF5F2FF)
private val Muted = Color(0xFFAAA2BC)
private val Accent = Color(0xFFA78BFA)
private val Outline = Color(0xFF3C4052)

private enum class Page(val label: String) {
    HOME("Paramètres"),
    RETRO("RetroAchievements"),
    GENERAL("Général"),
    AUDIO("Audio"),
    VIDEO("Vidéo"),
    INTERFACE("Interface"),
    ABOUT("À propos"),
}

/** Shared RavenEmu 0.8.1-inspired settings navigation. Settings are supplied by the host. */
@Composable
fun SettingsScreen(
    settings: Settings,
    onSettingsChange: ((Settings) -> Unit)?,
    appVersion: String,
    onOpenLink: ((String) -> Unit)?,
    retroSnapshot: RetroSnapshot = RetroSnapshot(),
    retroImages: Map<String, androidx.compose.ui.graphics.ImageBitmap> = emptyMap(),
    retroBusy: Boolean = false,
    onRetroLogin: ((String, String) -> Unit)? = null,
    onRetroDisconnect: (() -> Unit)? = null,
    onRetroRetry: (() -> Unit)? = null,
    hardcoreAvailable: Boolean = false,
    deltaSkins: List<DeltaSkinEntry> = emptyList(),
    onImportSkin: (() -> Unit)? = null,
    onToggleSkin: ((String) -> Unit)? = null,
    onDeleteSkin: ((String) -> Unit)? = null,
    aboutOnly: Boolean,
    onBack: () -> Unit,
) {
    var page by remember(aboutOnly) {
        mutableStateOf(if (aboutOnly) Page.ABOUT else Page.HOME)
    }
    // A legal document or licence open in the reader, over the About page.
    var openDocument by remember { mutableStateOf<ReaderDocument?>(null) }
    val scroll = rememberScrollState()
    LaunchedEffect(openDocument) { scroll.scrollTo(0) }
    var skinToDelete by remember { mutableStateOf<DeltaSkinEntry?>(null) }
    val update: (Settings) -> Unit = { updated ->
        onSettingsChange?.invoke(updated.normalized())
    }
    Column(Modifier.fillMaxSize().background(Background)) {
        Row(
            Modifier.fillMaxWidth().height(54.dp).background(Surface),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = {
                    if (openDocument != null) openDocument = null
                    else if (page == Page.HOME || aboutOnly) onBack()
                    else page = Page.HOME
                },
                modifier = Modifier.size(48.dp),
            ) { Text("‹", color = TextMain, fontSize = 30.sp) }
            Text(openDocument?.title ?: page.label, color = TextMain, fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(scroll)
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val reading = openDocument
            if (reading != null) DocumentReader(reading) else when (page) {
                Page.HOME -> {
                    if (onRetroLogin != null) {
                        CategoryRow("RetroAchievements", "Compte, succès et progression") {
                            page = Page.RETRO
                        }
                    }
                    CategoryRow("Général", "Pause, jaquettes et diagnostics") { page = Page.GENERAL }
                    CategoryRow("Audio", "Son du jeu et volume") { page = Page.AUDIO }
                    CategoryRow("Vidéo", "Couleurs, correction LCD, luminosité et contraste") { page = Page.VIDEO }
                    CategoryRow("Interface", "Vibrations, capteurs, commandes, style et Skin Delta") { page = Page.INTERFACE }
                    CategoryRow("À propos", "Version, auteur, confidentialité et licences") {
                        page = Page.ABOUT
                    }
                }
                Page.RETRO -> {
                    RetroAchievementsContent(
                        snapshot = retroSnapshot,
                        images = retroImages,
                        loginBusy = retroBusy,
                        onLogin = onRetroLogin,
                        onDisconnect = onRetroDisconnect,
                        onRetry = onRetroRetry,
                        onOpenLink = onOpenLink,
                    )
                    if (hardcoreAvailable) {
                        GroupTitle("Mode Hardcore")
                        SettingSwitch(
                            "Mode Hardcore",
                            "Succès comptés en Hardcore. Le chargement des sauvegardes d'état est désactivé et les pauses sont limitées. L'activer redémarre le jeu en cours.",
                            settings.retroHardcore,
                            onSettingsChange != null,
                        ) { update(settings.copy(retroHardcore = it)) }
                    }
                }
                Page.GENERAL -> {
                    GroupTitle("Émulation")
                    SettingDescription(
                        "Pause automatique",
                        "Le jeu et son audio sont suspendus lorsque RavenEmu passe en arrière-plan ou lorsque le panneau de jeu est ouvert. Ce comportement est fixe pour protéger la partie.",
                    )
                    GroupTitle("Bibliothèque")
                    SettingSwitch(
                        "Jaquettes en ligne",
                        "Cherche les jaquettes manquantes sur thumbnails.libretro.com. Ce serveur tiers reçoit le nom des jeux de la bibliothèque et l'adresse IP de l'appareil.",
                        settings.onlineCovers,
                        onSettingsChange != null,
                    ) { update(settings.copy(onlineCovers = it)) }
                    GroupTitle("Diagnostic")
                    SettingSwitch(
                        "Afficher les performances",
                        "FPS, temps du moteur et conversion vidéo dans le lecteur.",
                        settings.showDiagnostics,
                        onSettingsChange != null,
                    ) { update(settings.copy(showDiagnostics = it)) }
                    Text("Le diagnostic audio reste exportable depuis le menu du jeu.",
                        color = Muted, fontSize = 12.sp)
                }
                Page.AUDIO -> {
                    GroupTitle("Son du jeu")
                    SettingSwitch(
                        "Activer le son",
                        "Conserve l'horloge d'émulation et coupe uniquement la sortie audio.",
                        settings.audioEnabled,
                        onSettingsChange != null,
                    ) { update(settings.copy(audioEnabled = it)) }
                    SettingSlider(
                        "Volume", "${settings.audioVolume} %",
                        settings.audioVolume, 0..100,
                        settings.audioEnabled && onSettingsChange != null,
                    ) { update(settings.copy(audioVolume = it)) }
                    Text("Le fondu de démarrage de 180 ms reste inchangé.",
                        color = Muted, fontSize = 12.sp)
                }
                Page.VIDEO -> {
                    GroupTitle("Écran")
                    SettingDescription(
                        "Profil de couleurs",
                        "Original préserve les couleurs actuelles ; DMG simule un LCD vert ; Pocket utilise le gris. Ces profils ne concernent que les jeux Game Boy : les jeux Game Boy Color et Game Boy Advance gardent toujours leurs couleurs.",
                    )
                    ChoiceRow("Original", settings.videoPalette == "original",
                        onSettingsChange != null) {
                        update(settings.copy(videoPalette = "original"))
                    }
                    ChoiceRow("DMG vert", settings.videoPalette == "dmg",
                        onSettingsChange != null) {
                        update(settings.copy(videoPalette = "dmg"))
                    }
                    ChoiceRow("Pocket gris", settings.videoPalette == "pocket",
                        onSettingsChange != null) {
                        update(settings.copy(videoPalette = "pocket"))
                    }
                    GroupTitle("Correction des couleurs LCD")
                    SettingSwitch(
                        "Game Boy Color",
                        "Atténue les couleurs très vives pour retrouver l'aspect plus mat de l'écran d'origine.",
                        settings.lcdCorrectionGbc,
                        onSettingsChange != null,
                    ) { update(settings.copy(lcdCorrectionGbc = it)) }
                    SettingSwitch(
                        "Game Boy Advance",
                        "Même correction, réglée à part pour la Game Boy Advance.",
                        settings.lcdCorrectionGba,
                        onSettingsChange != null,
                    ) { update(settings.copy(lcdCorrectionGba = it)) }
                    GroupTitle("Ajustements")
                    SettingSlider("Luminosité", "${settings.videoBrightness}",
                        settings.videoBrightness, -50..50, onSettingsChange != null) {
                        update(settings.copy(videoBrightness = it))
                    }
                    SettingSlider("Contraste", "${settings.videoContrast} %",
                        settings.videoContrast, 50..150, onSettingsChange != null) {
                        update(settings.copy(videoContrast = it))
                    }
                    TextButton(onClick = {
                        update(settings.copy(
                            videoPalette = "original", videoBrightness = 0, videoContrast = 100,
                            lcdCorrectionGbc = false, lcdCorrectionGba = false,
                        ))
                    }, enabled = onSettingsChange != null) {
                        Text("Rétablir l'affichage d'origine", color = Accent)
                    }
                }
                Page.INTERFACE -> {
                    GroupTitle("Commandes tactiles")
                    SettingSwitch(
                        "Retour haptique",
                        "Petite vibration à la pression des boutons.",
                        settings.haptics,
                        onSettingsChange != null,
                    ) { update(settings.copy(haptics = it)) }
                    SettingSwitch(
                        "Vibration de la cartouche",
                        "Fait vibrer le téléphone avec les jeux équipés d'un moteur de vibration (Drill Dozer, WarioWare: Twisted!, cartouches Game Boy Rumble).",
                        settings.cartridgeRumble,
                        onSettingsChange != null,
                    ) { update(settings.copy(cartridgeRumble = it)) }
                    SettingSlider(
                        "Taille des commandes", "${settings.controlsScale} %",
                        settings.controlsScale, 80..115, onSettingsChange != null,
                    ) { update(settings.copy(controlsScale = it)) }
                    SettingSlider(
                        "Espacement vertical", "${settings.controlsGap} dp",
                        settings.controlsGap, 20..80, onSettingsChange != null,
                    ) { update(settings.copy(controlsGap = it)) }
                    GroupTitle("Style des boutons")
                    ChoiceRow("Classique (défaut)", settings.controlsStyle == "classic",
                        onSettingsChange != null) {
                        update(settings.copy(controlsStyle = "classic"))
                    }
                    ChoiceRow("Contraste élevé", settings.controlsStyle == "contrast",
                        onSettingsChange != null) {
                        update(settings.copy(controlsStyle = "contrast"))
                    }
                    Text("Le déplacement libre des boutons sera ajouté plus tard.",
                        color = Muted, fontSize = 12.sp)
                    TextButton(onClick = {
                        update(settings.copy(
                            haptics = false, controlsScale = 100,
                            controlsGap = 46, controlsStyle = "classic",
                        ))
                    }, enabled = onSettingsChange != null) {
                        Text("Réinitialiser les commandes", color = Accent)
                    }
                    GroupTitle("Capteurs de la cartouche")
                    SettingSwitch(
                        "Capteurs du téléphone",
                        "Le gyroscope, l'inclinaison et la lumière du téléphone remplacent ceux de la cartouche : WarioWare: Twisted!, Yoshi Topsy-Turvy, Kirby Tilt 'n' Tumble, Boktai.",
                        settings.cartridgeSensors,
                        onSettingsChange != null,
                    ) { update(settings.copy(cartridgeSensors = it)) }
                    SettingSwitch(
                        "Soleil automatique (Boktai)",
                        "Le capteur de lumière du téléphone règle le soleil : il faut la lumière du jour, celle d'une pièce ne suffit pas. Sans ce capteur, le niveau ci-dessous s'applique.",
                        settings.solarLightAuto,
                        onSettingsChange != null && settings.cartridgeSensors,
                    ) { update(settings.copy(solarLightAuto = it)) }
                    SettingSlider(
                        "Soleil (Boktai)", "${settings.solarLightLevel} %",
                        settings.solarLightLevel, 0..100,
                        onSettingsChange != null && !(settings.cartridgeSensors && settings.solarLightAuto),
                    ) { update(settings.copy(solarLightLevel = (it + 5) / 10 * 10)) }
                    GroupTitle("Skin Delta")
                    Text(
                        "RavenEmu lit les skins au format .deltaskin, en portrait. Ce projet n'est ni affilié à Delta, ni approuvé par ses auteurs : aucun skin n'est fourni, vous importez les vôtres.",
                        color = Muted, fontSize = 12.sp,
                    )
                    TextButton(onClick = { onImportSkin?.invoke() }, enabled = onImportSkin != null) {
                        Text("Importer un fichier .deltaskin", color = Accent)
                    }
                    if (deltaSkins.isEmpty()) {
                        Text("Aucun skin installé : commandes classiques.", color = Muted, fontSize = 12.sp)
                    }
                    for (skin in deltaSkins) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(skin.name, color = TextMain, fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium)
                                Text(skin.console + if (skin.selected) " · utilisé" else "",
                                    color = if (skin.selected) Accent else Muted, fontSize = 12.sp)
                            }
                            TextButton(onClick = { onToggleSkin?.invoke(skin.id) }, enabled = onToggleSkin != null) {
                                Text(if (skin.selected) "Retirer" else "Utiliser", color = Accent, fontSize = 13.sp)
                            }
                            TextButton(onClick = { skinToDelete = skin }, enabled = onDeleteSkin != null) {
                                Text("Supprimer", color = Muted, fontSize = 13.sp)
                            }
                        }
                    }
                }
                Page.ABOUT -> {
                    AboutContent(
                        onOpenLink = onOpenLink,
                        onOpenDocument = { openDocument = it },
                    )
                }
            }
        }
    }
    skinToDelete?.let { skin ->
        AlertDialog(
            onDismissRequest = { skinToDelete = null },
            title = { Text("Supprimer ce skin ?") },
            text = { Text("${skin.name} sera supprimé de l'appareil. Les commandes classiques reviennent s'il était utilisé.") },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteSkin?.invoke(skin.id)
                    skinToDelete = null
                }) { Text("Supprimer") }
            },
            dismissButton = { TextButton(onClick = { skinToDelete = null }) { Text("Annuler") } },
        )
    }
}

@Composable
private fun GroupTitle(title: String) {
    Text(title, color = Accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    HorizontalDivider(color = Outline)
}

@Composable
private fun CategoryRow(title: String, summary: String, onClick: () -> Unit) {
    val icon = when (title) {
        "RetroAchievements" -> RavenIconType.RETROACHIEVEMENTS
        "Général" -> RavenIconType.GENERAL
        "Audio" -> RavenIconType.AUDIO
        "Vidéo" -> RavenIconType.VIDEO
        "Interface" -> RavenIconType.INTERFACE
        else -> RavenIconType.ABOUT
    }
    Row(
        Modifier.fillMaxWidth()
            .border(1.dp, Outline.copy(alpha = 0.7f), RoundedCornerShape(16.dp))
            .background(Surface, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        androidx.compose.foundation.layout.Box(
            Modifier.size(42.dp)
                .background(Accent.copy(alpha = 0.13f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) { RavenIcon(icon, Accent) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = TextMain, fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold)
            Text(summary, color = Muted, fontSize = 12.sp)
        }
        Text("›", color = Accent, fontSize = 23.sp)
    }
}

@Composable
private fun SettingDescription(title: String, summary: String) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, color = TextMain, fontSize = 15.sp)
        Text(summary, color = Muted, fontSize = 13.sp)
    }
}

@Composable
private fun SettingSwitch(
    title: String, summary: String, checked: Boolean, enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            SettingDescription(title, summary)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun SettingSlider(
    title: String, valueText: String, value: Int, range: IntRange,
    enabled: Boolean, onChange: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, color = TextMain, fontSize = 15.sp)
            Text(valueText, color = Accent, fontSize = 14.sp)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            enabled = enabled,
        )
    }
}

@Composable
private fun ChoiceRow(
    title: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().background(if (selected) Surface else Background)
        .clickable(enabled = enabled, onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (selected) "●" else "○", color = Accent, fontSize = 17.sp)
        Spacer(Modifier.width(12.dp))
        Text(title, color = TextMain, fontSize = 14.sp)
    }
}

