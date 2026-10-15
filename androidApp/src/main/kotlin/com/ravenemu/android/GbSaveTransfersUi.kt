package com.ravenemu.android

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ravenemu.shared.GameEntry
import com.ravenemu.shared.GbSaveImportReview
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Save transfers of library games: .zip and .sav exports, and imports
 * reviewed before they replace anything. No transfer starts while the player
 * is open ([playerOpen]). The activity registers the three system pickers.
 */
internal class GbSaveTransfersUi(
    private val applicationContext: Context,
    private val contentResolver: ContentResolver,
    private val gameSession: AndroidGbSession,
    private val romLibrary: GbRomLibrary,
    private val saveTransfer: GbSaveTransfer,
    private val scope: CoroutineScope,
) {
    var saveTransferBusy by mutableStateOf(false)
        private set
    var saveImportReview by mutableStateOf<GbSaveImportReview?>(null)
        private set
    private var pendingExport by mutableStateOf<Pair<String, String>?>(null)
    private var pendingImportGame by mutableStateOf<String?>(null)
    private var pendingSavePayload by mutableStateOf<GbSaveTransfer.ImportPayload?>(null)
    lateinit var exportBackup: ManagedActivityResultLauncher<String, Uri?>
    lateinit var exportBattery: ManagedActivityResultLauncher<String, Uri?>
    lateinit var importSavePicker: ManagedActivityResultLauncher<Array<String>, Uri?>

    /** Result of [exportBackup]: writes the chosen game's .zip there. */
    fun writeBackup(uri: Uri?) {
        val request = pendingExport
        pendingExport = null
        if (uri != null && request != null && !saveTransferBusy) {
            saveTransferBusy = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        // The active core may still own unsaved battery RAM
                        // after leaving the player; flush before exporting.
                        gameSession.saveBatteryIfDirty()
                        contentResolver.openOutputStream(uri)?.use {
                            saveTransfer.exportBundle(request.first, it)
                        } ?: error("Destination d'export inaccessible.")
                    }
                    Toast.makeText(applicationContext,
                        "Sauvegardes exportées", Toast.LENGTH_SHORT).show()
                } catch (error: Exception) {
                    Log.e("RavenEmuTransfer", "Unable to export full backup", error)
                    Toast.makeText(applicationContext,
                        "Export impossible : ${error.message ?: "erreur inconnue"}",
                        Toast.LENGTH_LONG).show()
                } finally {
                    saveTransferBusy = false
                }
            }
        }
    }

    /** Result of [exportBattery]: writes the chosen game's .sav there. */
    fun writeBattery(uri: Uri?) {
        val request = pendingExport
        pendingExport = null
        if (uri != null && request != null && !saveTransferBusy) {
            saveTransferBusy = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        gameSession.saveBatteryIfDirty()
                        contentResolver.openOutputStream(uri)?.use {
                            saveTransfer.exportBattery(request.first, it)
                        } ?: error("Destination d'export inaccessible.")
                    }
                    Toast.makeText(applicationContext,
                        "Fichier .sav exporté", Toast.LENGTH_SHORT).show()
                } catch (error: Exception) {
                    Log.e("RavenEmuTransfer", "Unable to export .sav", error)
                    Toast.makeText(applicationContext,
                        "Export .sav impossible : ${error.message ?: "erreur inconnue"}",
                        Toast.LENGTH_LONG).show()
                } finally {
                    saveTransferBusy = false
                }
            }
        }
    }

    /** Result of [importSavePicker]: checks the file and prepares the review. */
    fun reviewImport(uri: Uri?, libraryGames: List<GameEntry>) {
        val id = pendingImportGame
        pendingImportGame = null
        if (uri != null && id != null && !saveTransferBusy) {
            saveTransferBusy = true
            scope.launch {
                try {
                    val (payload, conflicts) = withContext(Dispatchers.IO) {
                        check(romLibrary.list().any { it.id == id }) {
                            "Ce jeu n'est plus dans la bibliothèque."
                        }
                        val filename = contentResolver.query(
                            uri, arrayOf(OpenableColumns.DISPLAY_NAME),
                            null, null, null,
                        )?.use { cursor ->
                            if (cursor.moveToFirst() && !cursor.isNull(0))
                                cursor.getString(0) else null
                        } ?: error("Nom de fichier inaccessible.")
                        val candidate = contentResolver.openInputStream(uri)?.use {
                            saveTransfer.previewImport(it, id, filename)
                        } ?: error("Sauvegarde inaccessible.")
                        candidate to saveTransfer.conflicts(candidate)
                    }
                    val gameName = libraryGames.firstOrNull { it.id == id }?.title
                        ?: "Jeu Game Boy"
                    pendingSavePayload = payload
                    saveImportReview = GbSaveImportReview(
                        gameTitle = gameName,
                        hasBattery = payload.hasBattery,
                        slots = payload.stateSlots.map { it + 1 },
                        conflicts = conflicts,
                        rawSav = payload.rawBattery,
                    )
                } catch (error: Exception) {
                    Log.e("RavenEmuTransfer", "Invalid save import", error)
                    Toast.makeText(applicationContext,
                        "Import refusé : ${error.message ?: "fichier invalide"}",
                        Toast.LENGTH_LONG).show()
                } finally {
                    saveTransferBusy = false
                }
            }
        }
    }

    fun cancelImport() {
        saveImportReview = null
        pendingSavePayload = null
    }

    /**
     * Applies the reviewed import. [onGameReplaced] receives the game whose
     * saves were touched, whether or not the import succeeded.
     */
    fun confirmImport(playerOpen: Boolean, onGameReplaced: (romKey: String) -> Unit) {
        val payload = pendingSavePayload
        if (payload != null && !saveTransferBusy && !playerOpen) {
            // Drop the review before any disk mutation, and never
            // apply a package if the target game was removed.
            pendingSavePayload = null
            saveImportReview = null
            saveTransferBusy = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        check(romLibrary.list().any { it.id == payload.romKey }) {
                            "La ROM associée n'existe plus."
                        }
                        gameSession.releaseForSaveImport(payload.romKey)
                        saveTransfer.apply(payload)
                    }
                    onGameReplaced(payload.romKey)
                    Toast.makeText(applicationContext,
                        "Sauvegardes importées avec succès",
                        Toast.LENGTH_SHORT).show()
                } catch (error: Exception) {
                    Log.e("RavenEmuTransfer", "Save import failed", error)
                    onGameReplaced(payload.romKey)
                    Toast.makeText(applicationContext,
                        "Import impossible : ${error.message ?: "erreur inconnue"}",
                        Toast.LENGTH_LONG).show()
                } finally {
                    saveTransferBusy = false
                }
            }
        }
    }

    fun exportGameBackup(id: String, libraryGames: List<GameEntry>, playerOpen: Boolean) {
        if (!saveTransferBusy && !playerOpen && libraryGames.any { it.id == id }) {
            saveTransferBusy = true
            val gameName = exportName(libraryGames.first { it.id == id }.title)
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        gameSession.saveBatteryIfDirty()
                        check(saveTransfer.hasAnySave(id)) {
                            "Aucune sauvegarde à exporter pour ce jeu."
                        }
                    }
                    pendingExport = id to "zip"
                    exportBackup.launch("$gameName-ravenemu-saves.zip")
                } catch (error: Exception) {
                    Toast.makeText(applicationContext,
                        error.message ?: "Export impossible", Toast.LENGTH_LONG).show()
                } finally {
                    saveTransferBusy = false
                }
            }
        }
    }

    fun exportBatterySave(id: String, libraryGames: List<GameEntry>, playerOpen: Boolean) {
        if (!saveTransferBusy && !playerOpen && libraryGames.any { it.id == id }) {
            saveTransferBusy = true
            val gameName = exportName(libraryGames.first { it.id == id }.title)
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        gameSession.saveBatteryIfDirty()
                        check(saveTransfer.hasBattery(id)) {
                            "Ce jeu n'a pas encore de sauvegarde .sav."
                        }
                    }
                    pendingExport = id to "sav"
                    exportBattery.launch("$gameName.sav")
                } catch (error: Exception) {
                    Toast.makeText(applicationContext,
                        error.message ?: "Export .sav impossible",
                        Toast.LENGTH_LONG).show()
                } finally {
                    saveTransferBusy = false
                }
            }
        }
    }

    fun importGameSave(id: String, libraryGames: List<GameEntry>, playerOpen: Boolean) {
        if (!saveTransferBusy && !playerOpen && libraryGames.any { it.id == id }) {
            pendingSavePayload = null
            saveImportReview = null
            pendingImportGame = id
            importSavePicker.launch(arrayOf("application/zip",
                "application/octet-stream", "*/*"))
        }
    }

    /** File name stem for an export: the ROM name without extension or symbols. */
    private fun exportName(title: String): String = title
        .substringBeforeLast('.')
        .replace(Regex("[^A-Za-z0-9_-]+"), "-")
        .take(48)
        .ifBlank { "GameBoy" }
}
