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
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Owns one GB, GBC or GBA emulation core for the current Activity; the
 * file and its header pick which one (see [RomImportPolicy.platformOf]).
 * ROMs are selected on demand; battery-backed game saves survive process restarts.
 * Native state is always protected by lock, while disk writes use saveLock.
 *
 * With the wireless link cable, a frame can wait for the other device's
 * console (see [NativeRemoteLink]). The UI thread therefore never waits for
 * the native lock: buttons are applied before the next frame, the game's
 * fixed traits are kept aside, and a lifecycle save may give up.
 */
class AndroidGbSession(private val resolver: ContentResolver, filesDir: File) : Closeable {
    private val lock = ReentrantLock()
    private val saveLock = ReentrantLock()
    private val batteryStore = GbBatteryStore(File(filesDir, "gb-battery"))
    private val stateStore = GbSaveStateStore(File(filesDir, "gb-states"))
    @Volatile private var closed = false
    private var active: NativeCore? = null
    private var activeRomKey: String? = null
    private val heldButtons = mutableSetOf<GbButton>()
    // Buttons the UI wants, one bit per GbButton, applied before each frame.
    private val buttons = GbButtonLatch()
    // Fixed for a loaded game: read without waiting for a running frame.
    private class Traits(val fps: Double, val audioRate: Int, val sensors: Int)
    @Volatile private var traits: Traits? = null
    // Wireless cable of the running game; created and destroyed between frames.
    @Volatile private var remote: NativeRemoteLink? = null

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
            return saveLock.withLock {
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
                val fixed = Traits(replacement.fps, replacement.audioRate, replacement.sensors())
                lock.withLock {
                    check(!closed) { "La session a été fermée." }
                    val previous = active
                    dropRemoteLocked()
                    heldButtons.clear()
                    buttons.releaseAll()
                    active = replacement
                    activeRomKey = romKey
                    traits = fixed
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
    fun saveBatteryIfDirty(): Boolean = saveLock.withLock {
        saveBatteryIfDirtyLocked()
    }

    /**
     * The same, giving up after [waitMillis] if a frame holds the console:
     * for the UI thread, while a frame may wait for the wireless partner.
     */
    fun saveBatteryIfDirty(waitMillis: Long): Boolean {
        if (!saveLock.tryLock(waitMillis, TimeUnit.MILLISECONDS)) return false
        try {
            return saveBatteryIfDirtyLocked(waitMillis)
        } finally {
            saveLock.unlock()
        }
    }

    private fun saveBatteryIfDirtyLocked(waitMillis: Long? = null): Boolean {
        val pending = withNativeLock(waitMillis) {
            if (closed) return@withNativeLock null
            val core = active ?: return@withNativeLock null
            val key = activeRomKey ?: return@withNativeLock null
            if (!core.batteryDirty()) return@withNativeLock null
            val snapshot = core.batterySnapshotWithGeneration() ?: return@withNativeLock null
            Triple(core, key, snapshot)
        } ?: return false

        val (core, key, snapshot) = pending
        batteryStore.write(key, snapshot.bytes)
        // Not acknowledged when the console stays busy: saved again later.
        withNativeLock(waitMillis) {
            if (!closed && active === core) {
                core.acknowledgeBattery(snapshot.generation)
            }
        }
        Log.d(TAG, "Battery saved for ROM ${key.take(12)}")
        return true
    }

    // The native lock, or null after [waitMillis] when a frame keeps it.
    private inline fun <T> withNativeLock(waitMillis: Long?, block: () -> T): T? {
        if (waitMillis == null) return lock.withLock(block)
        if (!lock.tryLock(waitMillis, TimeUnit.MILLISECONDS)) return null
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    /**
     * Release a matching cached core BEFORE installing externally imported
     * saves. Otherwise its stale in-memory battery RAM could be auto-saved on
     * the next ROM launch or lifecycle event, overwriting the imported .sav.
     * The next user launch recreates the core from the imported save files.
     */
    fun releaseForSaveImport(romKey: String) = saveLock.withLock {
        saveBatteryIfDirtyLocked()
        lock.withLock {
            check(!closed) { "La session a été fermée." }
            if (activeRomKey == romKey) {
                dropRemoteLocked()
                for (button in heldButtons) active?.button(button.nativeCode, false)
                heldButtons.clear()
                buttons.releaseAll()
                active?.close()
                active = null
                activeRomKey = null
                traits = null
            }
        }
    }

    /** No load or autosave may recreate/write stale SRAM during installation. */
    fun importSave(romKey: String, install: () -> Unit) = saveLock.withLock {
        releaseForSaveImport(romKey)
        install()
    }

    /** Read-only RetroAchievements memory; do not clock the emulated bus. */
    fun readAchievementMemory(address: Int, count: Int): ByteArray = lock.withLock {
        if (closed || address < 0 || count <= 0 || count > 0x58000) return@withLock ByteArray(0)
        val native = active ?: return@withLock ByteArray(0)
        val bytes = ByteArray(count)
        val read = native.readAchievementMemory(address, bytes)
        if (read == count) bytes else bytes.copyOf(read)
    }

    /** Power-cycle the running game (Hardcore requires it when switched on). */
    fun resetGame() = lock.withLock {
        check(!closed) { "La session a été fermée." }
        val core = active ?: return@withLock
        for (button in heldButtons) core.button(button.nativeCode, false)
        heldButtons.clear()
        buttons.releaseAll()
        core.reset()
    }

    /** Serialize state operations with both emulation and battery saves. */
    fun saveStateSlot(slot: Int = 0, preview: ByteArray? = null) = saveLock.withLock {
        val (key, state) = lock.withLock {
            check(!closed) { "La session a été fermée." }
            val core = active ?: error("Aucun jeu chargé.")
            val key = activeRomKey ?: error("Aucune ROM active.")
            // Buttons released by the game menu are released in the state too.
            applyButtons(core, buttons.current())
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

    /**
     * Set by RetroAchievements while Hardcore is wanted or running: then no
     * save state is loaded. Checked again next to the native restore, under
     * its lock, so a mode change cannot slip between the check and the load.
     */
    @Volatile internal var stateLoadLocked = false

    fun loadStateSlot(slot: Int = 0) = saveLock.withLock {
        RetroHardcorePolicy.requireStateLoadAllowed(stateLoadLocked)
        val key = lock.withLock {
            check(!closed) { "La session a été fermée." }
            activeRomKey ?: error("Aucun jeu chargé.")
        }
        val state = stateStore.read(key, slot)
            ?: error("Emplacement ${slot + 1} vide.")
        lock.withLock {
            check(!closed && activeRomKey == key) { "La ROM active a changé." }
            RetroHardcorePolicy.requireStateLoadAllowed(stateLoadLocked)
            // The other console would not follow: the exchange in progress would break.
            check(remote == null) { "Câble sans fil relié : chargement d'état impossible." }
            val core = active ?: error("Aucun jeu chargé.")
            for (button in heldButtons) core.button(button.nativeCode, false)
            heldButtons.clear()
            buttons.releaseAll()
            core.loadState(state)
        }
        Log.d(TAG, "Save state slot ${slot + 1} restored for ROM ${key.take(12)}")
    }

    /** Only deletes the selected slot; cartridge SRAM is kept untouched. */
    fun deleteStateSlot(slot: Int) = saveLock.withLock {
        val key = lock.withLock {
            check(!closed) { "La session a été fermée." }
            activeRomKey ?: error("Aucun jeu chargé.")
        }
        stateStore.delete(key, slot)
        Log.d(TAG, "Save state slot ${slot + 1} deleted for ROM ${key.take(12)}")
    }

    internal fun listStateSlots(): List<GbSlotSummary> = saveLock.withLock {
        val key = lock.withLock {
            check(!closed) { "La session a été fermée." }
            activeRomKey ?: error("Aucun jeu chargé.")
        }
        stateStore.slots(key)
    }

    /**
     * Advance exactly one frame, then drain interleaved stereo PCM16 from the
     * same native handle under one lock. No second emulation clock is started.
     * The buttons and the latest [sensors] readings reach the core just before
     * the frame. Returns null if no ROM is active.
     */
    internal fun nextFrameWithAudioInto(
        pixels: IntArray, audio: ShortArray, sensors: CartridgeSensors? = null,
    ): Int? =
        lock.withLock {
            check(!closed) { "La session a été fermée." }
            val core = active ?: return@withLock null
            applyButtons(core, buttons.takeFrame())
            sensors?.applyTo(core)
            core.frameInto(pixels)
            rumbleActive = core.rumbleActive()
            linkWanted = core.linkWanted()
            core.readAudioInto(audio)
        }

    // Under the native lock: the buttons the UI wants, before a frame or a state.
    private fun applyButtons(core: NativeCore, wanted: Int) {
        for (button in GbButton.entries) {
            val pressed = (wanted and (1 shl button.ordinal)) != 0
            if ((button in heldButtons) == pressed) continue
            core.button(button.nativeCode, pressed)
            if (pressed) heldButtons.add(button) else heldButtons.remove(button)
        }
    }

    /** The game used its link port in the last seconds (after the last frame). */
    @Volatile var linkWanted: Boolean = false
        private set

    /**
     * Plugs a wireless cable into the running game, `player` 0 being the GBA
     * parent. Null without a game, or for a console without a remote cable.
     */
    internal fun attachRemoteLink(player: Int, sink: NativeRemoteLink.Sink): NativeRemoteLink? = lock.withLock {
        if (closed) return@withLock null
        val core = active ?: return@withLock null
        dropRemoteLocked()
        runCatching { NativeRemoteLink(core, player, sink) }
            .onFailure { Log.w(TAG, "Wireless link cable refused", it) }
            .getOrNull()
            ?.also { remote = it }
    }

    /** Unplugs [link] (a frame waiting for the partner returns at once), then frees it. */
    internal fun detachRemoteLink(link: NativeRemoteLink) {
        link.setConnected(false)
        lock.withLock {
            if (remote === link) remote = null
            link.close()
        }
    }

    /**
     * Unplugs the wireless cable from any thread, without waiting for the
     * console: a frame waiting for the partner returns at once.
     */
    internal fun unplugRemoteLink() {
        remote?.setConnected(false)
    }

    /** Between frames or during a pause: answers the partner's transfers. */
    internal fun serviceRemoteLink() {
        lock.withLock { remote?.service() }
    }

    // A new or no game: its wireless cable goes with the old one.
    private fun dropRemoteLocked() {
        remote?.close()
        remote = null
        linkWanted = false
    }

    /** The cartridge motor's state after the last frame (see NativeCore). */
    @Volatile var rumbleActive: Boolean = false
        private set

    /** Sensors of the loaded cartridge (NativeCore.SENSOR_* flags), 0 without a game. */
    fun cartridgeSensors(): Int = if (closed) 0 else traits?.sensors ?: 0

    fun refreshRateHz(): Double? = if (closed) null else traits?.fps

    fun audioRateHz(): Int? = if (closed) null else traits?.audioRate

    /**
     * Input is ordered on the UI thread and reaches the core before the next
     * frame, as it did when the UI waited for the running one to end. A press
     * shorter than a frame still lasts one frame (see GbButtonLatch).
     */
    fun setButton(button: GbButton, pressed: Boolean): Boolean {
        if (closed) return false
        buttons.set(1 shl button.ordinal, pressed)
        return true
    }

    /** Prevent stuck buttons after screen exit, app pause or cancelled touches. */
    fun releaseAllButtons() {
        buttons.releaseAll()
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
        unplugRemoteLink()
        saveLock.withLock {
            try {
                saveBatteryIfDirtyLocked()
            } catch (error: Exception) {
                Log.e(TAG, "Failed to persist battery on session close", error)
            } finally {
                lock.withLock {
                    closed = true
                    dropRemoteLocked()
                    heldButtons.clear()
                    buttons.releaseAll()
                    active?.close()
                    active = null
                    activeRomKey = null
                    traits = null
                }
            }
        }
    }

    private companion object {
        const val TAG = "RavenEmuSaves"
    }
}
