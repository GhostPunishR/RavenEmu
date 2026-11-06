package com.ravenemu.app.settings;


import org.junit.Test;
import static org.junit.Assert.*;

/** The settings, as the RavenCore Libretro cores name their options. */
public final class CoreOptionsTest {
    private static final ConsoleSettings CONSOLE = ConsoleSettings.DEFAULTS;
    private static final CartridgeSettings CARTRIDGE = CartridgeSettings.DEFAULTS;

    @Test public void defaultsAreTheCoresOwnValues() {
        PlaybackSettings defaults = PlaybackSettings.DEFAULTS;
        assertArrayEquals(new String[]{"ravenemu_gb_palette", "dmg", "ravenemu_gb_frame_blending", "disabled",
                        "ravenemu_gb_device_sensors", "enabled"},
                CoreOptions.atLoad("gb", defaults, CONSOLE, CARTRIDGE, false));
        assertArrayEquals(new String[]{"ravenemu_gbc_color_correction", "disabled",
                "ravenemu_gbc_frame_blending", "disabled", "ravenemu_gbc_dmg_colors", "auto",
                "ravenemu_gbc_device_sensors", "enabled",
                "ravenemu_gbc_hardware", "gbc"}, CoreOptions.atLoad("gbc", defaults, CONSOLE, CARTRIDGE, false));
        assertArrayEquals(new String[]{"ravenemu_gba_color_correction", "disabled",
                "ravenemu_gba_frame_blending", "disabled", "ravenemu_gba_device_sensors", "enabled",
                "ravenemu_gba_solar_light", "sensor", "ravenemu_gba_bios", "disabled"},
                CoreOptions.atLoad("gba", defaults, CONSOLE, CARTRIDGE, false));
    }

    @Test public void eachCoreGetsOnlyItsOwnImageSettings() {
        PlaybackSettings chosen = PlaybackSettings.DEFAULTS.withImage(PlaybackSettings.PALETTE_GRAY, true, false,
                PlaybackSettings.BLENDING_MIX);
        assertArrayEquals(new String[]{"ravenemu_gb_palette", "gray", "ravenemu_gb_frame_blending", "mix",
                        "ravenemu_gb_device_sensors", "enabled"},
                CoreOptions.live("gb", chosen, CONSOLE, CARTRIDGE));
        assertArrayEquals(new String[]{"ravenemu_gbc_color_correction", "enabled",
                "ravenemu_gbc_frame_blending", "mix", "ravenemu_gbc_dmg_colors", "auto",
                "ravenemu_gbc_device_sensors", "enabled"},
                CoreOptions.live("gbc", chosen, CONSOLE, CARTRIDGE));
        assertEquals("disabled", option(CoreOptions.live("gba", chosen, CONSOLE, CARTRIDGE),
                "ravenemu_gba_color_correction"));
        assertEquals("mix", option(CoreOptions.live("gba", chosen, CONSOLE, CARTRIDGE),
                "ravenemu_gba_frame_blending"));
        PlaybackSettings pocket = chosen.withImage(PlaybackSettings.PALETTE_POCKET, false, true,
                PlaybackSettings.BLENDING_GHOSTING);
        assertEquals("pocket", option(CoreOptions.live("gb", pocket, CONSOLE, CARTRIDGE), "ravenemu_gb_palette"));
        assertEquals("ghosting", option(CoreOptions.live("gb", pocket, CONSOLE, CARTRIDGE),
                "ravenemu_gb_frame_blending"));
        assertEquals("enabled", option(CoreOptions.live("gba", pocket, CONSOLE, CARTRIDGE),
                "ravenemu_gba_color_correction"));
    }

