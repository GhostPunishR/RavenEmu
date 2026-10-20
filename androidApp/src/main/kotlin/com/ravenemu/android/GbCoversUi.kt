package com.ravenemu.android

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.ravenemu.shared.GameEntry
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Library covers: images chosen by the user, and the optional online search
 * of missing ones. The activity registers [coverPicker].
 */
internal class GbCoversUi(
    private val applicationContext: Context,
    filesDir: File,
    contentResolver: ContentResolver,
    private val romLibrary: GbRomLibrary,
    private val scope: CoroutineScope,
) {
    val coverStore = GbCoverStore(File(filesDir, "gb-covers"), contentResolver)
    private val autoCoverDownloader = GbAutoCoverDownloader()
    private val coverPreferences =
        applicationContext.getSharedPreferences("ravenemu_cover_rules", Context.MODE_PRIVATE)
    var ignoredAutoCovers by mutableStateOf(
        coverPreferences.getStringSet("disabled_auto_cover_ids", emptySet())
            ?.toSet() ?: emptySet()
    )
        private set
    // Jeux que l'index de libretro ne connaît pas. Les retenir évite de
    // retélécharger l'index entier à chaque lancement pour les chercher
    // à nouveau ; désactiver puis réactiver la recherche les réarme.
    private var unmatchedAutoCovers by mutableStateOf(
        coverPreferences.getStringSet("unmatched_auto_cover_ids", emptySet())
            ?.toSet() ?: emptySet()
    )
    var autoCoverLoading by mutableStateOf(false)
        private set
    var libraryCovers by mutableStateOf(emptyMap<String, ImageBitmap>())
    var coverBusy by mutableStateOf(false)
        private set
    private var pendingCoverId by mutableStateOf<String?>(null)
    lateinit var coverPicker: ManagedActivityResultLauncher<Array<String>, Uri?>

    /** Games the catalogue did not know are looked up again on the next search. */
    fun forgetUnmatched() {
        if (unmatchedAutoCovers.isEmpty()) return
        unmatchedAutoCovers = emptySet()
        coverPreferences.edit().remove("unmatched_auto_cover_ids").apply()
    }

    /**
     * Looks up the missing covers in the online catalogue, only when the user
     * enabled it ([onlineCovers]). Disabled by default: the requests tell a
     * third-party server which games are in the library.
     */
    suspend fun fillMissingOnline(libraryGames: List<GameEntry>, onlineCovers: Boolean) {
        if (!onlineCovers) {
            forgetUnmatched()
            return
        }
        if (libraryGames.isNotEmpty()) {
            autoCoverLoading = true
            try {
                // The downloader limits each pass to 30 images. Scan
                // every batch of a newly imported folder, not just the
                // first 30 games, without blocking the Compose UI.
                val entries = withContext(Dispatchers.IO) { romLibrary.list() }
                for (batch in entries.chunked(30)) {
                    val pass = withContext(Dispatchers.IO) {
                        autoCoverDownloader.fillMissing(
                            batch, coverStore, ignoredAutoCovers + unmatchedAutoCovers,
                        )
                    }
                    // Manual artwork wins over a concurrent download.
                    libraryCovers = pass.found.mapValues { it.value.asImageBitmap() } + libraryCovers
                    if (pass.unmatched.isNotEmpty()) {
                        unmatchedAutoCovers = unmatchedAutoCovers + pass.unmatched
                        coverPreferences.edit()
                            .putStringSet("unmatched_auto_cover_ids", unmatchedAutoCovers)
                            .apply()
                    }
                }
            } catch (error: Exception) {
                Log.w("RavenEmuCover", "Automatic cover lookup unavailable", error)
            } finally {
                autoCoverLoading = false
            }
        }
    }

    fun choose(id: String, libraryGames: List<GameEntry>) {
        if (!coverBusy && libraryGames.any { it.id == id }) {
            pendingCoverId = id
            coverPicker.launch(arrayOf("image/*"))
        }
    }

    /** Result of [coverPicker]: stores the chosen image as the game's cover. */
    fun saveChosen(uri: Uri?) {
        val id = pendingCoverId
        pendingCoverId = null
        if (uri != null && id != null && !coverBusy) {
            coverBusy = true
            scope.launch {
                try {
                    val cover = withContext(Dispatchers.IO) {
                        check(romLibrary.list().any { it.id == id }) {
                            "Ce jeu n'est plus présent dans la bibliothèque."
                        }
                        coverStore.save(id, uri).asImageBitmap()
                    }
                    libraryCovers = libraryCovers + (id to cover)
                    if (id in ignoredAutoCovers) {
                        ignoredAutoCovers = ignoredAutoCovers - id
                        coverPreferences.edit()
                            .putStringSet("disabled_auto_cover_ids", ignoredAutoCovers)
                            .apply()
                    }
                    Toast.makeText(
                        applicationContext, "Jaquette enregistrée",
                        Toast.LENGTH_SHORT,
                    ).show()
                } catch (error: Exception) {
                    Log.e("RavenEmuLibrary", "Unable to save custom cover", error)
                    Toast.makeText(
                        applicationContext,
                        "Jaquette non enregistrée : ${error.message ?: "image invalide"}",
                        Toast.LENGTH_LONG,
                    ).show()
                } finally {
                    coverBusy = false
                }
            }
        }
    }

    /** Removes the game's cover and keeps the online search from bringing one back. */
    fun remove(id: String, libraryGames: List<GameEntry>) {
        if (!coverBusy && libraryGames.any { it.id == id }) {
            coverBusy = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { coverStore.delete(id) }
                    libraryCovers = libraryCovers - id
                    ignoredAutoCovers = ignoredAutoCovers + id
                    coverPreferences.edit()
                        .putStringSet("disabled_auto_cover_ids", ignoredAutoCovers)
                        .apply()
                } catch (error: Exception) {
                    Log.e("RavenEmuLibrary", "Unable to remove cover", error)
                    Toast.makeText(
                        applicationContext, "Impossible de retirer la jaquette",
                        Toast.LENGTH_LONG,
                    ).show()
                } finally {
                    coverBusy = false
                }
            }
        }
    }
}
