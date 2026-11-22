package com.ravenemu.app.settings;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/** Settings → Vidéo → Shaders: the imported RetroArch preset the picture is
 * drawn with ("" for the built-in effects), and the parameters the player
 * changed, kept for each preset. Stored with the playback preferences; a
 * native addition after RetroArch's shaders (the beta has none). */
public final class ShaderSettings {
    static final String PRESET = "shader.preset";
    /** shader.parameter.<preset>\n<name>: the preset's own name may hold anything. */
    static final String PARAMETER = "shader.parameter.";

    public static final ShaderSettings DEFAULTS = new ShaderSettings("", Collections.emptyMap());

    /** A ShaderPacks preset id, or "" for none. */
    public final String preset;
    /** The values the player set for this preset, by parameter name. */
    public final Map<String, Float> parameters;

    ShaderSettings(String preset, Map<String, Float> parameters) {
        this.preset = preset == null ? "" : preset;
        this.parameters = Collections.unmodifiableMap(new TreeMap<>(parameters));
    }

    public static ShaderSettings load(Context context) {
        SharedPreferences preferences = preferences(context);
        Object stored = preferences.getAll().get(PRESET);
        String preset = stored instanceof String ? (String) stored : "";
        return new ShaderSettings(preset, parametersOf(preferences, preset));
    }

    /** Another preset, with the values the player once set for it. */
    public static ShaderSettings choose(Context context, String preset) {
        SharedPreferences preferences = preferences(context);
        preferences.edit().putString(PRESET, preset == null ? "" : preset).apply();
        return new ShaderSettings(preset, parametersOf(preferences, preset == null ? "" : preset));
    }

    public ShaderSettings withParameter(Context context, String name, float value) {
        if (preset.isEmpty() || name == null || name.isEmpty()) return this;
        preferences(context).edit().putFloat(key(preset, name), value).apply();
        Map<String, Float> changed = new TreeMap<>(parameters);
        changed.put(name, value);
        return new ShaderSettings(preset, changed);
    }

    /** "Valeurs du preset": the player's values for this preset forgotten. */
    public ShaderSettings withoutParameters(Context context) {
        SharedPreferences preferences = preferences(context);
        SharedPreferences.Editor editor = preferences.edit();
        String prefix = PARAMETER + preset + "\n";
        for (String key : preferences.getAll().keySet()) if (key.startsWith(prefix)) editor.remove(key);
        editor.apply();
        return new ShaderSettings(preset, Collections.emptyMap());
    }

    private static Map<String, Float> parametersOf(SharedPreferences preferences, String preset) {
        Map<String, Float> values = new TreeMap<>();
        if (preset.isEmpty()) return values;
        String prefix = PARAMETER + preset + "\n";
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet())
            if (entry.getKey().startsWith(prefix) && entry.getValue() instanceof Float)
                values.put(entry.getKey().substring(prefix.length()), (Float) entry.getValue());
        return values;
    }

    private static String key(String preset, String name) { return PARAMETER + preset + "\n" + name; }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PlaybackSettings.PREFERENCES, Context.MODE_PRIVATE);
    }
}