    @Test public void consoleOptionsFollowRavenCoresValues() {
        PlaybackSettings image = PlaybackSettings.DEFAULTS;
        ConsoleSettings colors = CONSOLE.withGbInColor(true).withGbColors(3).withGbcOnGba(true);
        String[] gbc = CoreOptions.atLoad("gbc", image, colors, CARTRIDGE, false);
        assertEquals("a_up", option(gbc, "ravenemu_gbc_dmg_colors"));
        assertEquals("gba", option(gbc, "ravenemu_gbc_hardware"));
        assertEquals("b_right", option(CoreOptions.live("gbc", image,
                colors.withGbColors(ConsoleSettings.GB_COLORS.length - 1), CARTRIDGE), "ravenemu_gbc_dmg_colors"));
        assertEquals("grayscale", option(CoreOptions.live("gbc", image, colors.withGbColors(1), CARTRIDGE),
                "ravenemu_gbc_dmg_colors"));
    }

    @Test public void theRealBiosOnlyWhenOneIsInstalled() {
        PlaybackSettings image = PlaybackSettings.DEFAULTS;
        ConsoleSettings real = CONSOLE.withGbaBios(ConsoleSettings.BIOS_REAL);
        assertEquals("enabled", option(CoreOptions.atLoad("gba", image, real, CARTRIDGE, true), "ravenemu_gba_bios"));
        assertEquals("disabled", option(CoreOptions.atLoad("gba", image, real, CARTRIDGE, false), "ravenemu_gba_bios"));
        ConsoleSettings quick = CONSOLE.withGbaBios(ConsoleSettings.BIOS_REAL_SKIP_INTRO);
        assertEquals("skip_intro", option(CoreOptions.atLoad("gba", image, quick, CARTRIDGE, true),
                "ravenemu_gba_bios"));
        assertEquals("disabled", option(CoreOptions.atLoad("gba", image, CONSOLE, CARTRIDGE, true),
                "ravenemu_gba_bios"));
    }

    @Test public void thePhonesSensorsAndTheSunFollowTheCartridgeSettings() {
        PlaybackSettings image = PlaybackSettings.DEFAULTS;
        CartridgeSettings off = CARTRIDGE.withSensors(false).withSolarLevel(70);
        for (String core : new String[]{"gb", "gbc", "gba"})
            assertEquals("disabled", option(CoreOptions.live(core, image, CONSOLE, off),
                    "ravenemu_" + core + "_device_sensors"));
        assertEquals("Without the sensors, the chosen sun", "70",
                option(CoreOptions.live("gba", image, CONSOLE, off), "ravenemu_gba_solar_light"));
        assertEquals("0", option(CoreOptions.live("gba", image, CONSOLE,
                CARTRIDGE.withSolarAuto(false).withSolarLevel(0)), "ravenemu_gba_solar_light"));
        assertEquals("100", option(CoreOptions.live("gba", image, CONSOLE,
                CARTRIDGE.withSolarAuto(false).withSolarLevel(100)), "ravenemu_gba_solar_light"));
        assertNull("Only the GBA core has a solar sensor",
                option(CoreOptions.live("gbc", image, CONSOLE, CARTRIDGE), "ravenemu_gba_solar_light"));
    }

    @Test public void theConsoleAndTheBiosAreNeverChangedDuringTheGame() {
        ConsoleSettings everything = CONSOLE.withGbcOnGba(true).withGbaBios(ConsoleSettings.BIOS_REAL);
        for (String core : new String[]{"gb", "gbc", "gba"}) {
            String[] live = CoreOptions.live(core, PlaybackSettings.DEFAULTS, everything, CARTRIDGE);
            assertNull(option(live, "ravenemu_gbc_hardware"));
            assertNull(option(live, "ravenemu_gba_bios"));
        }
    }

    @Test public void anUnknownCoreGetsNoOption() {
        assertEquals(0, CoreOptions.atLoad("nes", PlaybackSettings.DEFAULTS, CONSOLE, CARTRIDGE, true).length);
        assertEquals(0, CoreOptions.live(null, PlaybackSettings.DEFAULTS, CONSOLE, CARTRIDGE).length);
    }

    /** The value given to `key`, or null without it; keys and values alternate. */
    private static String option(String[] options, String key) {
        assertEquals("Keys and values alternate", 0, options.length % 2);
        for (int i = 0; i < options.length; i += 2) if (options[i].equals(key)) return options[i + 1];
        return null;
    }
}
