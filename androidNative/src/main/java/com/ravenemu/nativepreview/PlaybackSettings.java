package com.ravenemu.nativepreview;

import android.content.Context;
import java.util.Map;

/** Immutable preferences of the independent native preview. No beta data is read. */
final class PlaybackSettings {
    static final String PREFERENCES = "native-playback-settings";
    static final PlaybackSettings DEFAULTS = new PlaybackSettings(true, 100, false, 0, 100);

    final boolean audioEnabled;
    final int audioVolume;
    final boolean integerScale;
    final int brightness;
    final int contrast;

    PlaybackSettings(boolean audioEnabled, int audioVolume, boolean integerScale,
                     int brightness, int contrast) {
        this.audioEnabled = audioEnabled;
        this.audioVolume = clamp(audioVolume, 0, 100);
        this.integerScale = integerScale;
        this.brightness = clamp(brightness, -50, 50);
        this.contrast = clamp(contrast, 50, 150);
    }

    static PlaybackSettings load(Context context) {
        // One snapshot keeps a settings update coherent. Malformed values fall
        // back independently, preserving every other valid user preference.
        Map<String, ?> values = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getAll();
        return new PlaybackSettings(
                bool(values, "audio.enabled", true), integer(values, "audio.volume", 100),
                bool(values, "video.integer_scale", false), integer(values, "video.brightness", 0),
                integer(values, "video.contrast", 100));
    }

    void save(Context context) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
                .putBoolean("audio.enabled", audioEnabled).putInt("audio.volume", audioVolume)
                .putBoolean("video.integer_scale", integerScale)
                .putInt("video.brightness", brightness).putInt("video.contrast", contrast)
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

    private static int clamp(int value, int low, int high) {
        return Math.max(low, Math.min(high, value));
    }
}
