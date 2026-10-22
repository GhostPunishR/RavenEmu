package com.ravenemu.android

import android.content.Context
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ravenemu.android.deltaskin.DeltaSkinControllerConfiguration
import com.ravenemu.android.deltaskin.DeltaSkinErrorCode
import com.ravenemu.android.deltaskin.DeltaSkins
import com.ravenemu.shared.DeltaSkinEntry
import com.ravenemu.shared.GamePlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Delta skins as the screens show them: the installed list, and the skin of
 * the running game. The activity registers [skinPicker].
 */
internal class DeltaSkinsUi(
    private val applicationContext: Context,
    private val scope: CoroutineScope,
) {
    private val deltaSkins = DeltaSkins(applicationContext)
    var entries by mutableStateOf(emptyList<DeltaSkinEntry>())
        private set
    /** The skin of the running game; without one, the classic controls show. */
    var configuration by mutableStateOf<DeltaSkinControllerConfiguration?>(null)
        private set
    lateinit var skinPicker: ManagedActivityResultLauncher<Array<String>, Uri?>

    @Composable
    fun Effects(platform: GamePlatform?, haptics: Boolean) {
        LaunchedEffect(Unit) { refresh() }
        LaunchedEffect(platform, entries, haptics) {
            configuration = if (platform == null) null else runCatching {
                withContext(Dispatchers.IO) { deltaSkins.configuration(platform, haptics) }
            }.getOrNull()
        }
    }

    private fun refresh() {
        scope.launch {
            entries = runCatching { withContext(Dispatchers.IO) { deltaSkins.entries() } }
                .onFailure { Log.w("RavenEmuSkins", "Cannot list skins", it) }
                .getOrDefault(emptyList())
        }
    }

    fun import(uri: Uri?) {
        if (uri != null) scope.launch {
            val message = runCatching { withContext(Dispatchers.IO) { deltaSkins.import(uri) } }
                .getOrElse { "Importation du skin impossible" }
            Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
            refresh()
        }
    }

    fun toggle(id: String) {
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { deltaSkins.toggle(id) } }
            refresh()
        }
    }

    fun delete(id: String) {
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { deltaSkins.delete(id) } }
            Toast.makeText(applicationContext, "Skin supprimé", Toast.LENGTH_SHORT).show()
            refresh()
        }
    }

    /** [skin] failed on [platform]: that console goes back to the classic controls. */
    fun fallBack(code: DeltaSkinErrorCode, platform: GamePlatform?, skin: DeltaSkinControllerConfiguration) {
        Log.w("RavenEmuSkins", "Skin fallback: $code")
        scope.launch(Dispatchers.IO) {
            if (platform != null) deltaSkins.fallback(platform, skin.skinSha256)
        }
        configuration = null
        Toast.makeText(applicationContext,
            "Skin invalide : commandes classiques restaurées",
            Toast.LENGTH_LONG).show()
        refresh()
    }
}
