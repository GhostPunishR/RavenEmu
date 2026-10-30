package com.ravenemu.app.settings;


import android.content.Context;
import android.content.SharedPreferences;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36)
public final class ConsoleSettingsTest {
    private Context context;
    private SharedPreferences preferences;

    @Before public void prepare() {
        context = RuntimeEnvironment.getApplication();
        preferences = context.getSharedPreferences(PlaybackSettings.PREFERENCES, Context.MODE_PRIVATE);
        preferences.edit().clear().commit();
    }

    @Test public void defaultsRunEachGameOnItsOwnConsoleAndReadingNeverWrites() {
        ConsoleSettings value = ConsoleSettings.load(context);
        assertFalse(value.gbInColor);
        assertEquals(0, value.gbColors);
        assertFalse(value.gbcOnGba);
        assertEquals(ConsoleSettings.BIOS_RAVENCORE, value.gbaBios);
        assertEquals("gb", value.coreFor("gb"));
        assertEquals("gbc", value.coreFor("gbc"));
        assertEquals("gba", value.coreFor("gba"));
        assertTrue(preferences.getAll().isEmpty());
    }

    @Test public void gameBoyGamesInColorRunOnTheGameBoyColorCore() {
        ConsoleSettings color = ConsoleSettings.DEFAULTS.withGbInColor(true);
        assertEquals("gbc", color.coreFor("gb"));
        assertEquals("gbc", color.coreFor("gbc"));
        assertEquals("gba", color.coreFor("gba"));
    }

    @Test public void settingsPersistAlongsideThePlaybackOnes() {
        PlaybackSettings.DEFAULTS.withAudio(false, 30).save(context);
        new ConsoleSettings(true, 5, true, ConsoleSettings.BIOS_REAL_SKIP_INTRO).save(context);
        ConsoleSettings saved = ConsoleSettings.load(context);
        assertTrue(saved.gbInColor);
        assertEquals(5, saved.gbColors);
        assertTrue(saved.gbcOnGba);
        assertEquals(ConsoleSettings.BIOS_REAL_SKIP_INTRO, saved.gbaBios);
        assertEquals("Console settings must keep the sound ones", 30, PlaybackSettings.load(context).audioVolume);
        ConsoleSettings changed = saved.withGbColors(2);
        assertTrue(changed.gbInColor);
        assertTrue(changed.gbcOnGba);
        assertEquals(ConsoleSettings.BIOS_REAL_SKIP_INTRO, changed.gbaBios);
    }

    @Test public void malformedOrOutOfRangeValuesFallBackOnTheirOwn() {
        preferences.edit().putString("console.gb_in_color", "yes").putInt("console.gb_colors", 99)
                .putBoolean("console.gbc_on_gba", true).putInt("console.gba_bios", -4).commit();
        ConsoleSettings value = ConsoleSettings.load(context);
        assertFalse(value.gbInColor);
        assertEquals(ConsoleSettings.GB_COLORS.length - 1, value.gbColors);
        assertTrue(value.gbcOnGba);
        assertEquals(ConsoleSettings.BIOS_RAVENCORE, value.gbaBios);
    }

    @Test public void onlyTheGbaHardwareAndARealBiosSetStatesApart() {
        ConsoleSettings defaults = ConsoleSettings.DEFAULTS;
        assertEquals("", defaults.stateVariant("gb", null));
        assertEquals("", defaults.stateVariant("gbc", null));
        assertEquals("", defaults.stateVariant("gba", "0123456789abcdef"));
        assertEquals("|gbc-on-gba", defaults.withGbcOnGba(true).stateVariant("gbc", null));
        assertEquals("", defaults.withGbcOnGba(true).stateVariant("gb", null));
        ConsoleSettings real = defaults.withGbaBios(ConsoleSettings.BIOS_REAL);
        assertEquals("|gba-bios-0123456789abcdef", real.stateVariant("gba", "0123456789abcdef"));
        assertEquals("No BIOS installed: RavenCore's own runs", "", real.stateVariant("gba", null));
        assertEquals("With or without its animation, the same BIOS",
                real.stateVariant("gba", "00"), real.withGbaBios(ConsoleSettings.BIOS_REAL_SKIP_INTRO)
                        .stateVariant("gba", "00"));
    }
}
