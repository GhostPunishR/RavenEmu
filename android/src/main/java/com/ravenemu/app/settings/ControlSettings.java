package com.ravenemu.app.settings;


import android.content.Context;
import java.util.Map;

/** The beta's Settings → Interface, touch controls part: haptics, size,
 * vertical spacing and style. Stored with the playback preferences of the
 * native preview; no beta data is read. */
public final class ControlSettings {
    public static final String STYLE_CLASSIC = "classic";
    public static final String STYLE_CONTRAST = "contrast";
    public static final int SCALE_MIN = 80, SCALE_MAX = 115;
    public static final int GAP_MIN = 20, GAP_MAX = 80;

    public static final ControlSettings DEFAULTS = new ControlSettings(false, 100, 46, STYLE_CLASSIC);

    /** A short vibration as a button is pressed. */
    public final boolean haptics;
    /** The D-pad and A/B size, in percent. */
    public final int scale;
    /** The space between the rows of controls, in dp. */
    public final int gap;
    public final String style;

    public ControlSettings(boolean haptics, int scale, int gap, String style) {
        this.haptics = haptics;
        this.scale = Math.max(SCALE_MIN, Math.min(SCALE_MAX, scale));
        this.gap = Math.max(GAP_MIN, Math.min(GAP_MAX, gap));
        this.style = STYLE_CONTRAST.equals(style) ? STYLE_CONTRAST : STYLE_CLASSIC;
    }

    public ControlSettings withHaptics(boolean value) { return new ControlSettings(value, scale, gap, style); }
    public ControlSettings withScale(int value) { return new ControlSettings(haptics, value, gap, style); }
    public ControlSettings withGap(int value) { return new ControlSettings(haptics, scale, value, style); }
    public ControlSettings withStyle(String value) { return new ControlSettings(haptics, scale, gap, value); }

    public boolean highContrast() { return STYLE_CONTRAST.equals(style); }

    public static ControlSettings load(Context context) {
        Map<String, ?> values = context.getSharedPreferences(PlaybackSettings.PREFERENCES, Context.MODE_PRIVATE)
                .getAll();
        Object haptics = values.get("controls.haptics");
        Object scale = values.get("controls.scale");
        Object gap = values.get("controls.gap");
        Object style = values.get("controls.style");
        return new ControlSettings(haptics instanceof Boolean ? (Boolean)haptics : DEFAULTS.haptics,
                scale instanceof Integer ? (Integer)scale : DEFAULTS.scale,
                gap instanceof Integer ? (Integer)gap : DEFAULTS.gap,
                style instanceof String ? (String)style : DEFAULTS.style);
    }

    public void save(Context context) {
        context.getSharedPreferences(PlaybackSettings.PREFERENCES, Context.MODE_PRIVATE).edit()
                .putBoolean("controls.haptics", haptics).putInt("controls.scale", scale)
                .putInt("controls.gap", gap).putString("controls.style", style)
                .apply();
    }
}
