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
    private val linkSession: AndroidLinkSession,
    private val operations: LibraryOperationGate,
    private val romLibrary: GbRomLibrary,
    private val saveTransfer: GbSaveTransfer,
    private val scope: CoroutineScope,
) {
    var saveTransferBusy by mutableStateOf(false)
        private set
    var saveImportReview by mutableStateOf<GbSaveImportReview?>(null)
        private set
    private var pickerLease: LibraryOperationGate.Lease? = null
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
        val lease = pickerLease ?: return
        pickerLease = null
        if (uri != null && request != null) {
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        // The active core may still own unsaved battery RAM
                        // after leaving the player; flush before exporting.
                        flushSessions()
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
                    finish(lease)
                }
            }
        } else {
            finish(lease)
        }
    }

    /** Result of [exportBattery]: writes the chosen game's .sav there. */
    fun writeBattery(uri: Uri?) {
        val request = pendingExport
        pendingExport = null
        val lease = pickerLease ?: return
        pickerLease = null
        if (uri != null && request != null) {
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        flushSessions()
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
                    finish(lease)
                }
            }
        } else {
            finish(lease)
        }
    }

    /** Result of [importSavePicker]: checks the file and prepares the review. */
    fun reviewImport(uri: Uri?, libraryGames: List<GameEntry>) {
        val id = pendingImportGame
        pendingImportGame = null
        val lease = pickerLease ?: return
        pickerLease = null
        if (uri != null && id != null) {
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
                    finish(lease)
                }
            }
        } else {
            finish(lease)
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
        if (payload != null) {
            val lease = begin(playerOpen) ?: return
            // Drop the review before any disk mutation, and never
            // apply a package if the target game was removed.
            pendingSavePayload = null
            saveImportReview = null
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        check(romLibrary.list().any { it.id == payload.romKey }) {
                            "La ROM associée n'existe plus."
                        }
                        linkSession.unload()
                        gameSession.importSave(payload.romKey) { saveTransfer.apply(payload) }
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
                    finish(lease)
                }
            }
        }
    }

    fun exportGameBackup(id: String, libraryGames: List<GameEntry>, playerOpen: Boolean) {
        if (libraryGames.any { it.id == id }) {
            val lease = begin(playerOpen) ?: return
            val gameName = exportName(libraryGames.first { it.id == id }.title)
            scope.launch {
                var handedToPicker = false
                try {
                    withContext(Dispatchers.IO) {
                        flushSessions()
                        check(saveTransfer.hasAnySave(id)) {
                            "Aucune sauvegarde à exporter pour ce jeu."
                        }
                    }
                    pendingExport = id to "zip"
                    pickerLease = lease
                    exportBackup.launch("$gameName-ravenemu-saves.zip")
                    handedToPicker = true
                } catch (error: Exception) {
                    Toast.makeText(applicationContext,
                        error.message ?: "Export impossible", Toast.LENGTH_LONG).show()
                } finally {
                    if (!handedToPicker) {
                        pickerLease = null
                        pendingExport = null
                        finish(lease)
                    }
                }
            }
        }
    }

    fun exportBatterySave(id: String, libraryGames: List<GameEntry>, playerOpen: Boolean) {
        if (libraryGames.any { it.id == id }) {
            val lease = begin(playerOpen) ?: return
            val gameName = exportName(libraryGames.first { it.id == id }.title)
            scope.launch {
                var handedToPicker = false
                try {
                    withContext(Dispatchers.IO) {
                        flushSessions()
                        check(saveTransfer.hasBattery(id)) {
                            "Ce jeu n'a pas encore de sauvegarde .sav."
                        }
                    }
                    pendingExport = id to "sav"
                    pickerLease = lease
                    exportBattery.launch("$gameName.sav")
                    handedToPicker = true
                } catch (error: Exception) {
                    Toast.makeText(applicationContext,
                        error.message ?: "Export .sav impossible",
                        Toast.LENGTH_LONG).show()
                } finally {
                    if (!handedToPicker) {
                        pickerLease = null
                        pendingExport = null
                        finish(lease)
                    }
                }
            }
        }
    }

    fun importGameSave(id: String, libraryGames: List<GameEntry>, playerOpen: Boolean) {
        if (libraryGames.any { it.id == id }) {
            val lease = begin(playerOpen) ?: return
            try {
                pendingSavePayload = null
                saveImportReview = null
                pendingImportGame = id
                pickerLease = lease
                importSavePicker.launch(arrayOf("application/zip",
                    "application/octet-stream", "*/*"))
            } catch (error: Exception) {
                pendingImportGame = null
                pickerLease = null
                finish(lease)
                Toast.makeText(applicationContext, "Import impossible", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun begin(playerOpen: Boolean): LibraryOperationGate.Lease? {
        if (playerOpen || saveTransferBusy) return null
        val lease = operations.tryAcquire() ?: return null
        saveTransferBusy = true
        return lease
    }

    private fun finish(lease: LibraryOperationGate.Lease) {
        saveTransferBusy = false
        lease.close()
    }

    /** A link pair can still be retained after a failed/asynchronous exit. */
    private fun flushSessions() {
        linkSession.unload()
        gameSession.saveBatteryIfDirty()
    }

    /** File name stem for an export: the ROM name without extension or symbols. */
    private fun exportName(title: String): String = title
        .substringBeforeLast('.')
        .replace(Regex("[^A-Za-z0-9_-]+"), "-")
        .take(48)
        .ifBlank { "GameBoy" }
}
