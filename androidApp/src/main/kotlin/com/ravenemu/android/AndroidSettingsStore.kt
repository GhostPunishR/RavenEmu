package com.ravenemu.android

import android.content.Context
import com.ravenemu.shared.Settings

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
        cartridgeRumble = prefs.getBoolean("cartridge_rumble", true),
        cartridgeSensors = prefs.getBoolean("cartridge_sensors", true),
        solarLightAuto = prefs.getBoolean("solar_light_auto", true),
        solarLightLevel = prefs.getInt("solar_light_level", 50),
        retroHardcore = prefs.getBoolean("retro_hardcore", false),
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
            .putBoolean("cartridge_rumble", safe.cartridgeRumble)
            .putBoolean("cartridge_sensors", safe.cartridgeSensors)
            .putBoolean("solar_light_auto", safe.solarLightAuto)
            .putInt("solar_light_level", safe.solarLightLevel)
            .putBoolean("retro_hardcore", safe.retroHardcore)
            .apply()
    }
}
