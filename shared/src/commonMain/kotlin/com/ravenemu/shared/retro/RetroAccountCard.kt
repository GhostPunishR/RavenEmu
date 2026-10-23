package com.ravenemu.shared.retro

import androidx.compose.foundation.Image
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ravenemu.shared.theme.RavenAccent
import com.ravenemu.shared.theme.RavenMuted
import com.ravenemu.shared.theme.RavenOutline
import com.ravenemu.shared.theme.RavenSecondary
import com.ravenemu.shared.theme.RavenText

/** The account as the game menu shows it: plain text lines, Softcore first. */
data class RetroAccountSummary(
    /** Display name, or the connection state when signed out. */
    val name: String,
    /** Points of the account, or what to do / what went wrong. */
    val points: String,
    /** Progress in the running game, or why there is none; may be empty. */
    val game: String,
    /** "Softcore" or "Hardcore" while signed in, empty otherwise. */
    val mode: String,
)

fun retroAccountSummary(snapshot: RetroSnapshot): RetroAccountSummary {
    if (snapshot.username.isBlank()) {
        val name = if (snapshot.busy) "Connexion en cours…" else "Non connecté"
        // A refused login keeps its reason in the status; the neutral
        // states only invite to sign in.
        val points = if (snapshot.busy || snapshot.status in NEUTRAL_STATUSES)
            "Touchez pour vous connecter" else snapshot.status
        return RetroAccountSummary(name, points, "", "")
    }
    val game = when {
        snapshot.gameTitle.isNotBlank() -> buildString {
            append("${snapshot.unlockedCount}/${snapshot.totalCount} succès")
            append("  ·  ${snapshot.unlockedPoints}/${snapshot.totalPoints} pts")
            if (snapshot.pendingAwards > 0) append("  ·  ${snapshot.pendingAwards} en attente")
        }
        // Signed in without a set: loading, unknown game or a game error.
        snapshot.status.startsWith("Connecté") -> "Aucun jeu reconnu pour le moment."
        else -> snapshot.status
    }
    return RetroAccountSummary(
        name = snapshot.displayName.ifBlank { snapshot.username },
        points = "Softcore ${snapshot.softcorePoints} pts  ·  Hardcore ${snapshot.hardcorePoints} pts",
        game = game,
        mode = if (snapshot.hardcore) "Hardcore" else "Softcore",
    )
}

private val NEUTRAL_STATUSES = setOf(
    "Non connecté", "Connectez votre compte RetroAchievements.", "Connexion en cours…",
)

/** Entry of the game menu: the account at a glance; a touch opens RetroAchievements. */
@Composable
internal fun RetroAccountCard(
    snapshot: RetroSnapshot,
    avatar: ImageBitmap?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val summary = retroAccountSummary(snapshot)
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier.fillMaxWidth()
            .clip(shape)
            .background(Color(0xFF202230))
            .border(1.dp, RavenOutline, shape)
            .clickable(onClickLabel = "Ouvrir RetroAchievements", role = Role.Button, onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val avatarShape = RoundedCornerShape(10.dp)
        if (avatar != null) {
            Image(avatar, null, Modifier.size(44.dp).clip(avatarShape), contentScale = ContentScale.Crop)
        } else {
            Box(
                Modifier.size(44.dp).background(Color(0xFF292A38), avatarShape),
                contentAlignment = Alignment.Center,
            ) { Text("RA", color = RavenAccent, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("RetroAchievements", color = RavenMuted, fontSize = 11.sp,
                    modifier = Modifier.weight(1f))
                if (summary.mode.isNotEmpty()) {
                    Text(summary.mode, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                        color = if (snapshot.hardcore) RavenAccent else RavenSecondary)
                }
            }
            Text(summary.name, color = RavenText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(summary.points, color = RavenSecondary, fontSize = 12.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (summary.game.isNotEmpty()) {
                Text(summary.game, color = RavenMuted, fontSize = 12.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Text("›", color = RavenMuted, fontSize = 22.sp)
    }
}
