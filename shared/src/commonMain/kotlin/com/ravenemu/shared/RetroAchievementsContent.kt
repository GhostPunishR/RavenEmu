package com.ravenemu.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val raAccent = Color(0xFFA78BFA)
private val raText = Color(0xFFF5F2FF)
private val raMuted = Color(0xFFAAA2BC)
private val raSurface = Color(0xFF191B28)

/** RavenEmu 0.8.1-style account + Softcore game dashboard, independent of Android JNI. */
@Composable
fun RetroAchievementsContent(
    snapshot: RetroSnapshot,
    images: Map<String, ImageBitmap> = emptyMap(),
    loginBusy: Boolean,
    onLogin: ((String, String) -> Unit)?,
    onDisconnect: (() -> Unit)?,
    onRetry: (() -> Unit)?,
    onOpenLink: ((String) -> Unit)?,
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(0) }
    var visibleCount by remember { mutableStateOf(10) }
    var selected by remember { mutableStateOf<AchievementEntry?>(null) }

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("RetroAchievements", fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold, color = raText)
        Text(snapshot.status, fontSize = 12.sp, color = raMuted)

        if (snapshot.username.isBlank()) {
            Text("Connectez votre compte pour obtenir les succès officiels pendant vos parties Game Boy, Game Boy Color et Game Boy Advance. Mode normal (Softcore).",
                color = raMuted, fontSize = 13.sp)
            OutlinedTextField(
                value = username,
                onValueChange = { username = it.take(80) },
                label = { Text("Nom d'utilisateur") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                enabled = !loginBusy,
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Mot de passe") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                enabled = !loginBusy,
            )
            Button(
                onClick = {
                    onLogin?.invoke(username.trim(), password)
                    password = "" // never retain credentials in the UI
                },
                enabled = !loginBusy && username.isNotBlank() && password.isNotBlank()
                    && onLogin != null,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = raAccent, contentColor = Color(0xFF0B0C12)),
            ) { Text(if (loginBusy) "Connexion…" else "Se connecter") }
            Text("Le mot de passe n'est pas enregistré. Seul le jeton est conservé, chiffré sur cet appareil.",
                color = raMuted, fontSize = 11.sp)
        } else {
            Column(
                Modifier.fillMaxWidth()
                    .background(raSurface, RoundedCornerShape(14.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val avatar = images[snapshot.avatarUrl]
                    if (avatar != null) {
                        Image(avatar, "Avatar RetroAchievements",
                            Modifier.size(58.dp), contentScale = ContentScale.Fit)
                    }
                    Column {
                        Text(snapshot.displayName.ifBlank { snapshot.username },
                            color = raText, fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold)
                        Text("@${snapshot.username}", color = raMuted, fontSize = 12.sp)
                    }
                }
                Text("Softcore : ${snapshot.softcorePoints} points  ·  Hardcore : ${snapshot.hardcorePoints} points",
                    color = raAccent, fontSize = 12.sp)
                if (snapshot.gameTitle.isNotBlank()) {
                    HorizontalDivider(color = Color(0xFF3C4052))
                    Text(snapshot.gameTitle, color = raText,
                        fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text("${snapshot.unlockedCount}/${snapshot.totalCount} succès  ·  ${snapshot.unlockedPoints}/${snapshot.totalPoints} points",
                        color = raMuted, fontSize = 12.sp)
                } else {
                    Text("Aucun jeu reconnu pour le moment.",
                        color = raMuted, fontSize = 12.sp)
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = { onRetry?.invoke() },
                    enabled = !loginBusy && onRetry != null,
                ) { Text("Réessayer", fontSize = 12.sp) }
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = { onDisconnect?.invoke() },
                    enabled = !loginBusy && onDisconnect != null,
                ) { Text("Déconnexion", fontSize = 12.sp) }
            }
            if (snapshot.entries.isNotEmpty()) {
                Text("Succès du jeu", color = raText, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (index in 0..2) {
                        val label = when(index) { 0 -> "Tous"; 1 -> "À obtenir"; else -> "Obtenus" }
                        TextButton(onClick = { filter = index; visibleCount = 10 }) {
                            Text(label,
                                color = if (filter == index) raAccent else raMuted,
                                fontSize = 12.sp)
                        }
                    }
                }
                val items = snapshot.entries.filter {
                    filter == 0 || (filter == 2) == it.unlocked
                }
                for (achievement in items.take(visibleCount)) {
                    Column(
                        Modifier.fillMaxWidth()
                            .background(raSurface.copy(alpha = 0.65f), RoundedCornerShape(10.dp))
                            .clickable { selected = achievement }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            val badge = images[achievement.badgeUrl]
                            if (badge != null) {
                                Image(badge, "Badge du succès",
                                    Modifier.size(42.dp), contentScale = ContentScale.Fit)
                            } else {
                                // Same footprint while the badge loads or if it
                                // is unavailable: titles stay aligned.
                                Box(Modifier.size(42.dp)
                                    .background(raMuted.copy(alpha = 0.18f), RoundedCornerShape(6.dp)))
                            }
                            Text(achievement.title, modifier = Modifier.weight(1f),
                                color = raText, fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.width(6.dp))
                            Text("${achievement.points} pts", color = raAccent, fontSize = 11.sp)
                        }
                        Text(
                            when {
                                achievement.unlocked -> "Débloqué"
                                achievement.state == 3 -> "Non pris en charge"
                                achievement.progress.isNotBlank() -> achievement.progress
                                else -> "Verrouillé"
                            },
                            color = raMuted, fontSize = 11.sp,
                        )
                    }
                }
                if (items.size > visibleCount) {
                    TextButton(onClick = { visibleCount += 15 }) {
                        Text("Afficher plus (${items.size - visibleCount} restants)",
                            color = raAccent)
                    }
                }
                selected?.let { achievement ->
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { selected = null },
                        title = { Text(achievement.title) },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                images[achievement.badgeUrl]?.let { badge ->
                                    Image(badge, "Badge du succès",
                                        Modifier.size(88.dp), contentScale = ContentScale.Fit)
                                }
                                Text(achievement.description +
                                    "\n\n${achievement.points} points · " +
                                    (if (achievement.unlocked) "Débloqué" else "À obtenir"))
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { selected = null }) { Text("Fermer") }
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                onOpenLink?.invoke("https://retroachievements.org/achievement/${achievement.id}")
                            }) { Text("Voir sur le site") }
                        },
                    )
                }
            }
        }
        if (snapshot.error != null) {
            Text(snapshot.error, color = Color(0xFFFF928B), fontSize = 12.sp)
        }
        Text("Mode Hardcore désactivé · SDK officiel rcheevos 12.5.0",
            color = raMuted, fontSize = 11.sp)
    }
}
