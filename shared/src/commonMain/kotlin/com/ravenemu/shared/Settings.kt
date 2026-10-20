package com.ravenemu.shared

import kotlin.math.pow
import kotlin.math.roundToInt

/** Hide settings for services the current host has not connected yet. */
data class SettingsCapabilities(
    val onlineCovers: Boolean = true,
    val cartridgeHardware: Boolean = true,
    val deltaSkins: Boolean = true,
    val haptics: Boolean = true,
    val diagnostics: Boolean = true,
    val audioStartupFade: Boolean = true,
)

/**
 * RavenEmu preferences: portable value object. Android stores this in private
 * SharedPreferences; other hosts can supply their own persistence adapter.
 * Defaults deliberately preserve the already validated RavenEmu picture and sound.
 */
data class Settings(
    val audioEnabled: Boolean = true,
    val audioVolume: Int = 100,
    val videoPalette: String = "original",
    val videoBrightness: Int = 0,
    val videoContrast: Int = 100,
    val showDiagnostics: Boolean = true,
    val haptics: Boolean = false,
    val controlsScale: Int = 100,
    val controlsGap: Int = 46,
    val controlsStyle: String = "classic",
    /**
     * Recherche des jaquettes sur `thumbnails.libretro.com`. Désactivée par
     * défaut : chaque image demandée indique à ce serveur tiers un jeu de la
     * bibliothèque, avec l'adresse IP de l'appareil. L'utilisateur doit
     * pouvoir l'accepter en connaissance de cause, pas la découvrir.
     */
    val onlineCovers: Boolean = false,
    /**
     * LCD colour correction, per console: tones down the very vivid raw
     * colours to the flatter look of a real reflective screen. Off by
     * default (the RavenEmu 0.8.1 default); a Game Boy game is never affected.
     */
    val lcdCorrectionGbc: Boolean = false,
    val lcdCorrectionGba: Boolean = false,
    /** Rumble cartridges drive the phone's vibrator. */
    val cartridgeRumble: Boolean = true,
    /**
     * The phone's gyroscope, gravity and light sensors stand in for the
     * cartridge's (WarioWare: Twisted!, tilt cartridges, Boktai).
     */
    val cartridgeSensors: Boolean = true,
    /** Boktai's sun follows the phone's light sensor, when it has one. */
    val solarLightAuto: Boolean = true,
    /** Boktai's sun otherwise, in percent of full sunlight. */
    val solarLightLevel: Int = 50,
    /**
     * RetroAchievements Hardcore, as chosen by the player. It only takes
     * effect once RetroAchievements has approved the app (Android:
     * HardcoreAvailability.APPROVED); until then the switch is not shown.
     */
    val retroHardcore: Boolean = false,
    val retroUnlockPopups: Boolean = true,
    val retroProgressPopups: Boolean = true,
    val retroPopupSound: Boolean = false,
    val retroPopupPosition: String = "top",
    val retroPopupSeconds: Int = 5,
    val retroProgressSeconds: Int = 2,
) {
    fun normalized(): Settings = copy(
        audioVolume = audioVolume.coerceIn(0, 100),
        videoPalette = videoPalette.takeIf { it in listOf("original", "dmg", "pocket") } ?: "original",
        videoBrightness = videoBrightness.coerceIn(-50, 50),
        videoContrast = videoContrast.coerceIn(50, 150),
        controlsScale = controlsScale.coerceIn(80, 115),
        controlsGap = controlsGap.coerceIn(20, 80),
        controlsStyle = controlsStyle.takeIf { it in listOf("classic", "contrast") } ?: "classic",
        solarLightLevel = solarLightLevel.coerceIn(0, 100),
        retroPopupPosition = retroPopupPosition.takeIf { it in listOf("top", "bottom") } ?: "top",
        retroPopupSeconds = retroPopupSeconds.coerceIn(2, 10),
        retroProgressSeconds = retroProgressSeconds.coerceIn(1, 5),
    )
}

/** Apply an optional display treatment after the native GB/GBC/GBA frame. */
object GbDisplayFilter {
    /**
     * The DMG and Pocket profiles turn the image into shades of one color:
     * on a Game Boy Color game they would erase its colors, so they only
     * apply to monochrome games. The LCD correction only applies to the
     * colour consoles that have it enabled. Brightness and contrast apply to
     * all, last.
     */
    fun apply(pixels: IntArray, settings: Settings, platform: GamePlatform = GamePlatform.GB) {
        val profile = if (platform == GamePlatform.GB) settings.videoPalette else "original"
        val brightness = settings.videoBrightness
        val contrast = settings.videoContrast
        val lcd = when (platform) {
            GamePlatform.GB -> false
            GamePlatform.GBC -> settings.lcdCorrectionGbc
            GamePlatform.GBA -> settings.lcdCorrectionGba
        }
        if (profile == "original" && !lcd && brightness == 0 && contrast == 100) return
        for (index in pixels.indices) {
            val color = pixels[index]
            val alpha = color and -0x1000000
            val sourceR = (color ushr 16) and 255
            val sourceG = (color ushr 8) and 255
            val sourceB = color and 255
            var r = sourceR
            var g = sourceG
            var b = sourceB
            if (profile != "original") {
                val luminance = (sourceR * 30 + sourceG * 59 + sourceB * 11) / 100
                if (profile == "dmg") {
                    // Retro LCD green with a dark-green shadow color.
                    r = 15 + luminance * 140 / 255
                    g = 56 + luminance * 132 / 255
                    b = 15 + luminance * 85 / 255
                } else {
                    r = luminance
                    g = luminance
                    b = luminance
                }
            }
            if (lcd) {
                // Same simulation as RavenEmu 0.8.1 (calibrated by eye, not
                // official panel data): ~28 % desaturation towards the Rec.
                // 709 luminance, then a reflective-panel gamma of 1.15.
                val luma = (54 * r + 183 * g + 19 * b) ushr 8
                r = LCD_GAMMA[r + (((luma - r) * LCD_DESATURATION) shr 8)]
                g = LCD_GAMMA[g + (((luma - g) * LCD_DESATURATION) shr 8)]
                b = LCD_GAMMA[b + (((luma - b) * LCD_DESATURATION) shr 8)]
            }
            fun adjust(channel: Int): Int =
                (((channel - 128) * contrast) / 100 + 128 + brightness * 2)
                    .coerceIn(0, 255)
            pixels[index] = alpha or
                (adjust(r) shl 16) or (adjust(g) shl 8) or adjust(b)
        }
    }

    /** Desaturation share towards the luminance, out of 256 (about 28 %). */
    private const val LCD_DESATURATION = 72

    /** Reflective LCD gamma 1.15: darkens the mid tones a little. */
    private val LCD_GAMMA = IntArray(256) { v ->
        (255.0 * (v / 255.0).pow(1.15)).roundToInt().coerceIn(0, 255)
    }
}
