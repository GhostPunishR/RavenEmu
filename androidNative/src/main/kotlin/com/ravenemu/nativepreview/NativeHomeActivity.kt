package com.ravenemu.nativepreview

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.ravenemu.shared.RavenEmuApp
import com.ravenemu.shared.library.GameEntry
import com.ravenemu.shared.library.GamePlatform
import com.ravenemu.shared.settings.SettingsCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * The SAME Compose library, toolbar, console pages, game details and settings
 * used in the original RavenEmu app (shared/RavenEmuApp.kt).
 *
 * Only the host adapters differ: ROMs/covers belong to androidNative's private
 * repository and games launch the isolated Java/JNI/GL native preview player.
 * Never access the old beta's files or override its application ID.
 */
class NativeHomeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK),
        )

        setContent {
            var games by remember { mutableStateOf<List<GameEntry>>(emptyList()) }
            var covers by remember { mutableStateOf<Map<String, ImageBitmap>>(emptyMap()) }
            var importBusy by remember { mutableStateOf(false) }
            var coverBusy by remember { mutableStateOf(false) }
            var error by remember { mutableStateOf<String?>(null) }
            var pendingCoverKey by remember { mutableStateOf<String?>(null) }
            val scope = rememberCoroutineScope()

            fun refresh() {
                scope.launch {
                    try {
                        val result = withContext(Dispatchers.IO) {
                            val entries = RomLibrary.list(filesDir)
                            val items = entries.map { game ->
                                GameEntry(game.key, game.title, platform(game.system))
                            }
                            // Keep decode work off main and thumbnails small. The
                            // Android preview does not fetch any online artwork.
                            val images = mutableMapOf<String, ImageBitmap>()
                            entries.forEach { game ->
                                try {
                                    CoverRepository.load(filesDir, game.key, 96)
                                        ?.let { images[game.key] = it.asImageBitmap() }
                                } catch (_: IOException) {
                                    // A broken cover never prevents game discovery.
                                }
                            }
                            items to images.toMap()
                        }
                        games = result.first
                        covers = result.second
                        error = null
                    } catch (failure: Exception) {
                        error = "Bibliothèque indisponible : ${failure.message}"
                    }
                }
            }

            val romPicker = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri ->
                if (uri != null) {
                    scope.launch {
                        importBusy = true
                        try {
                            withContext(Dispatchers.IO) {
                                val name = displayName(uri)
                                val bytes = readBounded(uri, RomLibrary.MAX_ROM_BYTES)
                                RomLibrary.importRom(filesDir, name, bytes)
                            }
                            refresh()
                        } catch (failure: Exception) {
                            error = "Import impossible : ${failure.message}"
                        } finally {
                            importBusy = false
                        }
                    }
                }
            }

            val coverPicker = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri ->
                val key = pendingCoverKey
                pendingCoverKey = null
                if (uri != null && key != null) {
                    scope.launch {
                        coverBusy = true
                        try {
                            withContext(Dispatchers.IO) {
                                val exists = RomLibrary.list(filesDir).any { it.key == key }
                                if (!exists) throw IOException("Jeu absent de la bibliothèque")
                                CoverRepository.importImage(
                                    filesDir, key,
                                    readBounded(uri, CoverRepository.MAX_SOURCE_BYTES),
                                )
                            }
                            refresh()
                        } catch (failure: Exception) {
                            error = "Jaquette non importée : ${failure.message}"
                        } finally {
                            coverBusy = false
                        }
                    }
                }
            }

            LaunchedEffect(Unit) { refresh() }

            RavenEmuApp(
                onImportRom = { romPicker.launch(arrayOf("*/*")) },
                onRefreshLibrary = { refresh() },
                libraryGames = games,
                libraryCovers = covers,
                onChooseCover = { key ->
                    pendingCoverKey = key
                    coverPicker.launch(arrayOf("image/*"))
                },
                onRemoveCover = { key ->
                    scope.launch {
                        coverBusy = true
                        try {
                            withContext(Dispatchers.IO) {
                                CoverRepository.remove(filesDir, key)
                            }
                            refresh()
                        } finally {
                            coverBusy = false
                        }
                    }
                },
                coverBusy = coverBusy,
                importBusy = importBusy,
                importError = error,
                // The shared 'Jouer' button opens the native C/JNI player.
                // The Compose shell stays on the library when returning.
                onStartVideo = {},
                onLaunchLibraryGame = { id, _ ->
                    startActivity(Intent(this@NativeHomeActivity, MainActivity::class.java)
                        .putExtra(MainActivity.EXTRA_ROM_KEY, id))
                },
                appVersion = "Native Preview — interface RavenEmu",
                // Unsupported emulator services are visibly non-editable.
                // Do not silently persist settings that the native core ignores.
                settingsCapabilities = SettingsCapabilities(
                    onlineCovers = false,
                    cartridgeHardware = false,
                    deltaSkins = false,
                    haptics = false,
                    diagnostics = false,
                    audioStartupFade = false,
                    wirelessLink = false,
                    console = false,
                    frameBlending = false,
                    rewind = false,
                ),
                onSettingsChange = null,
            )
        }
    }

    private fun platform(system: String): GamePlatform = when (system) {
        "gba" -> GamePlatform.GBA
        "gbc" -> GamePlatform.GBC
        else -> GamePlatform.GB
    }

    private fun displayName(uri: Uri): String {
        try {
            contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
            )?.use {
                if (it.moveToFirst() && !it.isNull(0)) return it.getString(0)
            }
        } catch (_: Exception) { }
        return uri.lastPathSegment ?: "jeu.gb"
    }

    private fun readBounded(uri: Uri, limit: Int): ByteArray {
        val input: InputStream = contentResolver.openInputStream(uri)
            ?: throw IOException("Document introuvable")
        input.use { stream ->
            ByteArrayOutputStream().use { output ->
                val chunk = ByteArray(32 * 1024)
                while (true) {
                    val count = stream.read(chunk)
                    if (count == -1) break
                    if (count == 0) continue
                    if (output.size() > limit - count)
                        throw IOException("Document trop volumineux")
                    output.write(chunk, 0, count)
                }
                return output.toByteArray()
            }
        }
    }
}
