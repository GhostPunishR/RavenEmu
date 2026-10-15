package com.ravenemu.android

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
import com.ravenemu.shared.GbStateSlot
import java.io.ByteArrayOutputStream
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    fun refreshSlots() {
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
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                ?.asImageBitmap()
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
        if (retro.hardcoreActive) {
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
                    withContext(Dispatchers.IO) { gameSession.loadStateSlot(slot) }
                    try { retro.resetProgress() }
                    catch (error: Exception) {
                        Log.w("RavenEmuRetro", "RA reset after state load failed", error)
                    }
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
