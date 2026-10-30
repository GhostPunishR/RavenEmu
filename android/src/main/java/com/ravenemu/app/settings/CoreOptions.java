package com.ravenemu.app.settings;


/** The image settings as the RavenCore Libretro cores name their options,
 * for NativeBridge: alternating keys and values. The C frontend skips any
 * the open core does not declare. */
public final class CoreOptions {
    private CoreOptions() {}

    public static String[] forSystem(String system, PlaybackSettings settings) {
        if (!"gb".equals(system) && !"gbc".equals(system) && !"gba".equals(system)) return new String[0];
        String blending = PlaybackSettings.BLENDING_OFF.equals(settings.frameBlending)
                ? "disabled" : settings.frameBlending;
        String prefix = "ravenemu_" + system + "_";
        switch (system) {
            case "gb":
                return new String[]{prefix + "palette", palette(settings.gbPalette),
                        prefix + "frame_blending", blending};
            case "gbc":
                return new String[]{prefix + "color_correction", enabled(settings.lcdCorrectionGbc),
                        prefix + "frame_blending", blending};
            default:
                return new String[]{prefix + "color_correction", enabled(settings.lcdCorrectionGba),
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
