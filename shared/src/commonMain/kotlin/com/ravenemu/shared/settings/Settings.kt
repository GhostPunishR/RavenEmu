package com.ravenemu.shared.settings

import kotlin.math.pow
import kotlin.math.roundToInt
import com.ravenemu.shared.library.GamePlatform

/** Hide settings for services the current host has not connected yet. */
data class SettingsCapabilities(
    val onlineCovers: Boolean = true,
    val cartridgeHardware: Boolean = true,
    val deltaSkins: Boolean = true,
    val haptics: Boolean = true,
    val diagnostics: Boolean = true,
    val audioStartupFade: Boolean = true,
    val wirelessLink: Boolean = true,
    /** Settings > Console: Game Boy colors, GBC of a GBA, real GBA BIOS. */
    val console: Boolean = true,
    /** Frame blending and rewind, which the host's player runs. */
    val frameBlending: Boolean = true,
    val rewind: Boolean = true,
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
    /**
     * Frame blending: "off", "mix" (each image averaged with the one before)
     * or "ghosting" (a trail of the images before). Some games flicker an
     * object every other frame to make it transparent, counting on the
     * slow LCD (see [GbFrameBlender]).
     */
    val frameBlending: String = "off",
    /** The picture at a whole multiple of its size: every pixel the same, borders around. */
    val integerScale: Boolean = false,
    /** Each frame is kept so that the game can play backwards (not in Hardcore). */
    val rewind: Boolean = true,
    /** Game Boy games run on a Game Boy Color, which colors them. */
    val gbInColor: Boolean = false,
    /** Their colors: 0 the boot ROM's choice for each game, 1 to 13 a button combination (see [GB_COLORS]). */
    val gbColors: Int = 0,
    /** GB and GBC games on the GBC inside a Game Boy Advance (Zelda Oracle, Shantae extras). */
    val gbcOnGba: Boolean = false,
    /** GBA BIOS: 0 RavenCore's, 1 the player's real BIOS, 2 the real one without its animation. */
    val gbaBios: Int = 0,
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
    /** The unlock chime, played with the game's sound; on by default, as on the PC. */
    val retroPopupSound: Boolean = true,
    val retroPopupPosition: String = "top",
    val retroPopupSeconds: Int = 5,
    val retroProgressSeconds: Int = 2,
    /**
     * Câble link sans fil : quand le jeu se sert de son port link, le
     * téléphone cherche un autre RavenEmu sur le même réseau Wi-Fi et s'y relie.
     */
    val wirelessLink: Boolean = true,
) {
    fun normalized(): Settings = copy(
        audioVolume = audioVolume.coerceIn(0, 100),
        videoPalette = videoPalette.takeIf { it in listOf("original", "dmg", "pocket") } ?: "original",
        frameBlending = frameBlending.takeIf { it in listOf("off", "mix", "ghosting") } ?: "off",
        videoBrightness = videoBrightness.coerceIn(-50, 50),
        videoContrast = videoContrast.coerceIn(50, 150),
        controlsScale = controlsScale.coerceIn(80, 115),
        controlsGap = controlsGap.coerceIn(20, 80),
        controlsStyle = controlsStyle.takeIf { it in listOf("classic", "contrast") } ?: "classic",
        solarLightLevel = solarLightLevel.coerceIn(0, 100),
        gbColors = gbColors.coerceIn(0, GB_COLORS.lastIndex),
        gbaBios = gbaBios.coerceIn(0, 2),
        retroPopupPosition = retroPopupPosition.takeIf { it in listOf("top", "bottom") } ?: "top",
        retroPopupSeconds = retroPopupSeconds.coerceIn(2, 10),
        retroProgressSeconds = retroProgressSeconds.coerceIn(1, 5),
    )
}

/**
 * The colors a Game Boy game can get on a Game Boy Color, in RavenCore's
 * order: the boot ROM's choice, then the combinations held during its logo.
 */
val GB_COLORS = listOf(
    "Celles de la boot ROM, jeu par jeu", "Gris", "Haut : brun", "A + Haut : rouge", "B + Haut : sépia",
    "Gauche : bleu", "A + Gauche : bleu-violet", "B + Gauche : gris", "Bas : pastel", "A + Bas : jaune et rouge",
    "B + Bas : jaune et brun", "Droite : vert et rouge", "A + Droite : vert et bleu", "B + Droite : négatif",
)

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

/**
 * Frame blending, after [GbDisplayFilter], as on RavenEmu PC. "mix" averages
 * each image with the one before: an object shown every other frame comes
 * out half transparent, as the console's slow LCD showed it. "ghosting"
 * keeps 3/8 of the image shown before, a longer trail. One instance per
 * running game: it remembers the image before.
 */
class GbFrameBlender {
    private var mode = "off"
    private var history = IntArray(0)

    fun apply(pixels: IntArray, mode: String) {
        if (mode != this.mode) {
            this.mode = mode
            history = IntArray(0)
        }
        if (mode == "off") return
        if (history.size != pixels.size) {
            history = pixels.copyOf()
            return
        }
        val mix = mode == "mix"
        for (index in pixels.indices) {
            val now = pixels[index]
            val before = history[index]
            var blended = -0x1000000
            for (shift in 0..16 step 8) {
                val a = (now ushr shift) and 255
                val b = (before ushr shift) and 255
                blended = blended or ((if (mix) (a + b + 1) / 2 else (a * 5 + b * 3 + 4) / 8) shl shift)
            }
            pixels[index] = blended
            history[index] = if (mix) now else blended
        }
    }
}
