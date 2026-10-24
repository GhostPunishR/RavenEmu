package com.ravenemu.shared.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ravenemu.shared.theme.RavenAccent
import com.ravenemu.shared.theme.RavenMuted
import com.ravenemu.shared.theme.RavenText

/** How many times faster than the console fast forward runs, at most. */
const val FAST_FORWARD_SPEED = 4

/** Over the picture while the game fast-forwards. */
@Composable
internal fun FastForwardBadge(modifier: Modifier = Modifier) {
    Text(
        "»  ×$FAST_FORWARD_SPEED",
        modifier.padding(10.dp)
            .background(Color(0xCC000000), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        color = RavenAccent, fontSize = 13.sp, fontWeight = FontWeight.Bold,
    )
}

/** The game menu's switch. */
@Composable
internal fun FastForwardSwitch(on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Avance rapide", color = RavenText, fontSize = 15.sp)
            Text("Le jeu tourne jusqu'à $FAST_FORWARD_SPEED fois plus vite ; le son passe par morceaux. " +
                "Aussi par le bouton » en haut de l'écran ou le clic du stick droit de la manette.",
                color = RavenMuted, fontSize = 12.sp, lineHeight = 16.sp)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = on, onCheckedChange = onChange)
    }
}
