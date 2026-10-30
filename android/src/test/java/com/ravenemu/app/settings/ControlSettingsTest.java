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
public final class ControlSettingsTest {
    private Context context;
    private SharedPreferences preferences;

    @Before public void prepare() {
        context = RuntimeEnvironment.getApplication();
        preferences = context.getSharedPreferences(PlaybackSettings.PREFERENCES, Context.MODE_PRIVATE);
        preferences.edit().clear().commit();
    }

    @Test public void defaultsAreTheBetasAndReadingNeverWrites() {
        ControlSettings value = ControlSettings.load(context);
        assertFalse(value.haptics);
        assertEquals(100, value.scale);
        assertEquals(46, value.gap);
        assertEquals(ControlSettings.STYLE_CLASSIC, value.style);
        assertFalse(value.highContrast());
        assertTrue(preferences.getAll().isEmpty());
    }

    @Test public void settingsPersistAlongsideTheOthersAndChangingOneKeepsTheRest() {
        new ConsoleSettings(true, 4, false, 0).save(context);
        new ControlSettings(true, 112, 30, ControlSettings.STYLE_CONTRAST).save(context);
        ControlSettings saved = ControlSettings.load(context);
        assertTrue(saved.haptics);
        assertEquals(112, saved.scale);
        assertEquals(30, saved.gap);
        assertTrue(saved.highContrast());
        assertEquals("Console settings must stay", 4, ConsoleSettings.load(context).gbColors);
        ControlSettings changed = saved.withGap(60);
        assertTrue(changed.haptics);
        assertEquals(112, changed.scale);
        assertTrue(changed.highContrast());
        assertEquals(ControlSettings.STYLE_CLASSIC, changed.withStyle("neon").style);
    }

    @Test public void outOfRangeOrMalformedValuesFallBackOnTheirOwn() {
        preferences.edit().putInt("controls.scale", 400).putInt("controls.gap", 1)
                .putString("controls.haptics", "true").putInt("controls.style", 2).commit();
        ControlSettings value = ControlSettings.load(context);
        assertEquals(ControlSettings.SCALE_MAX, value.scale);
        assertEquals(ControlSettings.GAP_MIN, value.gap);
        assertFalse(value.haptics);
        assertEquals(ControlSettings.STYLE_CLASSIC, value.style);
    }
}
