package com.ravenemu.app.settings;


import android.content.Context;
import java.util.Arrays;
import java.util.Locale;

/** Where the player moved each touch control: an offset in dp from its
 * default place, one layout for portrait and one for landscape. The view
 * keeps every control inside its own bounds. */
public final class ControlLayout {
    /** The controls, in the order of the offsets. */
    public static final String[] CONTROLS = {"dpad", "a", "b", "select", "start", "l", "r"};
    public static final ControlLayout DEFAULT = new ControlLayout(new float[CONTROLS.length * 2]);
    /** Far beyond any screen: a larger stored value is not a layout. */
    private static final float LIMIT = 4000f;

    private final float[] offsets;

    private ControlLayout(float[] offsets) { this.offsets = offsets; }

    public float dx(int control) { return offsets[control * 2]; }
    public float dy(int control) { return offsets[control * 2 + 1]; }

    public ControlLayout with(int control, float dx, float dy) {
        float[] next = offsets.clone();
        next[control * 2] = bounded(dx);
        next[control * 2 + 1] = bounded(dy);
        return new ControlLayout(next);
    }

    public boolean isDefault() {
        for (float offset : offsets) if (offset != 0f) return false;
        return true;
    }

    public static ControlLayout load(Context context, boolean landscape) {
        Object stored = context.getSharedPreferences(PlaybackSettings.PREFERENCES, Context.MODE_PRIVATE)
                .getAll().get(key(landscape));
        return stored instanceof String ? parse((String)stored) : DEFAULT;
    }

    public void save(Context context, boolean landscape) {
        context.getSharedPreferences(PlaybackSettings.PREFERENCES, Context.MODE_PRIVATE).edit()
                .putString(key(landscape), toString()).apply();
    }

    /** Both orientations back to the original places. */
    public static void reset(Context context) {
        context.getSharedPreferences(PlaybackSettings.PREFERENCES, Context.MODE_PRIVATE).edit()
                .remove(key(false)).remove(key(true)).apply();
    }

    private static String key(boolean landscape) {
        return landscape ? "controls.layout.landscape" : "controls.layout.portrait";
    }

    /** Fourteen numbers, two per control; anything else is the default layout. */
    static ControlLayout parse(String stored) {
        String[] parts = stored.split(",", -1);
        if (parts.length != CONTROLS.length * 2) return DEFAULT;
        float[] offsets = new float[parts.length];
        try {
            for (int i = 0; i < parts.length; i++) {
                float offset = Float.parseFloat(parts[i].trim());
                if (Float.isNaN(offset) || Math.abs(offset) > LIMIT) return DEFAULT;
                offsets[i] = bounded(offset);
            }
        } catch (NumberFormatException malformed) {
            return DEFAULT;
        }
        return new ControlLayout(offsets);
    }

    @Override public String toString() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < offsets.length; i++) {
            if (i > 0) text.append(',');
            text.append(String.format(Locale.ROOT, "%.1f", offsets[i]));
        }
        return text.toString();
    }

    @Override public boolean equals(Object other) {
        return other instanceof ControlLayout && Arrays.equals(offsets, ((ControlLayout)other).offsets);
    }

    @Override public int hashCode() { return Arrays.hashCode(offsets); }

    /** A tenth of a dp, as stored: a saved layout reads back equal. */
    private static float bounded(float value) {
        if (Float.isNaN(value)) return 0f;
        return Math.round(Math.max(-LIMIT, Math.min(LIMIT, value)) * 10f) / 10f;
    }
}
