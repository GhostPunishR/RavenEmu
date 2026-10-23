@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ravenemu.shared

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.window.ComposeUIViewController
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import platform.Foundation.NSData
import platform.Foundation.NSUserDefaults
import platform.UIKit.UIViewController
import platform.posix.memcpy
import com.ravenemu.shared.library.GameEntry
import com.ravenemu.shared.library.GamePlatform
import com.ravenemu.shared.library.LoadedGameInfo
import com.ravenemu.shared.saves.BatteryFileFormat
import com.ravenemu.shared.saves.GbStateSlot
import com.ravenemu.shared.settings.GbDisplayFilter
import com.ravenemu.shared.settings.Settings
import com.ravenemu.shared.settings.SettingsCapabilities

/** Main-thread UI callbacks. Swift serializes all native work on its own queue. */
interface IosHostActions {
    fun importRom()
    fun launchGame(id: String)
    fun startPlayback()
    fun stopPlayback()
    fun menuVisibilityChanged(open: Boolean)
    fun buttonChanged(code: Int, pressed: Boolean)
    fun saveState(slot: Int)
    fun loadState(slot: Int)
    fun deleteState(slot: Int)
    fun exportBattery(id: String)
    fun importBattery(id: String)
    fun configureAudio(enabled: Boolean, volume: Int)
    fun openLink(url: String)
}

class IosLibraryGame(val id: String, val filename: String, val system: Int)

/** Owns Compose state, never a native handle. Frames are immutable BGRA bytes. */
class IosHost {
    var actions: IosHostActions? = null
    private var games by mutableStateOf(emptyList<GameEntry>())
    private var loaded by mutableStateOf<LoadedGameInfo?>(null)
    private var frame by mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
    private var busy by mutableStateOf(false)
    private var transferBusy by mutableStateOf(false)
    private var stateBusy by mutableStateOf(false)
    private var slots by mutableStateOf(emptyList<GbStateSlot>())
    private var notice by mutableStateOf<String?>(null)
    private var settings by mutableStateOf(readSettings())
    private var launchReady: (() -> Unit)? = null

    fun viewController(): UIViewController = ComposeUIViewController {
        RavenEmuApp(
            libraryGames = games, loadedGame = loaded, videoFrame = frame,
            importBusy = busy, saveTransferBusy = transferBusy,
            stateOperationBusy = stateBusy, stateSlots = slots,
            onImportRom = { actions?.importRom() },
            onLaunchLibraryGame = { id, ready ->
                if (!busy && !transferBusy) { busy = true; launchReady = ready; actions?.launchGame(id) }
            },
            onStartVideo = { actions?.startPlayback() }, onStopVideo = { actions?.stopPlayback() },
            onButtonChange = { button, pressed -> actions?.buttonChanged(button.nativeCode, pressed) },
            onGameMenuVisibilityChange = { actions?.menuVisibilityChanged(it) },
            onSaveState = { stateBusy = true; actions?.saveState(it) },
            onLoadState = { stateBusy = true; actions?.loadState(it) },
            onDeleteState = { stateBusy = true; actions?.deleteState(it) },
            // The iOS host writes .sav files only.
            onExportBatterySave = { id, _ -> transferBusy = true; actions?.exportBattery(id) },
            batteryFormats = listOf(BatteryFileFormat.SAV),
            onImportGameSave = { transferBusy = true; actions?.importBattery(it) },
            settings = settings,
            settingsCapabilities = SettingsCapabilities(onlineCovers = false, cartridgeHardware = false, deltaSkins = false, haptics = false, diagnostics = false, audioStartupFade = false, wirelessLink = false),
            onSettingsChange = { updated ->
                settings = updated.normalized(); writeSettings(settings)
                actions?.configureAudio(settings.audioEnabled, settings.audioVolume)
            }, onOpenLink = { actions?.openLink(it) },
        )
        notice?.let { message ->
            AlertDialog(onDismissRequest = { notice = null }, title = { Text("RavenEmu") },
                text = { Text(message) }, confirmButton = { TextButton(onClick = { notice = null }) { Text("Fermer") } })
        }
    }

