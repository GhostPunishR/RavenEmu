package com.ravenemu.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun RetroLeaderboardsContent(snapshot: RetroSnapshot,
    onFetch: ((Int, Boolean) -> Unit)?, onOpenLink: ((String) -> Unit)?) {
    var selected by remember(snapshot.username, snapshot.gameId) { mutableStateOf<Int?>(null) }
    var visible by remember(snapshot.gameId) { mutableStateOf(10) }
    HorizontalDivider()
    Text("Classements", color = RavenText, fontWeight = FontWeight.SemiBold)
    if (!snapshot.hardcore) Text("Consultation des scores disponible. Les tentatives officielles nécessitent le mode Hardcore.",
        color = RavenMuted, fontSize = 12.sp)
    snapshot.scoreboard?.let { score ->
        Text("Dernier résultat · ${score.title}", color = RavenAccent)
        Text("Score : ${score.submitted}\nRecord : ${score.best}\nRang : ${score.rank}/${score.total}", color = RavenText)
    }
    if (snapshot.leaderboards.isEmpty()) Text("Aucun classement pour ce jeu.", color = RavenMuted)
    for (board in snapshot.leaderboards.take(visible)) {
        TextButton(onClick = {
            selected = board.id
            onFetch?.invoke(board.id, false)
        }, enabled = onFetch != null) { Text(board.title, color = RavenAccent) }
    }
    if (snapshot.leaderboards.size > visible) TextButton(onClick = { visible += 15 }) { Text("Afficher plus de classements") }
    val board = snapshot.leaderboards.firstOrNull { it.id == selected } ?: return
    val view = snapshot.leaderboardView
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(board.title, color = RavenText, fontWeight = FontWeight.SemiBold)
        Text(board.description, color = RavenMuted, fontSize = 12.sp)
        if (board.value.isNotBlank()) Text("Tentative : ${board.value}", color = RavenAccent)
        Row {
            TextButton(onClick = { onFetch?.invoke(board.id, false) }, enabled = !view.loading) { Text("Meilleurs scores") }
            TextButton(onClick = { onFetch?.invoke(board.id, true) }, enabled = !view.loading) { Text("Autour de moi") }
        }
        if (view.id == board.id) {
            when {
                view.loading -> CircularProgressIndicator()
                view.error.isNotBlank() -> {
                    Text(view.error, color = RavenMuted)
                    TextButton(onClick = { onFetch?.invoke(board.id, view.aroundUser) }) { Text("Réessayer les scores") }
                }
                else -> {
                    Text(if (view.aroundUser) "Scores autour de moi · ${view.total} participants" else "Meilleurs scores · ${view.total} participants",
                        color = RavenMuted, fontSize = 12.sp)
                    if (view.entries.isEmpty()) Text("Aucun score enregistré.", color = RavenMuted)
                    for (entry in view.entries) {
                        Text("#${entry.rank} · ${entry.username}${if (entry.isUser) " (vous)" else ""}\n${entry.score}",
                            color = if (entry.isUser) RavenAccent else RavenText, fontSize = 13.sp)
                        if (entry.submittedDate.isNotBlank()) Text(entry.submittedDate, color = RavenMuted, fontSize = 11.sp)
                    }
                }
            }
        }
        TextButton(onClick = { onOpenLink?.invoke("https://retroachievements.org/leaderboardinfo.php?i=${board.id}") },
            enabled = onOpenLink != null) { Text("Classement complet sur le site") }
    }
}
