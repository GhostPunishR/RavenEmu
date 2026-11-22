package com.ravenemu.app.settings;

import static org.junit.Assert.*;

import android.content.Context;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36)
public final class ShaderSettingsTest {
    private final Context context = RuntimeEnvironment.getApplication();

    @Before public void clean() {
        context.getSharedPreferences(PlaybackSettings.PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit();
    }

    @Test public void noPresetByDefaultAndNothingWritten() {
        ShaderSettings settings = ShaderSettings.load(context);
        assertEquals("", settings.preset);
        assertTrue(settings.parameters.isEmpty());
        assertTrue(context.getSharedPreferences(PlaybackSettings.PREFERENCES, Context.MODE_PRIVATE).getAll().isEmpty());
    }

    @Test public void eachPresetKeepsTheValuesThePlayerSet() {
        ShaderSettings crt = ShaderSettings.choose(context, "pack/crt/crt-geom.glslp")
                .withParameter(context, "CRTgamma", 2.2f).withParameter(context, "CURVATURE", 0.5f);
        ShaderSettings.choose(context, "pack/handheld/lcd3x.glslp").withParameter(context, "CURVATURE", 0.1f);
        assertEquals(0.1f, ShaderSettings.load(context).parameters.get("CURVATURE"), 0f);
        ShaderSettings back = ShaderSettings.choose(context, crt.preset);
        assertEquals(2.2f, back.parameters.get("CRTgamma"), 0f);
        assertEquals(0.5f, back.parameters.get("CURVATURE"), 0f);
        assertEquals(back.preset, ShaderSettings.load(context).preset);
        ShaderSettings reset = back.withoutParameters(context);
        assertTrue(reset.parameters.isEmpty());
        assertTrue(ShaderSettings.load(context).parameters.isEmpty());
        // The other preset's values stay.
        assertEquals(0.1f, ShaderSettings.choose(context, "pack/handheld/lcd3x.glslp").parameters.get("CURVATURE"), 0f);
        assertEquals("", ShaderSettings.choose(context, "").preset);
        assertTrue("No preset, no value", ShaderSettings.load(context).withParameter(context, "x", 1f)
                .parameters.isEmpty());
    }
}
