package com.ravenemu.android.saves

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.ravenemu.shared.saves.GbStateSlot
import java.io.ByteArrayOutputStream
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.ravenemu.android.engine.AndroidGbSession
import com.ravenemu.android.player.GbPlayback
import com.ravenemu.android.retro.AchievementsController

/**
 * The four save-state slots of the running game, listed in the game menu.
 * [playing] tells each operation whether a game is loaded and on screen.
 */
internal class GbStateSlotsUi(
    private val applicationContext: Context,
    private val gameSession: AndroidGbSession,
    private val playback: GbPlayback,
    private val scope: CoroutineScope,
) {
    var stateSlots by mutableStateOf(emptyList<GbStateSlot>())
        private set
    var stateSlotsLoading by mutableStateOf(false)
        private set
    var stateOperationBusy by mutableStateOf(false)
        private set
    /** True while a slot is restored: the player loop stays stopped. */
    var restoringState by mutableStateOf(false)
        private set
    /** Bumped after each restore attempt, so the player loop starts again. */
    var stateRevision by mutableStateOf(0)
        private set
    /** The last state load can be undone (see [undoLoad]). */
    var loadUndoable by mutableStateOf(false)
        private set

    fun refreshSlots() {
        loadUndoable = gameSession.loadUndoable
        stateSlotsLoading = true
        stateSlots = emptyList()
        scope.launch {
            try {
                stateSlots = withContext(Dispatchers.IO) {
                    val dateFormat = DateFormat.getDateTimeInstance(
                        DateFormat.MEDIUM, DateFormat.SHORT,
                    )
                    gameSession.listStateSlots().map { stored ->
                        val thumbnail = stored.preview?.let { bytes ->
                            val size = GbStatePreview.size(bytes) ?: return@let null
                            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                            if (bounds.outWidth != size.width || bounds.outHeight != size.height) {
                                return@let null
                            }
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                                BitmapFactory.Options().apply {
                                    inSampleSize = GbStatePreview.sampleSize(size)
                                    inPreferredConfig = Bitmap.Config.ARGB_8888
                                })?.asImageBitmap()
                        }
                        GbStateSlot(
                            index = stored.slot,
                            savedAt = if (!stored.occupied) null else
                                stored.savedAtMillis?.let {
                                    dateFormat.format(Date(it))
                                } ?: "Date inconnue",
                            thumbnail = thumbnail,
                        )
                    }
                }
            } catch (error: Exception) {
                Log.e("RavenEmuSaves", "Cannot list save slots", error)
                Toast.makeText(
                    applicationContext,
                    "Impossible de lire les emplacements",
                    Toast.LENGTH_LONG,
                ).show()
            } finally {
                stateSlotsLoading = false
            }
        }
    }

    fun save(slot: Int, playing: Boolean) {
        if (!stateOperationBusy && playing) {
            stateOperationBusy = true
            // Private copy: the player reuses its images in rotation.
            val screenshot = playback.frame?.asAndroidBitmap()?.copy(Bitmap.Config.ARGB_8888, false)
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val preview = screenshot?.let { image ->
                            val miniature = Bitmap.createScaledBitmap(
                                // Native size: 160x144 (GB/GBC), 240x160 (GBA).
                                image, image.width, image.height, false,
                            )
                            try {
                                ByteArrayOutputStream().use { output ->
                                    if (miniature.compress(
                                            Bitmap.CompressFormat.PNG, 100, output,
                                        )
                                    ) output.toByteArray() else null
                                }
                            } finally {
                                if (miniature !== image) miniature.recycle()
                            }
                        }
                        gameSession.saveStateSlot(slot, preview)
                    }
                    Toast.makeText(
                        applicationContext,
                        "État sauvegardé (emplacement ${slot + 1})",
                        Toast.LENGTH_SHORT,
                    ).show()
                    refreshSlots()
                } catch (error: Exception) {
                    Log.e("RavenEmuSaves", "Save state failed", error)
                    Toast.makeText(
                        applicationContext,
                        "Échec du save state : ${error.message ?: "erreur inconnue"}",
                        Toast.LENGTH_LONG,
                    ).show()
                } finally {
                    stateOperationBusy = false
                }
            }
        }
    }

    fun load(slot: Int, playing: Boolean, retro: AchievementsController) {
        if (retro.stateLoadLocked) {
            Toast.makeText(applicationContext,
                "Mode Hardcore : le chargement d'un état est désactivé.",
                Toast.LENGTH_LONG).show()
        } else if (!stateOperationBusy && playing) {
            stateOperationBusy = true
            restoringState = true
            playback.activeAudio?.stopNow()
            gameSession.releaseAllButtons()
            scope.launch {
                try {
                    retro.loadState(slot)
                    Toast.makeText(
                        applicationContext, "État chargé (emplacement ${slot + 1})",
                        Toast.LENGTH_SHORT,
                    ).show()
                } catch (error: Exception) {
                    Log.e("RavenEmuSaves", "Load state failed", error)
                    Toast.makeText(
                        applicationContext,
                        "Chargement impossible : ${error.message ?: "erreur inconnue"}",
                        Toast.LENGTH_LONG,
                    ).show()
                } finally {
                    loadUndoable = gameSession.loadUndoable
                    stateRevision++
                    restoringState = false
                    stateOperationBusy = false
                }
            }
        }
    }

    /** Puts the game back as it was before the last state load. */
    fun undoLoad(playing: Boolean, retro: AchievementsController) {
        if (retro.stateLoadLocked) {
            Toast.makeText(applicationContext,
                "Mode Hardcore : le chargement d'un état est désactivé.",
                Toast.LENGTH_LONG).show()
        } else if (!stateOperationBusy && playing) {
            stateOperationBusy = true
            restoringState = true
            playback.activeAudio?.stopNow()
            gameSession.releaseAllButtons()
            scope.launch {
                try {
                    retro.undoLoadState()
                    Toast.makeText(applicationContext, "Chargement annulé", Toast.LENGTH_SHORT).show()
                } catch (error: Exception) {
                    Log.e("RavenEmuSaves", "Undo load failed", error)
                    Toast.makeText(
                        applicationContext,
                        "Annulation impossible : ${error.message ?: "erreur inconnue"}",
                        Toast.LENGTH_LONG,
                    ).show()
                } finally {
                    loadUndoable = gameSession.loadUndoable
                    stateRevision++
                    restoringState = false
                    stateOperationBusy = false
                }
            }
        }
    }

    fun delete(slot: Int, playing: Boolean) {
        if (!stateOperationBusy && playing) {
            stateOperationBusy = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { gameSession.deleteStateSlot(slot) }
                    Toast.makeText(
                        applicationContext,
                        "Emplacement ${slot + 1} supprimé",
                        Toast.LENGTH_SHORT,
                    ).show()
                    refreshSlots()
                } catch (error: Exception) {
                    Log.e("RavenEmuSaves", "Delete state failed", error)
                    Toast.makeText(
                        applicationContext,
                        "Suppression impossible : ${error.message ?: "erreur inconnue"}",
                        Toast.LENGTH_LONG,
                    ).show()
                } finally {
                    stateOperationBusy = false
                }
            }
        }
    }
}
