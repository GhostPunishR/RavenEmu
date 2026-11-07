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
public final class AchievementSettingsTest {
    private Context context;
    private SharedPreferences preferences;

    @Before public void prepare() {
        context = RuntimeEnvironment.getApplication();
        preferences = context.getSharedPreferences(PlaybackSettings.PREFERENCES, Context.MODE_PRIVATE);
        preferences.edit().clear().commit();
    }

    @Test public void theBetasDefaultsAndReadingNeverWrites() {
        AchievementSettings settings = AchievementSettings.load(context);
        assertTrue(settings.unlockPopups);
        assertTrue(settings.progressPopups);
        assertTrue(settings.sound);
        assertEquals(AchievementSettings.TOP, settings.position);
        assertEquals(5, settings.popupSeconds);
        assertEquals(2, settings.progressSeconds);
        assertTrue(preferences.getAll().isEmpty());
    }

    @Test public void eachChoiceIsKeptAlongsideTheOthers() {
        new LatencySettings(2).save(context);
        AchievementSettings.DEFAULTS.withUnlockPopups(false).withProgressPopups(false).withSound(false)
                .withPosition(AchievementSettings.BOTTOM).withPopupSeconds(9).withProgressSeconds(4).save(context);
        AchievementSettings loaded = AchievementSettings.load(context);
        assertFalse(loaded.unlockPopups);
        assertFalse(loaded.progressPopups);
        assertFalse(loaded.sound);
        assertEquals(AchievementSettings.BOTTOM, loaded.position);
        assertEquals(9, loaded.popupSeconds);
        assertEquals(4, loaded.progressSeconds);
        assertEquals("Latency must stay", 2, LatencySettings.load(context).preemptiveFrames);
    }

    @Test public void retroArchsSixAnchorsAreKeptAndTheTwoStoredBeforeStillRead() {
        assertEquals(java.util.Arrays.asList("top_left", "top", "top_right", "bottom_left", "bottom", "bottom_right"),
                AchievementSettings.POSITIONS);
        int[] sides = {-1, 0, 1, -1, 0, 1};
        for (int i = 0; i < AchievementSettings.POSITIONS.size(); i++) {
            String position = AchievementSettings.POSITIONS.get(i);
            AchievementSettings.DEFAULTS.withPosition(position).save(context);
            AchievementSettings loaded = AchievementSettings.load(context);
            assertEquals(position, loaded.position);
            assertEquals(position, i >= 3, loaded.fromBottom());
            assertEquals(position, sides[i], loaded.horizontal());
        }
        // RavenEmu stored only "top" and "bottom": they are the centred anchors.
        preferences.edit().putString("achievements.position", "bottom").commit();
        AchievementSettings bottom = AchievementSettings.load(context);
        assertTrue(bottom.fromBottom());
        assertEquals(0, bottom.horizontal());
        assertFalse(AchievementSettings.DEFAULTS.fromBottom());
        assertEquals("The default stays centred at the top", 0, AchievementSettings.DEFAULTS.horizontal());
    }

    @Test public void outOfRangeOrMalformedValuesFallBack() {
        AchievementSettings clamped = new AchievementSettings(true, true, true, "side", 60, 0);
        assertEquals(AchievementSettings.TOP, clamped.position);
        assertEquals(AchievementSettings.POPUP_MAX, clamped.popupSeconds);
        assertEquals(AchievementSettings.PROGRESS_MIN, clamped.progressSeconds);
        preferences.edit().putString("achievements.sound", "false").putInt("achievements.position", 1)
                .putInt("achievements.popup_seconds", 1).putString("achievements.progress_seconds", "3").commit();
        AchievementSettings loaded = AchievementSettings.load(context);
        assertTrue("A malformed choice keeps the default", loaded.sound);
        assertEquals(AchievementSettings.TOP, loaded.position);
        assertEquals(AchievementSettings.POPUP_MIN, loaded.popupSeconds);
        assertEquals(2, loaded.progressSeconds);
    }
}
