package com.ravenemu.android

import android.util.Log
import com.ravenemu.shared.GbButton
import com.ravenemu.shared.LinkCable
import com.ravenemu.shared.LoadedGameInfo
import com.ravenemu.shared.RomImportPolicy
import java.io.Closeable
import java.io.File

/**
 * Two games side by side, joined by a virtual link cable: GB/GBC with
 * GB/GBC, GBA with GBA (see [LinkCable]). Each game keeps its own battery
 * save, in the same store and under the same key as when it runs alone. The
 * player drives one game at a time, the [focus]; only that one is heard.
 * Native state is protected by lock, disk writes by saveLock, like
 * [AndroidGbSession].
 */
class AndroidLinkSession(filesDir: File) : Closeable {
    private val lock = Any()
    private val saveLock = Any()
    private val batteryStore = GbBatteryStore(File(filesDir, "gb-battery"))
    private var closed = false
    private var players: List<Player> = emptyList()
    private var link: NativeLink? = null

    private class Player(val core: NativeCore, val romKey: String) {
        val heldButtons = mutableSetOf<GbButton>()
    }

    /**
     * Number of the loaded pair, bumped by each [load]: an [unload] requested
     * for an earlier pair leaves the one that replaced it alone.
     */
    @Volatile var pairId: Long = 0
        private set

    /** Index (0 or 1) of the game that receives the controls and is heard. */
    @Volatile var focus: Int = 0
        private set

    /**
     * Loads both games and links them. [games] holds two (filename, ROM)
     * pairs of compatible systems and different ROMs. Must be called off the
     * UI thread; replaces any previous pair, whose saves are written first.
     */
    fun load(games: List<Pair<String, ByteArray>>): List<LoadedGameInfo> {
        require(games.size == 2) { "Le câble link relie deux jeux." }
        val keys = games.map { (_, bytes) -> GbBatteryStore.keyForRom(bytes) }
        require(keys[0] != keys[1]) {
            "Choisissez deux jeux différents : une cartouche n'a qu'une sauvegarde."
        }
        val platforms = games.map { (name, bytes) ->
            require(RomImportPolicy.acceptsRomFile(name)) { "La ROM doit être au format .gb, .gbc ou .gba." }
            require(bytes.size in 1..RomImportPolicy.maxRomBytes) { "Taille de ROM invalide." }
            RomImportPolicy.platformOf(name, bytes)
        }
        require(LinkCable.compatible(platforms[0], platforms[1])) {
            "Ces deux jeux ne se relient pas : Game Boy et Game Boy Color ensemble, ou deux Game Boy Advance."
        }
        return synchronized(saveLock) {
            saveBatteriesIfDirtyLocked()
            val loaded = mutableListOf<Player>()
            var cable: NativeLink? = null
            var transferred = false
            try {
                val infos = games.mapIndexed { index, (name, bytes) ->
                    val core = NativeCore(NativeCore.systemFor(platforms[index]))
                    loaded += Player(core, keys[index])
                    core.load(bytes, batteryStore.read(keys[index]))
                    LoadedGameInfo(name, platforms[index], core.width, core.height)
                }
                cable = NativeLink(loaded[0].core, loaded[1].core)
                synchronized(lock) {
                    check(!closed) { "La session a été fermée." }
                    releaseLocked()
                    players = loaded
                    link = cable
                    focus = 0
                    pairId++
                    transferred = true
                }
                infos
            } finally {
                if (!transferred) {
                    cable?.close()
                    for (player in loaded) player.core.close()
                }
            }
        }
    }

    /** Gives the controls (and the sound) to game [index], releasing the other's buttons. */
    fun setFocus(index: Int) = synchronized(lock) {
        require(index in 0..1)
        if (closed || index == focus) return@synchronized
        players.getOrNull(focus)?.let(::releaseButtonsLocked)
        focus = index
    }

    /** Input for the focused game, serialized with native frames. */
    fun setButton(button: GbButton, pressed: Boolean): Boolean = synchronized(lock) {
        if (closed) return@synchronized false
        val player = players.getOrNull(focus) ?: return@synchronized false
        if ((button in player.heldButtons) == pressed) return@synchronized true
        player.core.button(button.nativeCode, pressed)
        if (pressed) player.heldButtons.add(button) else player.heldButtons.remove(button)
        true
    }

    fun releaseAllButtons() = synchronized(lock) {
        if (!closed) players.forEach(::releaseButtonsLocked)
    }

    /**
     * Advances both games by one frame, linked, then drains their audio: the
     * focused game's into [audio], the other's into [discarded]. Returns the
     * focused game's sample count, or null when no pair is loaded.
     */
    internal fun nextFramesInto(
        first: IntArray, second: IntArray, audio: ShortArray, discarded: ShortArray,
    ): Int? = synchronized(lock) {
        check(!closed) { "La session a été fermée." }
        val cable = link ?: return@synchronized null
        cable.frameInto(first, second)
        var heard = 0
        players.forEachIndexed { index, player ->
            if (index == focus) heard = player.core.readAudioInto(audio)
            else player.core.readAudioInto(discarded)
        }
        heard
    }

    fun refreshRateHz(): Double? = synchronized(lock) {
        if (closed) null else players.firstOrNull()?.core?.fps
    }

    fun audioRateHz(): Int? = synchronized(lock) {
        if (closed) null else players.getOrNull(focus)?.core?.audioRate
    }

    /** Writes both games' changed saves; the same protocol as [AndroidGbSession]. */
    fun saveBatteriesIfDirty(): Boolean = synchronized(saveLock) { saveBatteriesIfDirtyLocked() }

    private fun saveBatteriesIfDirtyLocked(): Boolean {
        val pending = synchronized(lock) {
            if (closed) return@synchronized emptyList<Triple<Player, String, NativeCore.BatterySnapshot>>()
            players.mapNotNull { player ->
                if (!player.core.batteryDirty()) return@mapNotNull null
                player.core.batterySnapshotWithGeneration()?.let { Triple(player, player.romKey, it) }
            }
        }
        for ((player, key, snapshot) in pending) {
            batteryStore.write(key, snapshot.bytes)
            synchronized(lock) {
                if (!closed && player in players) player.core.acknowledgeBattery(snapshot.generation)
            }
            Log.d(TAG, "Battery saved for linked ROM ${key.take(12)}")
        }
        return pending.isNotEmpty()
    }

    /**
     * Writes both saves and frees the pair, so that either game can then run
     * alone without a second, stale copy of its save in memory. With [pair],
     * only that pair is freed: a later [load] may already have replaced it.
     * The cores go only once their saves are written: if a write fails, the
     * pair stays in memory and the next unload, pause or load tries again.
     */
    fun unload(pair: Long? = null) = synchronized(saveLock) {
        if (pair != null && pair != pairId) return@synchronized
        saveBatteriesIfDirtyLocked()
        synchronized(lock) { releaseLocked() }
    }

    private fun releaseButtonsLocked(player: Player) {
        for (button in player.heldButtons) player.core.button(button.nativeCode, false)
        player.heldButtons.clear()
    }

    // The cable goes first: it refers to both cores.
    private fun releaseLocked() {
        link?.close()
        link = null
        for (player in players) player.core.close()
        players = emptyList()
        focus = 0
    }

    override fun close() {
        synchronized(saveLock) {
            try {
                saveBatteriesIfDirtyLocked()
            } catch (error: Exception) {
                Log.e(TAG, "Failed to persist linked batteries on close", error)
            } finally {
                synchronized(lock) {
                    releaseLocked()
                    closed = true
                }
            }
        }
    }

    private companion object {
        const val TAG = "RavenEmuLink"
    }
}
