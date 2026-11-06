package com.ravenemu.app.settings;


import android.content.Context;
import java.util.Map;

/** The beta's Settings → Console: the console that runs the games. Stored
 * with the playback preferences of the native preview; no beta data is read. */
public final class ConsoleSettings {
    public static final int BIOS_RAVENCORE = 0;
    public static final int BIOS_REAL = 1;
    public static final int BIOS_REAL_SKIP_INTRO = 2;

    /** The colors a Game Boy game can get on a Game Boy Color, in RavenCore's
     * order: the boot ROM's choice, then the combinations held during its logo. */
    public static final String[] GB_COLORS = {
            "Celles de la boot ROM, jeu par jeu", "Gris", "Haut : brun", "A + Haut : rouge", "B + Haut : sépia",
            "Gauche : bleu", "A + Gauche : bleu-violet", "B + Gauche : gris", "Bas : pastel",
            "A + Bas : jaune et rouge", "B + Bas : jaune et brun", "Droite : vert et rouge",
            "A + Droite : vert et bleu", "B + Droite : négatif",
    };

    public static final ConsoleSettings DEFAULTS = new ConsoleSettings(false, 0, false, BIOS_RAVENCORE);

    /** Game Boy games run on the Game Boy Color core, which colors them. */
    public final boolean gbInColor;
    /** Their colors: an index in GB_COLORS. */
    public final int gbColors;
    /** GB and GBC games on the GBC inside a Game Boy Advance. */
    public final boolean gbcOnGba;
    /** RavenCore's GBA BIOS, the player's real one, or the real one without its animation. */
    public final int gbaBios;

    public ConsoleSettings(boolean gbInColor, int gbColors, boolean gbcOnGba, int gbaBios) {
        this.gbInColor = gbInColor;
        this.gbColors = clamp(gbColors, 0, GB_COLORS.length - 1);
        this.gbcOnGba = gbcOnGba;
        this.gbaBios = clamp(gbaBios, BIOS_RAVENCORE, BIOS_REAL_SKIP_INTRO);
    }

    public ConsoleSettings withGbInColor(boolean value) {
        return new ConsoleSettings(value, gbColors, gbcOnGba, gbaBios);
    }

    public ConsoleSettings withGbColors(int value) {
        return new ConsoleSettings(gbInColor, value, gbcOnGba, gbaBios);
    }

    public ConsoleSettings withGbcOnGba(boolean value) {
        return new ConsoleSettings(gbInColor, gbColors, value, gbaBios);
    }

    public ConsoleSettings withGbaBios(int value) {
        return new ConsoleSettings(gbInColor, gbColors, gbcOnGba, value);
    }

    /** The Libretro core that runs a game of this console: "gb", "gbc" or "gba". */
    public String coreFor(String system) {
        return "gb".equals(system) && gbInColor ? "gbc" : system;
    }

    /** Added to the core's state identity: a state taken on the GBC of a GBA,
     * or with a real BIOS (its SHA-256 prefix), only reloads in that setup.
     * Empty with the defaults, so the states already taken stay usable. */
    public String stateVariant(String core, String biosDigest) {
        if ("gbc".equals(core) && gbcOnGba) return "|gbc-on-gba";
        if ("gba".equals(core) && gbaBios != BIOS_RAVENCORE && biosDigest != null) return "|gba-bios-" + biosDigest;
        return "";
    }

    public static ConsoleSettings load(Context context) {
        Map<String, ?> values = context.getSharedPreferences(PlaybackSettings.PREFERENCES, Context.MODE_PRIVATE)
                .getAll();
        return new ConsoleSettings(bool(values, "console.gb_in_color"), integer(values, "console.gb_colors"),
                bool(values, "console.gbc_on_gba"), integer(values, "console.gba_bios"));
    }

    public void save(Context context) {
        context.getSharedPreferences(PlaybackSettings.PREFERENCES, Context.MODE_PRIVATE).edit()
                .putBoolean("console.gb_in_color", gbInColor).putInt("console.gb_colors", gbColors)
                .putBoolean("console.gbc_on_gba", gbcOnGba).putInt("console.gba_bios", gbaBios)
                .apply();
    }

    // Every default is false or 0; a malformed value falls back on its own.
    private static boolean bool(Map<String, ?> values, String key) {
        Object value = values.get(key);
        return value instanceof Boolean && (Boolean)value;
    }

    private static int integer(Map<String, ?> values, String key) {
        Object value = values.get(key);
        return value instanceof Integer ? (Integer)value : 0;
    }

    private static int clamp(int value, int low, int high) {
        return Math.max(low, Math.min(high, value));
    }
}
