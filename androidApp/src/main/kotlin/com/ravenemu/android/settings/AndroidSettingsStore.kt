package com.ravenemu.android.settings

import android.content.Context
import com.ravenemu.shared.settings.Settings

/** Private RavenEmu-only preferences: never reads or changes RavenEmu 0.8.1 settings. */
internal class AndroidSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("ravenemu_preferences", Context.MODE_PRIVATE)

    fun read(): Settings = Settings(
        audioEnabled = prefs.getBoolean("audio_enabled", true),
        audioVolume = prefs.getInt("audio_volume", 100),
        videoPalette = prefs.getString("video_palette", "original") ?: "original",
        videoBrightness = prefs.getInt("video_brightness", 0),
        videoContrast = prefs.getInt("video_contrast", 100),
        showDiagnostics = prefs.getBoolean("show_diagnostics", true),
        haptics = prefs.getBoolean("haptics", false),
        controlsScale = prefs.getInt("controls_scale", 100),
        controlsGap = prefs.getInt("controls_gap", 46),
        controlsStyle = prefs.getString("controls_style", "classic") ?: "classic",
        onlineCovers = prefs.getBoolean("online_covers", false),
        lcdCorrectionGbc = prefs.getBoolean("lcd_correction_gbc", false),
        lcdCorrectionGba = prefs.getBoolean("lcd_correction_gba", false),
        frameBlending = prefs.getString("frame_blending", "off") ?: "off",
        integerScale = prefs.getBoolean("integer_scale", false),
        rewind = prefs.getBoolean("rewind", true),
        cartridgeRumble = prefs.getBoolean("cartridge_rumble", true),
        cartridgeSensors = prefs.getBoolean("cartridge_sensors", true),
        solarLightAuto = prefs.getBoolean("solar_light_auto", true),
        solarLightLevel = prefs.getInt("solar_light_level", 50),
        retroHardcore = prefs.getBoolean("retro_hardcore", false),
        retroUnlockPopups = prefs.getBoolean("retro_unlock_popups", true),
        retroProgressPopups = prefs.getBoolean("retro_progress_popups", true),
        // A new key: the former notification beep was off by default, the chime is on.
        retroPopupSound = prefs.getBoolean("retro_unlock_chime", true),
        retroPopupPosition = prefs.getString("retro_popup_position", "top") ?: "top",
        retroPopupSeconds = prefs.getInt("retro_popup_seconds", 5),
        retroProgressSeconds = prefs.getInt("retro_progress_seconds", 2),
        wirelessLink = prefs.getBoolean("wireless_link", true),
    ).normalized()

    fun write(settings: Settings) {
        val safe = settings.normalized()
        prefs.edit()
            .putBoolean("audio_enabled", safe.audioEnabled)
            .putInt("audio_volume", safe.audioVolume)
            .putString("video_palette", safe.videoPalette)
            .putInt("video_brightness", safe.videoBrightness)
            .putInt("video_contrast", safe.videoContrast)
            .putBoolean("show_diagnostics", safe.showDiagnostics)
            .putBoolean("haptics", safe.haptics)
            .putInt("controls_scale", safe.controlsScale)
            .putInt("controls_gap", safe.controlsGap)
            .putString("controls_style", safe.controlsStyle)
            .putBoolean("online_covers", safe.onlineCovers)
            .putBoolean("lcd_correction_gbc", safe.lcdCorrectionGbc)
            .putBoolean("lcd_correction_gba", safe.lcdCorrectionGba)
            .putString("frame_blending", safe.frameBlending)
            .putBoolean("integer_scale", safe.integerScale)
            .putBoolean("rewind", safe.rewind)
            .putBoolean("cartridge_rumble", safe.cartridgeRumble)
            .putBoolean("cartridge_sensors", safe.cartridgeSensors)
            .putBoolean("solar_light_auto", safe.solarLightAuto)
            .putInt("solar_light_level", safe.solarLightLevel)
            .putBoolean("retro_hardcore", safe.retroHardcore)
            .putBoolean("retro_unlock_popups", safe.retroUnlockPopups)
            .putBoolean("retro_progress_popups", safe.retroProgressPopups)
            .putBoolean("retro_unlock_chime", safe.retroPopupSound)
            .putString("retro_popup_position", safe.retroPopupPosition)
            .putInt("retro_popup_seconds", safe.retroPopupSeconds)
            .putInt("retro_progress_seconds", safe.retroProgressSeconds)
            .putBoolean("wireless_link", safe.wirelessLink)
            .apply()
    }
}
