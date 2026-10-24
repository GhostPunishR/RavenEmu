package com.ravenemu.shared.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ravenemu.shared.saves.GbCheat
import com.ravenemu.shared.theme.RavenAccent
import com.ravenemu.shared.theme.RavenBackground
import com.ravenemu.shared.theme.RavenDanger
import com.ravenemu.shared.theme.RavenMuted
import com.ravenemu.shared.theme.RavenText

/**
 * The running game's cheats, for the game menu. [allowed] is false in
 * Hardcore: the list stays visible, nothing runs. [onAdd] checks the code
 * and returns why it is refused, or null once added.
 */
class GbCheatsView(
    val cheats: List<GbCheat> = emptyList(),
    val allowed: Boolean = true,
    val onAdd: (name: String, code: String) -> String? = { _, _ -> null },
    val onToggle: (Int) -> Unit = {},
    val onDelete: (Int) -> Unit = {},
)

/** The game menu's entry: how many cheats are on. */
@Composable
internal fun CheatsMenuButton(view: GbCheatsView, onOpen: () -> Unit) {
    val on = view.cheats.count { it.enabled }
    OutlinedButton(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Text(if (on == 0) "Codes de triche" else "Codes de triche · $on actif${if (on > 1) "s" else ""}")
    }
}

/** The list, each code turned on or off or deleted, and a form to add one. */
@Composable
internal fun CheatsPanel(view: GbCheatsView, onBack: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var refusal by remember { mutableStateOf<String?>(null) }
    TextButton(onClick = onBack) { Text("‹ Retour au menu du jeu", color = RavenAccent) }
    Text("Codes de triche", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    if (!view.allowed) {
        Text("Mode Hardcore : les codes de triche sont désactivés.", color = RavenDanger, fontSize = 13.sp)
    }
    if (view.cheats.isEmpty()) {
        Text("Aucun code pour ce jeu.", color = RavenMuted, fontSize = 13.sp)
    }
    view.cheats.forEachIndexed { index, cheat ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(cheat.name, color = RavenText, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(cheat.code, color = RavenMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TextButton(onClick = { view.onDelete(index) }) { Text("Supprimer", color = RavenMuted, fontSize = 12.sp) }
            Spacer(Modifier.width(4.dp))
            Switch(checked = cheat.enabled, onCheckedChange = { view.onToggle(index) }, enabled = view.allowed)
        }
    }
    Text("Ajouter un code", color = RavenText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    Text(
        "Game Boy et Game Boy Color : Game Genie (ABC-DEF-GHI) ou GameShark (010238CD). " +
            "Game Boy Advance : Codebreaker (XAAAAAAA YYYY) ou GameShark et Action Replay (XXXXXXXX YYYYYYYY). " +
            "Un code de plusieurs lignes s'écrit d'un bloc.",
        color = RavenMuted, fontSize = 12.sp, lineHeight = 16.sp,
    )
    OutlinedTextField(
        value = name, onValueChange = { name = it.take(60) },
        label = { Text("Nom (facultatif)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = code, onValueChange = { code = it.take(4000); refusal = null },
        label = { Text("Code") }, minLines = 2, modifier = Modifier.fillMaxWidth(),
    )
    refusal?.let { Text(it, color = RavenDanger, fontSize = 12.sp) }
    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        Button(
            onClick = {
                refusal = view.onAdd(name, code)
                if (refusal == null) {
                    name = ""
                    code = ""
                }
            },
            enabled = code.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = RavenAccent, contentColor = RavenBackground),
        ) { Text("Ajouter") }
    }
}
