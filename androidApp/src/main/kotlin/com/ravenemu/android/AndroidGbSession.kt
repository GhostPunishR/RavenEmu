package com.ravenemu.android

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.ravenemu.shared.GamePlatform
import com.ravenemu.shared.GbButton
import com.ravenemu.shared.LoadedGameInfo
import com.ravenemu.shared.RomImportPolicy
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File

/**
 * Owns one GB, GBC or GBA emulation core for the current Activity; the
 * file and its header pick which one (see [RomImportPolicy.platformOf]).
 * ROMs are selected on demand; battery-backed game saves survive process restarts.
 * Native state is always protected by lock, while disk writes use saveLock.
 */
class AndroidGbSession(private val resolver: ContentResolver, filesDir: File) : Closeable {
    private val lock = Any()
    private val saveLock = Any()
    private val batteryStore = GbBatteryStore(File(filesDir, "gb-battery"))
    private val stateStore = GbSaveStateStore(File(filesDir, "gb-states"))
    private var closed = false
    private var active: NativeCore? = null
    private var activeRomKey: String? = null
    private val heldButtons = mutableSetOf<GbButton>()

    /** Must be called off the UI thread. */
    fun load(uri: Uri): LoadedGameInfo {
        val filename = displayName(uri)
        require(RomImportPolicy.acceptsRomFile(filename)) {
            "Sélectionnez une ROM Game Boy (.gb), Game Boy Color (.gbc) ou Game Boy Advance (.gba)."
        }
        return loadBytes(filename, readBounded(uri))
    }

    /** Load either a just-imported ROM or a private persistent library copy. */
    fun loadBytes(filename: String, bytes: ByteArray): LoadedGameInfo {
        require(RomImportPolicy.acceptsRomFile(filename)) {
            "La ROM doit être au format .gb, .gbc ou .gba."
        }
        require(bytes.size in 1..RomImportPolicy.maxRomBytes) {
            "Taille de ROM invalide."
        }
        val romKey = GbBatteryStore.keyForRom(bytes)
        val platform = RomImportPolicy.platformOf(filename, bytes)
        val replacement = NativeCore(NativeCore.systemFor(platform))
        var transferred = false
        try {
            return synchronized(saveLock) {
                // Flush the previous game BEFORE reading this ROM's save: a
                // re-import of the same ROM must not load a stale snapshot.
                saveBatteryIfDirtyLocked()
                val savedBattery = batteryStore.read(romKey)
                replacement.load(bytes, savedBattery)
                val result = LoadedGameInfo(
                    filename = filename,
                    platform = platform,
                    width = replacement.width,
                    height = replacement.height,
                )
                synchronized(lock) {
                    check(!closed) { "La session a été fermée." }
                    val previous = active
                    heldButtons.clear()
                    active = replacement
                    activeRomKey = romKey
                    transferred = true
                    previous?.close()
                }
                result
            }
        } finally {
            if (!transferred) replacement.close()
        }
    }

    /**
     * Commit changed battery RAM using AtomicFile, then acknowledge exactly
     * the generation persisted. No disk IO runs while holding the native lock.
     * Safe to call from a periodic IO worker, ROM replacement, or lifecycle.
     */
    fun saveBatteryIfDirty(): Boolean = synchronized(saveLock) {
        saveBatteryIfDirtyLocked()
    }

    private fun saveBatteryIfDirtyLocked(): Boolean {
        val pending = synchronized(lock) {
            if (closed) return@synchronized null
            val core = active ?: return@synchronized null
            val key = activeRomKey ?: return@synchronized null
            if (!core.batteryDirty()) return@synchronized null
            val snapshot = core.batterySnapshotWithGeneration() ?: return@synchronized null
            Triple(core, key, snapshot)
        } ?: return false

        val (core, key, snapshot) = pending
        batteryStore.write(key, snapshot.bytes)
        synchronized(lock) {
            if (!closed && active === core) {
                core.acknowledgeBattery(snapshot.generation)
            }
        }
        Log.d(TAG, "Battery saved for ROM ${key.take(12)}")
        return true
    }

    /**
     * Release a matching cached core BEFORE installing externally imported
     * saves. Otherwise its stale in-memory battery RAM could be auto-saved on
     * the next ROM launch or lifecycle event, overwriting the imported .sav.
     * The next user launch recreates the core from the imported save files.
     */
    fun releaseForSaveImport(romKey: String) = synchronized(saveLock) {
        saveBatteryIfDirtyLocked()
        synchronized(lock) {
            check(!closed) { "La session a été fermée." }
            if (activeRomKey == romKey) {
                for (button in heldButtons) active?.button(button.nativeCode, false)
                heldButtons.clear()
                active?.close()
                active = null
                activeRomKey = null
            }
        }
    }

    /** Read-only RetroAchievements memory; do not clock the emulated bus. */
    fun readAchievementMemory(address: Int, count: Int): ByteArray = synchronized(lock) {
        if (closed || address < 0 || count <= 0 || count > 0x58000) return@synchronized ByteArray(0)
        val native = active ?: return@synchronized ByteArray(0)
        val bytes = ByteArray(count)
        val read = native.readAchievementMemory(address, bytes)
        if (read == count) bytes else bytes.copyOf(read)
    }

    /** Power-cycle the running game (Hardcore requires it when switched on). */
    fun resetGame() = synchronized(lock) {
        check(!closed) { "La session a été fermée." }
        val core = active ?: return@synchronized
        for (button in heldButtons) core.button(button.nativeCode, false)
        heldButtons.clear()
        core.reset()
    }