    fun replaceLibrary(entries: List<IosLibraryGame>) {
        games = entries.map { GameEntry(it.id, it.filename.substringBeforeLast('.', it.filename), platform(it.system)) }
        busy = false
    }
    fun gameLoaded(filename: String, system: Int, width: Int, height: Int) {
        loaded = LoadedGameInfo(filename, platform(system), width, height)
        frame = null; busy = false
        val ready = launchReady; launchReady = null; ready?.invoke()
    }
    fun showError(message: String) {
        busy = false; transferBusy = false; stateBusy = false; launchReady = null; notice = message
    }
    fun finishTransfer() { transferBusy = false }
    fun beginImport() { busy = true }
    fun cancelImport() { busy = false }
    fun stateSlots(dates: List<String>) {
        slots = (0..3).map { GbStateSlot(it, dates.getOrNull(it)?.takeIf(String::isNotBlank)) }
        stateBusy = false
    }
    fun audioEnabled(): Boolean = settings.audioEnabled
    fun audioVolume(): Int = settings.audioVolume

    fun presentFrame(bytes: NSData, width: Int, height: Int) {
        if (!((width == 160 && height == 144) || (width == 240 && height == 160))) return
        val size = width * height * 4
        if (bytes.length.toInt() != size) return
        val raw = ByteArray(size)
        raw.usePinned { memcpy(it.addressOf(0), bytes.bytes, size.toULong()) }
        if (settings.videoPalette != "original" || settings.videoBrightness != 0 || settings.videoContrast != 100 ||
            settings.lcdCorrectionGbc || settings.lcdCorrectionGba) {
            val pixels = IntArray(width * height) { i ->
                val at = i * 4
                (raw[at].toInt() and 255) or ((raw[at + 1].toInt() and 255) shl 8) or
                    ((raw[at + 2].toInt() and 255) shl 16) or (255 shl 24)
            }
            GbDisplayFilter.apply(pixels, settings, loaded?.platform ?: GamePlatform.GB)
            pixels.forEachIndexed { i, pixel ->
                for (channel in 0..3) raw[i * 4 + channel] = (pixel ushr (channel * 8)).toByte()
            }
        }
        frame = Image.makeRaster(ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE), raw, width * 4)
            .toComposeImageBitmap()
    }
    private fun platform(system: Int): GamePlatform = when (system) { 1 -> GamePlatform.GBC; 2 -> GamePlatform.GBA; else -> GamePlatform.GB }
}

private fun readSettings(): Settings {
    val prefs = NSUserDefaults.standardUserDefaults
    fun bool(key: String, fallback: Boolean) = if (prefs.objectForKey(key) == null) fallback else prefs.boolForKey(key)
    fun int(key: String, fallback: Int) = if (prefs.objectForKey(key) == null) fallback else prefs.integerForKey(key).toInt()
    return Settings(audioEnabled = bool("raven.audio", true), audioVolume = int("raven.volume", 100),
        videoPalette = prefs.stringForKey("raven.palette") ?: "original",
        videoBrightness = int("raven.brightness", 0), videoContrast = int("raven.contrast", 100),
        showDiagnostics = false, controlsScale = int("raven.scale", 100), controlsGap = int("raven.gap", 46),
        controlsStyle = prefs.stringForKey("raven.controls") ?: "classic",
        lcdCorrectionGbc = bool("raven.lcd.gbc", false), lcdCorrectionGba = bool("raven.lcd.gba", false)).normalized()
}
private fun writeSettings(value: Settings) {
    val prefs = NSUserDefaults.standardUserDefaults
    prefs.setBool(value.audioEnabled, "raven.audio"); prefs.setInteger(value.audioVolume.toLong(), "raven.volume")
    prefs.setObject(value.videoPalette, "raven.palette"); prefs.setInteger(value.videoBrightness.toLong(), "raven.brightness")
    prefs.setInteger(value.videoContrast.toLong(), "raven.contrast"); prefs.setInteger(value.controlsScale.toLong(), "raven.scale")
    prefs.setInteger(value.controlsGap.toLong(), "raven.gap"); prefs.setObject(value.controlsStyle, "raven.controls")
    prefs.setBool(value.lcdCorrectionGbc, "raven.lcd.gbc"); prefs.setBool(value.lcdCorrectionGba, "raven.lcd.gba")
}

/** Kept for existing embedders; the Swift app installs IosHostActions. */
fun MainViewController(): UIViewController = IosHost().viewController()
