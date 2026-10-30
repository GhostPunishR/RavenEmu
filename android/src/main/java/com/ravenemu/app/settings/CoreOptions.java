package com.ravenemu.app.settings;


import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** The settings as the RavenCore Libretro cores name their options, for
 * NativeBridge: alternating keys and values. The C frontend skips any the
 * open core does not declare. */
public final class CoreOptions {
    /** RavenCore's values for ConsoleSettings.GB_COLORS, in the same order. */
    private static final String[] GB_COLOR_VALUES = {
            "auto", "grayscale", "up", "a_up", "b_up", "left", "a_left", "b_left",
            "down", "a_down", "b_down", "right", "a_right", "b_right",
    };

    private CoreOptions() {}

    /** Every option of the core ("gb", "gbc" or "gba"), set before the game
     * loads. The real GBA BIOS only when one is installed, so the core never
     * runs another BIOS than the state identity says. */
    public static String[] atLoad(String core, PlaybackSettings image, ConsoleSettings console,
                                  boolean gbaBiosInstalled) {
        List<String> options = new ArrayList<>(Arrays.asList(live(core, image, console)));
        if ("gbc".equals(core)) {
            options.add("ravenemu_gbc_hardware");
            options.add(console.gbcOnGba ? "gba" : "gbc");
        } else if ("gba".equals(core)) {
            options.add("ravenemu_gba_bios");
            options.add(!gbaBiosInstalled || console.gbaBios == ConsoleSettings.BIOS_RAVENCORE ? "disabled"
                    : console.gbaBios == ConsoleSettings.BIOS_REAL ? "enabled" : "skip_intro");
        }
        return options.toArray(new String[0]);
    }

    /** The options the core rereads during the game: the image ones, and the
     * colors of the Game Boy games on the Game Boy Color. */
    public static String[] live(String core, PlaybackSettings image, ConsoleSettings console) {
        if (!"gb".equals(core) && !"gbc".equals(core) && !"gba".equals(core)) return new String[0];
        String blending = PlaybackSettings.BLENDING_OFF.equals(image.frameBlending)
                ? "disabled" : image.frameBlending;
        String prefix = "ravenemu_" + core + "_";
        switch (core) {
            case "gb":
                return new String[]{prefix + "palette", palette(image.gbPalette),
                        prefix + "frame_blending", blending};
            case "gbc":
                return new String[]{prefix + "color_correction", enabled(image.lcdCorrectionGbc),
                        prefix + "frame_blending", blending,
                        "ravenemu_gbc_dmg_colors", GB_COLOR_VALUES[console.gbColors]};
            default:
                return new String[]{prefix + "color_correction", enabled(image.lcdCorrectionGba),
                        prefix + "frame_blending", blending};
        }
    }

    /** The beta's "Original" is RavenCore's own Game Boy green: "dmg". */
    private static String palette(String profile) {
        if (PlaybackSettings.PALETTE_POCKET.equals(profile)) return "pocket";
        if (PlaybackSettings.PALETTE_GRAY.equals(profile)) return "gray";
        return "dmg";
    }

    private static String enabled(boolean value) {
        return value ? "enabled" : "disabled";
    }
}
