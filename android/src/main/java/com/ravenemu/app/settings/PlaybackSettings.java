package com.ravenemu.app.settings;


import android.content.Context;
import java.util.Map;

/** Immutable preferences of the independent native preview. No beta data is read. */
public final class PlaybackSettings {
    public static final String PREFERENCES = "native-playback-settings";

    /** Game Boy colors, as the beta names its profiles: RavenCore's green
     * (the beta's "Original"), the Game Boy Pocket's grays, or neutral grays. */
    public static final String PALETTE_ORIGINAL = "original";
    public static final String PALETTE_POCKET = "pocket";
    public static final String PALETTE_GRAY = "gray";
    public static final String BLENDING_OFF = "off";
    public static final String BLENDING_MIX = "mix";
    public static final String BLENDING_GHOSTING = "ghosting";
    /** Screen effects of the native GLES2 driver: none, sharp pixels at any
     * size, or a handheld LCD's grid. */
    public static final String EFFECT_NONE = "none";
    public static final String EFFECT_SHARP = "sharp";
    public static final String EFFECT_LCD = "lcd";

    public static final PlaybackSettings DEFAULTS = new PlaybackSettings(true, 100, false, 0, 100);

    public final boolean audioEnabled;
    public final int audioVolume;
    public final boolean integerScale;
    public final int brightness;
    public final int contrast;
    /** Read by the RavenCore cores, through their Libretro options (CoreOptions). */
    public final String gbPalette;
    public final boolean lcdCorrectionGbc;
    public final boolean lcdCorrectionGba;
    public final String frameBlending;
    public final String screenEffect;

    /** Sound and picture, with the cores' own image options at their defaults. */
    public PlaybackSettings(boolean audioEnabled, int audioVolume, boolean integerScale,
                     int brightness, int contrast) {
        this(audioEnabled, audioVolume, integerScale, brightness, contrast,
                PALETTE_ORIGINAL, false, false, BLENDING_OFF);
    }

    public PlaybackSettings(boolean audioEnabled, int audioVolume, boolean integerScale,
                     int brightness, int contrast, String gbPalette,
                     boolean lcdCorrectionGbc, boolean lcdCorrectionGba, String frameBlending) {
        this(audioEnabled, audioVolume, integerScale, brightness, contrast, gbPalette,
                lcdCorrectionGbc, lcdCorrectionGba, frameBlending, EFFECT_NONE);
    }

    public PlaybackSettings(boolean audioEnabled, int audioVolume, boolean integerScale,
                     int brightness, int contrast, String gbPalette,
                     boolean lcdCorrectionGbc, boolean lcdCorrectionGba, String frameBlending,
                     String screenEffect) {
        this.audioEnabled = audioEnabled;
        this.audioVolume = clamp(audioVolume, 0, 100);
        this.integerScale = integerScale;
        this.brightness = clamp(brightness, -50, 50);
        this.contrast = clamp(contrast, 50, 150);
        this.gbPalette = oneOf(gbPalette, PALETTE_ORIGINAL, PALETTE_POCKET, PALETTE_GRAY);
        this.lcdCorrectionGbc = lcdCorrectionGbc;
        this.lcdCorrectionGba = lcdCorrectionGba;
        this.frameBlending = oneOf(frameBlending, BLENDING_OFF, BLENDING_MIX, BLENDING_GHOSTING);
        this.screenEffect = oneOf(screenEffect, EFFECT_NONE, EFFECT_SHARP, EFFECT_LCD);
    }

    public PlaybackSettings withAudio(boolean enabled, int volume) {
        return new PlaybackSettings(enabled, volume, integerScale, brightness, contrast,
                gbPalette, lcdCorrectionGbc, lcdCorrectionGba, frameBlending, screenEffect);
    }

    public PlaybackSettings withPicture(boolean integerScale, int brightness, int contrast) {
        return new PlaybackSettings(audioEnabled, audioVolume, integerScale, brightness, contrast,
                gbPalette, lcdCorrectionGbc, lcdCorrectionGba, frameBlending, screenEffect);
    }

    public PlaybackSettings withImage(String gbPalette, boolean lcdCorrectionGbc,
                                      boolean lcdCorrectionGba, String frameBlending) {
        return new PlaybackSettings(audioEnabled, audioVolume, integerScale, brightness, contrast,
                gbPalette, lcdCorrectionGbc, lcdCorrectionGba, frameBlending, screenEffect);
    }

    public PlaybackSettings withEffect(String effect) {
        return new PlaybackSettings(audioEnabled, audioVolume, integerScale, brightness, contrast,
                gbPalette, lcdCorrectionGbc, lcdCorrectionGba, frameBlending, effect);
    }

    /** RAVEN_VIDEO_EFFECT_NONE, _SHARP or _LCD, for NativeBridge. */
    public int effectCode() {
        return EFFECT_SHARP.equals(screenEffect) ? 1 : EFFECT_LCD.equals(screenEffect) ? 2 : 0;
    }

    public static PlaybackSettings load(Context context) {
        // One snapshot keeps a settings update coherent. Malformed values fall
        // back independently, preserving every other valid user preference.
        Map<String, ?> values = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getAll();
        return new PlaybackSettings(
                bool(values, "audio.enabled", true), integer(values, "audio.volume", 100),
                bool(values, "video.integer_scale", false), integer(values, "video.brightness", 0),
                integer(values, "video.contrast", 100),
                string(values, "video.gb_palette", PALETTE_ORIGINAL),
                bool(values, "video.lcd_gbc", false), bool(values, "video.lcd_gba", false),
                string(values, "video.frame_blending", BLENDING_OFF),
                string(values, "video.screen_effect", EFFECT_NONE));
    }

    public void save(Context context) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
                .putBoolean("audio.enabled", audioEnabled).putInt("audio.volume", audioVolume)
                .putBoolean("video.integer_scale", integerScale)
                .putInt("video.brightness", brightness).putInt("video.contrast", contrast)
                .putString("video.gb_palette", gbPalette)
                .putBoolean("video.lcd_gbc", lcdCorrectionGbc).putBoolean("video.lcd_gba", lcdCorrectionGba)
                .putString("video.frame_blending", frameBlending)
                .putString("video.screen_effect", screenEffect)
                .apply();
    }

    private static boolean bool(Map<String, ?> values, String key, boolean fallback) {
        Object value = values.get(key);
        return value instanceof Boolean ? (Boolean)value : fallback;
    }

    private static int integer(Map<String, ?> values, String key, int fallback) {
        Object value = values.get(key);
        return value instanceof Integer ? (Integer)value : fallback;
    }

    private static String string(Map<String, ?> values, String key, String fallback) {
        Object value = values.get(key);
        return value instanceof String ? (String)value : fallback;
    }

    /** The value when it is one of the choices, else the first (the default). */
    private static String oneOf(String value, String... choices) {
        for (String choice : choices) if (choice.equals(value)) return choice;
        return choices[0];
    }

    private static int clamp(int value, int low, int high) {
        return Math.max(low, Math.min(high, value));
    }
}
