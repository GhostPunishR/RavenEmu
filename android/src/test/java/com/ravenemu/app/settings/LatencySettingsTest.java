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
public final class LatencySettingsTest {
    private Context context;
    private SharedPreferences preferences;

    @Before public void prepare() {
        context = RuntimeEnvironment.getApplication();
        preferences = context.getSharedPreferences(PlaybackSettings.PREFERENCES, Context.MODE_PRIVATE);
        preferences.edit().clear().commit();
    }

    @Test public void offByDefaultAndReadingNeverWrites() {
        assertEquals(0, LatencySettings.load(context).preemptiveFrames);
        assertTrue(preferences.getAll().isEmpty());
    }

    @Test public void theChoiceIsKeptAlongsideTheOthers() {
        new ControlSettings(true, 112, 30, ControlSettings.STYLE_CONTRAST).save(context);
        new LatencySettings(2).save(context);
        assertEquals(2, LatencySettings.load(context).preemptiveFrames);
        assertEquals("Control settings must stay", 112, ControlSettings.load(context).scale);
    }

    @Test public void outOfRangeOrMalformedValuesFallBack() {
        assertEquals(LatencySettings.PREEMPTIVE_MAX, new LatencySettings(9).preemptiveFrames);
        assertEquals(0, new LatencySettings(-1).preemptiveFrames);
        preferences.edit().putString("latency.preemptive_frames", "2").commit();
        assertEquals(0, LatencySettings.load(context).preemptiveFrames);
        preferences.edit().putInt("latency.preemptive_frames", 7).commit();
        assertEquals(LatencySettings.PREEMPTIVE_MAX, LatencySettings.load(context).preemptiveFrames);
    }
}
