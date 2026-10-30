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
public final class PlaybackSettingsTest {
    private Context context;
    private SharedPreferences preferences;

    @Before public void prepare() {
        context = RuntimeEnvironment.getApplication();
        preferences = context.getSharedPreferences(PlaybackSettings.PREFERENCES, Context.MODE_PRIVATE);
        preferences.edit().clear().commit();
    }

    @Test public void defaultsKeepTheOriginalPictureAndSoundAndReadingNeverWrites() {
        PlaybackSettings value = PlaybackSettings.load(context);
        assertTrue(value.audioEnabled);
        assertEquals(100, value.audioVolume);
        assertFalse(value.integerScale);
        assertEquals(0, value.brightness);
        assertEquals(100, value.contrast);
        assertEquals(PlaybackSettings.PALETTE_ORIGINAL, value.gbPalette);
        assertFalse(value.lcdCorrectionGbc);
        assertFalse(value.lcdCorrectionGba);
        assertEquals(PlaybackSettings.BLENDING_OFF, value.frameBlending);
        assertTrue(preferences.getAll().isEmpty());
    }

    @Test public void imageOptionsPersistAndChangingOneKeepsTheOthers() {
        new PlaybackSettings(false, 40, true, 5, 110, PlaybackSettings.PALETTE_POCKET, true, false,
                PlaybackSettings.BLENDING_GHOSTING).save(context);
        PlaybackSettings saved = PlaybackSettings.load(context);
        assertEquals(PlaybackSettings.PALETTE_POCKET, saved.gbPalette);
        assertTrue(saved.lcdCorrectionGbc);
        assertFalse(saved.lcdCorrectionGba);
        assertEquals(PlaybackSettings.BLENDING_GHOSTING, saved.frameBlending);
        PlaybackSettings sound = saved.withAudio(true, 90);
        assertEquals(PlaybackSettings.PALETTE_POCKET, sound.gbPalette);
        assertEquals(PlaybackSettings.BLENDING_GHOSTING, sound.frameBlending);
        PlaybackSettings picture = saved.withPicture(false, 0, 100);
        assertTrue(picture.lcdCorrectionGbc);
        assertEquals(40, picture.audioVolume);
        PlaybackSettings image = saved.withImage(PlaybackSettings.PALETTE_GRAY, false, true,
                PlaybackSettings.BLENDING_MIX);
        assertEquals(110, image.contrast);
        assertTrue(image.integerScale);
        assertTrue(image.lcdCorrectionGba);
    }

    @Test public void unknownOrMalformedImageOptionsFallBackToTheirDefaults() {
        preferences.edit().putString("video.gb_palette", "sepia").putString("video.frame_blending", "blur")
                .putString("video.lcd_gbc", "true").putBoolean("video.lcd_gba", true).commit();
        PlaybackSettings value = PlaybackSettings.load(context);
        assertEquals(PlaybackSettings.PALETTE_ORIGINAL, value.gbPalette);
        assertEquals(PlaybackSettings.BLENDING_OFF, value.frameBlending);
        assertFalse(value.lcdCorrectionGbc);
        assertTrue(value.lcdCorrectionGba);
        preferences.edit().putInt("video.gb_palette", 2).commit();
        assertEquals(PlaybackSettings.PALETTE_ORIGINAL, PlaybackSettings.load(context).gbPalette);
        assertEquals(PlaybackSettings.BLENDING_OFF,
                new PlaybackSettings(true, 100, false, 0, 100, null, false, false, null).frameBlending);
    }

    @Test public void normalizedSettingsPersistAsOneIndependentSnapshot() {
        new PlaybackSettings(false, 300, true, -99, 999).save(context);
        PlaybackSettings saved = PlaybackSettings.load(context);
        assertFalse(saved.audioEnabled);
        assertEquals(100, saved.audioVolume);
        assertTrue(saved.integerScale);
        assertEquals(-50, saved.brightness);
        assertEquals(150, saved.contrast);
        PlaybackSettings lower = new PlaybackSettings(true, -4, false, 90, -3);
        assertEquals(0, lower.audioVolume);
        assertEquals(50, lower.brightness);
        assertEquals(50, lower.contrast);
    }

    @Test public void malformedTypesFallBackPerFieldWithoutLosingValidPreferences() {
        preferences.edit().putString("audio.enabled", "false").putInt("audio.volume", 37)
                .putBoolean("video.integer_scale", true).putLong("video.brightness", 20)
                .putString("video.contrast", "130").commit();
        PlaybackSettings value = PlaybackSettings.load(context);
        assertTrue(value.audioEnabled);
        assertEquals(37, value.audioVolume);
        assertTrue(value.integerScale);
        assertEquals(0, value.brightness);
        assertEquals(100, value.contrast);
        assertEquals("false", preferences.getString("audio.enabled", ""));
        assertEquals(20L, preferences.getLong("video.brightness", 0));
    }

    @Test public void outOfRangeStoredIntegersAreBoundedAndUnknownKeysArePreserved() {
        preferences.edit().putInt("audio.volume", -1).putInt("video.brightness", 999)
                .putInt("video.contrast", 0).putString("future.setting", "keep").commit();
        PlaybackSettings value = PlaybackSettings.load(context);
        assertEquals(0, value.audioVolume);
        assertEquals(50, value.brightness);
        assertEquals(50, value.contrast);
        value.save(context);
        assertEquals("keep", preferences.getString("future.setting", ""));
    }
}