    /** Serialize state operations with both emulation and battery saves. */
    fun saveStateSlot(slot: Int = 0, preview: ByteArray? = null) = synchronized(saveLock) {
        val (key, state) = synchronized(lock) {
            check(!closed) { "La session a été fermée." }
            val core = active ?: error("Aucun jeu chargé.")
            val key = activeRomKey ?: error("Aucune ROM active.")
            key to core.saveState()
        }
        // Write the state before the optional thumbnail, so a preview cannot
        // be mistaken for a complete save on crash or low storage.
        stateStore.write(key, state, slot)
        try {
            if (preview != null) stateStore.writePreview(key, slot, preview)
            else stateStore.deletePreview(key, slot)
        } catch (error: Exception) {
            Log.w(TAG, "Preview write failed; save state is still safe", error)
            stateStore.deletePreview(key, slot)
        }
        Log.d(TAG, "Save state slot ${slot + 1} stored for ROM ${key.take(12)}")
    }

    fun loadStateSlot(slot: Int = 0) = synchronized(saveLock) {
        val key = synchronized(lock) {
            check(!closed) { "La session a été fermée." }
            activeRomKey ?: error("Aucun jeu chargé.")
        }
        val state = stateStore.read(key, slot)
            ?: error("Emplacement ${slot + 1} vide.")
        synchronized(lock) {
            check(!closed && activeRomKey == key) { "La ROM active a changé." }
            val core = active ?: error("Aucun jeu chargé.")
            for (button in heldButtons) core.button(button.nativeCode, false)
            heldButtons.clear()
            core.loadState(state)
        }
        Log.d(TAG, "Save state slot ${slot + 1} restored for ROM ${key.take(12)}")
    }

    /** Only deletes the selected slot; cartridge SRAM is kept untouched. */
    fun deleteStateSlot(slot: Int) = synchronized(saveLock) {
        val key = synchronized(lock) {
            check(!closed) { "La session a été fermée." }
            activeRomKey ?: error("Aucun jeu chargé.")
        }
        stateStore.delete(key, slot)
        Log.d(TAG, "Save state slot ${slot + 1} deleted for ROM ${key.take(12)}")
    }

    internal fun listStateSlots(): List<GbSlotSummary> = synchronized(saveLock) {
        val key = synchronized(lock) {
            check(!closed) { "La session a été fermée." }
            activeRomKey ?: error("Aucun jeu chargé.")
        }
        stateStore.slots(key)
    }

    /**
     * Advance exactly one frame, then drain interleaved stereo PCM16 from the
     * same native handle under one lock. No second emulation clock is started.
     * Returns null if no ROM is active.
     */
    fun nextFrameWithAudioInto(pixels: IntArray, audio: ShortArray): Int? =
        synchronized(lock) {
            check(!closed) { "La session a été fermée." }
            val core = active ?: return@synchronized null
            core.frameInto(pixels)
            rumbleActive = core.rumbleActive()
            core.readAudioInto(audio)
        }

    /** The cartridge motor's state after the last frame (see NativeCore). */
    @Volatile var rumbleActive: Boolean = false
        private set

    fun refreshRateHz(): Double? = synchronized(lock) {
        if (closed) null else active?.fps
    }

    fun audioRateHz(): Int? = synchronized(lock) {
        if (closed) null else active?.audioRate
    }

    /**
     * Input is ordered on the UI thread and serialized with native frames.
     * The bridge requires all operations on one native handle to be serialized.
     */
    fun setButton(button: GbButton, pressed: Boolean): Boolean = synchronized(lock) {
        if (closed) return@synchronized false
        val core = active ?: return@synchronized false
        if ((button in heldButtons) == pressed) return@synchronized true
        core.button(button.nativeCode, pressed)
        if (pressed) heldButtons.add(button) else heldButtons.remove(button)
        true
    }

    /** Prevent stuck buttons after screen exit, app pause or cancelled touches. */
    fun releaseAllButtons() {
        synchronized(lock) {
            if (!closed) {
                val core = active
                for (button in heldButtons) core?.button(button.nativeCode, false)
            }
            heldButtons.clear()
        }
    }

    private fun displayName(uri: Uri): String {
        val name = resolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        }
        return name?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Impossible de lire le nom du fichier.")
    }

    private fun readBounded(uri: Uri): ByteArray {
        val stream = resolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Impossible d'ouvrir cette ROM.")
        return stream.use { input ->
            val output = ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val read = input.read(chunk)
                if (read == -1) break
                if (read == 0) continue
                require(read <= RomImportPolicy.maxRomBytes - total) {
                    "Cette ROM dépasse la limite de 32 Mio."
                }
                output.write(chunk, 0, read)
                total += read
            }
            require(total > 0) { "Le fichier est vide." }
            output.toByteArray()
        }
    }

    override fun close() {
        synchronized(saveLock) {
            try {
                saveBatteryIfDirtyLocked()
            } catch (error: Exception) {
                Log.e(TAG, "Failed to persist battery on session close", error)
            } finally {
                synchronized(lock) {
                    closed = true
                    heldButtons.clear()
                    active?.close()
                    active = null
                    activeRomKey = null
                }
            }
        }
    }

    private companion object {
        const val TAG = "RavenEmuSaves"
    }
}
