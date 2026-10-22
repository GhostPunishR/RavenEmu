package com.ravenemu.android

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import com.ravenemu.shared.GameEntry
import com.ravenemu.shared.GbFolderImportSummary
import com.ravenemu.shared.LoadedGameInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The game library as the screens show it: the stored games, their import
 * and refresh, and the games loaded from it. One operation runs at a time
 * ([operations] is shared with the save transfers), never while the player
 * is open.
 */
internal class GbLibraryUi(
    private val applicationContext: Context,
    private val contentResolver: ContentResolver,
    private val romLibrary: GbRomLibrary,
    private val covers: GbCoversUi,
    private val gameSession: AndroidGbSession,
    private val linkSession: AndroidLinkSession,
    private val retro: AchievementsController,
    private val player: GbPlayerUi,
    private val operations: LibraryOperationGate,
    private val scope: CoroutineScope,
) {
    var games by mutableStateOf(emptyList<GameEntry>())
        private set
    var loadedGame by mutableStateOf<LoadedGameInfo?>(null)
        private set
    var activeGameId by mutableStateOf<String?>(null)
        private set
    // Each refresh from the library menu searches the missing covers again.
    private var refreshes by mutableStateOf(0)
    var loading by mutableStateOf(false)
        private set
    var importError by mutableStateOf<String?>(null)
        private set
    var folderImportProgress by mutableStateOf<String?>(null)
        private set
    var folderImportSummary by mutableStateOf<GbFolderImportSummary?>(null)
        private set

    @Composable
    fun Effects(onlineCovers: Boolean) {
        LaunchedEffect(this) {
            try {
                reload()
            } catch (error: Exception) {
                Log.e("RavenEmuLibrary", "Cannot open library index", error)
            }
        }
        // Only run a cover search when the user enabled it, and then only
        // when the library changes, an excluded ROM is explicitly
        // re-enabled or the library is refreshed. Recomposition/video
        // frames never initiate network calls.
        LaunchedEffect(games, covers.ignoredAutoCovers, onlineCovers, refreshes) {
            covers.fillMissingOnline(games, onlineCovers)
        }
    }

    private fun storedGames(): List<GameEntry> =
        romLibrary.list().map { GameEntry(it.id, it.filename, it.platform) }

    // Games and covers as stored; throws when the index cannot be read.
    private suspend fun reload() {
        val (stored, coverImages) = withContext(Dispatchers.IO) {
            val entries = storedGames()
            val pictures = entries.mapNotNull { entry ->
                try {
                    covers.coverStore.read(entry.id)?.asImageBitmap()?.let { entry.id to it }
                } catch (error: Exception) {
                    Log.w("RavenEmuLibrary", "Cannot read cover for ${entry.id.take(12)}", error)
                    null
                }
            }.toMap()
            entries to pictures
        }
        games = stored
        covers.libraryCovers = coverImages
    }

    /**
     * Runs [task] as the only library operation, while the player is closed:
     * [start] then [progress] show at once, the task reports its own errors.
     */
    private fun exclusive(progress: String? = null, start: () -> Unit = {}, task: suspend () -> Unit) {
        if (loading || player.open) return
        val operation = operations.tryAcquire() ?: return
        loading = true
        importError = null
        start()
        folderImportProgress = progress
        scope.launch {
            try {
                task()
            } finally {
                folderImportProgress = null
                loading = false
                operation.close()
            }
        }
    }

    // OpenDocumentTree grants temporary read access to just the
    // chosen folder. All accepted ROMs are copied to app-private
    // storage; no broad storage permission or persisted URI required.
    fun importFolder(uri: Uri?) {
        if (uri == null) return
        exclusive("Analyse du dossier…", start = { folderImportSummary = null }) {
            try {
                val (result, entries) = withContext(Dispatchers.IO) {
                    val imported = GbFolderImporter(contentResolver, romLibrary)
                        .importTree(uri) { scanned ->
                            if (scanned == 1 || scanned % 5 == 0) {
                                scope.launch {
                                    folderImportProgress =
                                        "Importation du dossier… $scanned ROM examinées"
                                }
                            }
                        }
                    imported to storedGames()
                }
                games = entries
                folderImportSummary = GbFolderImportSummary(
                    added = result.added,
                    duplicate = result.duplicate,
                    invalid = result.invalid,
                    unreadable = result.unreadable,
                    otherFiles = result.otherFiles,
                    scannedFolders = result.folders,
                    limited = result.limited,
                )
            } catch (error: Exception) {
                Log.e("RavenEmuFolder", "Folder ROM import failed", error)
                importError = "Importation du dossier impossible : " +
                    (error.message ?: "erreur inconnue")
                // Even a partial import already committed its
                // valid ROMs. Refresh the library in that case.
                games = withContext(Dispatchers.IO) { storedGames() }
            }
        }
    }

    fun dismissFolderImportSummary() {
        folderImportSummary = null
    }

    /** Imports one ROM and loads it, ready to play. */
    fun importRom(uri: Uri?) {
        if (uri == null) return
        exclusive {
            try {
                val (info, stored, rom) = withContext(Dispatchers.IO) {
                    // A pair just left behind writes its saves first.
                    linkSession.unload()
                    val (name, bytes) = romLibrary.readSelected(uri)
                    // Validate with native RavenGB before indexing the cartridge.
                    val info = gameSession.loadBytes(name, bytes)
                    val stored = romLibrary.add(name, bytes)
                    Triple(info, stored, bytes)
                }
                loadedGame = info
                activeGameId = stored.id
                try { retro.selectRom(rom, info.platform) }
                catch (error: Exception) {
                    Log.w("RavenEmuRetro", "RA identify failed after import", error)
                }
                games = withContext(Dispatchers.IO) { storedGames() }
            } catch (exception: Exception) {
                importError = exception.message ?: "Échec du chargement de la ROM."
            }
        }
    }

    fun refresh() = exclusive(progress = "Actualisation de la bibliothèque…") {
        try {
            reload()
            // Games the cover index did not know are searched again.
            covers.forgetUnmatched()
            refreshes++
            val count = games.size
            Toast.makeText(applicationContext,
                "Bibliothèque actualisée : $count ${if (count > 1) "jeux" else "jeu"}",
                Toast.LENGTH_SHORT).show()
        } catch (error: Exception) {
            Log.e("RavenEmuLibrary", "Library refresh failed", error)
            importError = "Actualisation impossible : " +
                (error.message ?: "erreur inconnue")
        }
    }

    /** Loads the stored game [id] unless it already is, then [onReady]. */
    fun launch(id: String, onReady: () -> Unit) = exclusive {
        try {
            if (activeGameId != id || loadedGame == null) {
                val entry = games.firstOrNull { it.id == id }
                    ?: error("Ce jeu n'est plus dans la bibliothèque.")
                val (result, rom) = withContext(Dispatchers.IO) {
                    // A pair just left behind writes its saves first.
                    linkSession.unload()
                    val rom = romLibrary.readRom(id)
                    gameSession.loadBytes(entry.title, rom) to rom
                }
                loadedGame = result
                activeGameId = id
                try { retro.selectRom(rom, result.platform) }
                catch (error: Exception) {
                    Log.w("RavenEmuRetro", "RA identify failed on launch", error)
                }
            }
            onReady()
        } catch (error: Exception) {
            Log.e("RavenEmuLibrary", "Cannot load stored GB ROM", error)
            importError = error.message ?: "Impossible de lancer ce jeu."
        }
    }

    /** Loads two stored games joined by the link cable, then [onReady]. */
    fun launchLinked(firstId: String, secondId: String, onReady: () -> Unit) = exclusive {
        try {
            val ids = listOf(firstId, secondId)
            val entries = ids.map { id ->
                games.firstOrNull { it.id == id }
                    ?: error("Ce jeu n'est plus dans la bibliothèque.")
            }
            val infos = withContext(Dispatchers.IO) {
                val roms = entries.map { it.title to romLibrary.readRom(it.id) }
                // A game held by the single player writes its save and
                // leaves memory: only one copy of a save may run at once.
                for (id in ids) gameSession.releaseForSaveImport(id)
                linkSession.load(roms)
            }
            if (activeGameId in ids) {
                activeGameId = null
                loadedGame = null
            }
            player.showLinked(infos)
            onReady()
        } catch (error: Exception) {
            Log.e("RavenEmuLink", "Cannot load linked games", error)
            importError = error.message ?: "Impossible de relier ces jeux."
        }
    }

    /** The save of [romKey] was replaced: its game is loaded again at launch. */
    fun forgetLoaded(romKey: String) {
        if (activeGameId == romKey) {
            activeGameId = null
            loadedGame = null
        }
    }
}
