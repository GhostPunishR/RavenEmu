package com.ravenemu.android.saves

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ravenemu.android.engine.AndroidGbSession
import com.ravenemu.android.engine.NativeCore
import com.ravenemu.shared.library.LoadedGameInfo
import com.ravenemu.shared.saves.GbCheat
import com.ravenemu.shared.saves.GbCheatList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The running game's cheats, from the game menu: read when the game starts,
 * written whole at each change, and handed to the session, which runs the
 * enabled ones (none in Hardcore).
 */
internal class GbCheatsUi(
    private val context: Context,
    private val gameSession: AndroidGbSession,
    private val store: GbCheatStore,
    private val scope: CoroutineScope,
) {
    var cheats by mutableStateOf(emptyList<GbCheat>())
        private set
    private var game: LoadedGameInfo? = null
    private var romKey: String? = null

    /** The list of the game just loaded, or none. */
    fun open(loaded: LoadedGameInfo?) {
        game = loaded
        romKey = null
        cheats = emptyList()
        if (loaded == null) return
        scope.launch {
            val (key, list) = withContext(Dispatchers.IO) {
                val key = gameSession.currentRomKey() ?: return@withContext null to emptyList<GbCheat>()
                val list = try {
                    store.read(key)
                } catch (error: Exception) {
                    Log.e(TAG, "Cannot read cheats", error)
                    emptyList()
                }
                gameSession.setCheats(GbCheatList.active(list))
                key to list
            }
            // Another game may have started meanwhile.
            if (game === loaded) {
                romKey = key
                cheats = list
            }
        }
    }

    /** Adds a cheat, on; returns why RavenCore refuses its code, or null. */
    fun add(name: String, code: String): String? {
        val platform = game?.platform ?: return "Aucun jeu chargé."
        if (romKey == null) return "Codes de triche pas encore prêts."
        val line = GbCheatList.oneLine(code)
        if (line.isEmpty()) return "Saisissez un code."
        NativeCore.cheatError(platform, line)?.let { return it }
        changed(cheats + GbCheat(name.trim().ifEmpty { "Code ${cheats.size + 1}" }, line, true))
        return null
    }

    fun toggle(index: Int) {
        val cheat = cheats.getOrNull(index) ?: return
        changed(cheats.toMutableList().also { it[index] = cheat.copy(enabled = !cheat.enabled) })
    }

    fun delete(index: Int) {
        if (index !in cheats.indices) return
        changed(cheats.toMutableList().also { it.removeAt(index) })
    }

    private fun changed(list: List<GbCheat>) {
        val key = romKey ?: return
        cheats = list
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    gameSession.setCheats(GbCheatList.active(list))
                    store.write(key, list)
                }
            } catch (error: Exception) {
                Log.e(TAG, "Cannot save cheats", error)
                Toast.makeText(context, "Codes de triche non enregistrés", Toast.LENGTH_LONG).show()
            }
        }
    }

    private companion object {
        const val TAG = "RavenEmuCheats"
    }
}
