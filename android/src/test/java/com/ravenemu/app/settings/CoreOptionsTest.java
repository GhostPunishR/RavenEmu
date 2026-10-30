package com.ravenemu.app.settings;


import org.junit.Test;
import static org.junit.Assert.*;

/** The settings, as the RavenCore Libretro cores name their options. */
public final class CoreOptionsTest {
    private static final ConsoleSettings CONSOLE = ConsoleSettings.DEFAULTS;

    @Test public void defaultsAreTheCoresOwnValues() {
        PlaybackSettings defaults = PlaybackSettings.DEFAULTS;
        assertArrayEquals(new String[]{"ravenemu_gb_palette", "dmg", "ravenemu_gb_frame_blending", "disabled"},
                CoreOptions.atLoad("gb", defaults, CONSOLE, false));
        assertArrayEquals(new String[]{"ravenemu_gbc_color_correction", "disabled",
                "ravenemu_gbc_frame_blending", "disabled", "ravenemu_gbc_dmg_colors", "auto",
                "ravenemu_gbc_hardware", "gbc"}, CoreOptions.atLoad("gbc", defaults, CONSOLE, false));
        assertArrayEquals(new String[]{"ravenemu_gba_color_correction", "disabled",
                "ravenemu_gba_frame_blending", "disabled", "ravenemu_gba_bios", "disabled"},
                CoreOptions.atLoad("gba", defaults, CONSOLE, false));
    }

    @Test public void eachCoreGetsOnlyItsOwnImageSettings() {
        PlaybackSettings chosen = PlaybackSettings.DEFAULTS.withImage(PlaybackSettings.PALETTE_GRAY, true, false,
                PlaybackSettings.BLENDING_MIX);
        assertArrayEquals(new String[]{"ravenemu_gb_palette", "gray", "ravenemu_gb_frame_blending", "mix"},
                CoreOptions.live("gb", chosen, CONSOLE));
        assertArrayEquals(new String[]{"ravenemu_gbc_color_correction", "enabled",
                "ravenemu_gbc_frame_blending", "mix", "ravenemu_gbc_dmg_colors", "auto"},
                CoreOptions.live("gbc", chosen, CONSOLE));
        assertArrayEquals(new String[]{"ravenemu_gba_color_correction", "disabled",
                "ravenemu_gba_frame_blending", "mix"}, CoreOptions.live("gba", chosen, CONSOLE));
        PlaybackSettings pocket = chosen.withImage(PlaybackSettings.PALETTE_POCKET, false, true,
                PlaybackSettings.BLENDING_GHOSTING);
        assertEquals("pocket", CoreOptions.live("gb", pocket, CONSOLE)[1]);
        assertEquals("ghosting", CoreOptions.live("gb", pocket, CONSOLE)[3]);
        assertEquals("enabled", CoreOptions.live("gba", pocket, CONSOLE)[1]);
    }

    @Test public void consoleOptionsFollowRavenCoresValues() {
        PlaybackSettings image = PlaybackSettings.DEFAULTS;
        ConsoleSettings colors = CONSOLE.withGbInColor(true).withGbColors(3).withGbcOnGba(true);
        String[] gbc = CoreOptions.atLoad("gbc", image, colors, false);
        assertEquals("a_up", gbc[5]);
        assertEquals("gba", gbc[7]);
        assertEquals("b_right", CoreOptions.live("gbc", image,
                colors.withGbColors(ConsoleSettings.GB_COLORS.length - 1))[5]);
        assertEquals("grayscale", CoreOptions.live("gbc", image, colors.withGbColors(1))[5]);
    }

    @Test public void theRealBiosOnlyWhenOneIsInstalled() {
        PlaybackSettings image = PlaybackSettings.DEFAULTS;
        ConsoleSettings real = CONSOLE.withGbaBios(ConsoleSettings.BIOS_REAL);
        assertEquals("enabled", CoreOptions.atLoad("gba", image, real, true)[5]);
        assertEquals("disabled", CoreOptions.atLoad("gba", image, real, false)[5]);
        ConsoleSettings quick = CONSOLE.withGbaBios(ConsoleSettings.BIOS_REAL_SKIP_INTRO);
        assertEquals("skip_intro", CoreOptions.atLoad("gba", image, quick, true)[5]);
        assertEquals("disabled", CoreOptions.atLoad("gba", image, CONSOLE, true)[5]);
    }

    @Test public void theConsoleAndTheBiosAreNeverChangedDuringTheGame() {
        ConsoleSettings everything = CONSOLE.withGbcOnGba(true).withGbaBios(ConsoleSettings.BIOS_REAL);
        for (String core : new String[]{"gb", "gbc", "gba"}) {
            String[] live = CoreOptions.live(core, PlaybackSettings.DEFAULTS, everything);
            for (int i = 0; i < live.length; i += 2) {
                assertNotEquals("ravenemu_gbc_hardware", live[i]);
                assertNotEquals("ravenemu_gba_bios", live[i]);
            }
        }
    }

    @Test public void anUnknownCoreGetsNoOption() {
        assertEquals(0, CoreOptions.atLoad("nes", PlaybackSettings.DEFAULTS, CONSOLE, true).length);
        assertEquals(0, CoreOptions.live(null, PlaybackSettings.DEFAULTS, CONSOLE).length);
    }
}
