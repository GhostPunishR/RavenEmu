package com.ravenemu.app.settings;


import org.junit.Test;
import static org.junit.Assert.*;

/** The image settings, as the RavenCore Libretro cores name their options. */
public final class CoreOptionsTest {
    @Test public void defaultsAreTheCoresOwnValues() {
        PlaybackSettings defaults = PlaybackSettings.DEFAULTS;
        assertArrayEquals(new String[]{"ravenemu_gb_palette", "dmg", "ravenemu_gb_frame_blending", "disabled"},
                CoreOptions.forSystem("gb", defaults));
        assertArrayEquals(new String[]{"ravenemu_gbc_color_correction", "disabled",
                "ravenemu_gbc_frame_blending", "disabled"}, CoreOptions.forSystem("gbc", defaults));
        assertArrayEquals(new String[]{"ravenemu_gba_color_correction", "disabled",
                "ravenemu_gba_frame_blending", "disabled"}, CoreOptions.forSystem("gba", defaults));
    }

    @Test public void eachConsoleGetsOnlyItsOwnSettings() {
        PlaybackSettings chosen = PlaybackSettings.DEFAULTS.withImage(PlaybackSettings.PALETTE_GRAY, true, false,
                PlaybackSettings.BLENDING_MIX);
        assertArrayEquals(new String[]{"ravenemu_gb_palette", "gray", "ravenemu_gb_frame_blending", "mix"},
                CoreOptions.forSystem("gb", chosen));
        assertArrayEquals(new String[]{"ravenemu_gbc_color_correction", "enabled",
                "ravenemu_gbc_frame_blending", "mix"}, CoreOptions.forSystem("gbc", chosen));
        assertArrayEquals(new String[]{"ravenemu_gba_color_correction", "disabled",
                "ravenemu_gba_frame_blending", "mix"}, CoreOptions.forSystem("gba", chosen));
        PlaybackSettings pocket = chosen.withImage(PlaybackSettings.PALETTE_POCKET, false, true,
                PlaybackSettings.BLENDING_GHOSTING);
        assertEquals("pocket", CoreOptions.forSystem("gb", pocket)[1]);
        assertEquals("ghosting", CoreOptions.forSystem("gb", pocket)[3]);
        assertEquals("enabled", CoreOptions.forSystem("gba", pocket)[1]);
    }

    @Test public void anUnknownConsoleGetsNoOption() {
        assertEquals(0, CoreOptions.forSystem("nes", PlaybackSettings.DEFAULTS).length);
        assertEquals(0, CoreOptions.forSystem(null, PlaybackSettings.DEFAULTS).length);
    }
}
