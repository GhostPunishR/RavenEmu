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
public final class CartridgeSettingsTest {
    private Context context;
    private SharedPreferences preferences;

    @Before public void prepare() {
        context = RuntimeEnvironment.getApplication();
        preferences = context.getSharedPreferences(PlaybackSettings.PREFERENCES, Context.MODE_PRIVATE);
        preferences.edit().clear().commit();
    }

    @Test public void defaultsAreTheBetasAndReadingNeverWrites() {
        CartridgeSettings value = CartridgeSettings.load(context);
        assertTrue(value.rumble);
        assertTrue(value.sensors);
        assertTrue(value.solarAuto);
        assertEquals(50, value.solarLevel);
        assertTrue(value.solarFromSensor());
        assertTrue(preferences.getAll().isEmpty());
    }

    @Test public void settingsPersistAlongsideTheOthersAndChangingOneKeepsTheRest() {
        new ControlSettings(true, 112, 30, ControlSettings.STYLE_CONTRAST).save(context);
        new CartridgeSettings(false, true, false, 80).save(context);
        CartridgeSettings saved = CartridgeSettings.load(context);
        assertFalse(saved.rumble);
        assertTrue(saved.sensors);
        assertFalse(saved.solarAuto);
        assertEquals(80, saved.solarLevel);
        assertFalse("Without the automatic sun, the level applies", saved.solarFromSensor());
        assertEquals("Control settings must stay", 112, ControlSettings.load(context).scale);
        CartridgeSettings changed = saved.withSolarAuto(true).withSensors(false);
        assertFalse(changed.rumble);
        assertEquals(80, changed.solarLevel);
        assertFalse("Without the sensors, the level applies", changed.solarFromSensor());
    }

    @Test public void theSunMovesByTensWithinItsRange() {
        assertEquals(40, CartridgeSettings.DEFAULTS.withSolarLevel(44).solarLevel);
        assertEquals(50, CartridgeSettings.DEFAULTS.withSolarLevel(45).solarLevel);
        assertEquals(100, CartridgeSettings.DEFAULTS.withSolarLevel(400).solarLevel);
        assertEquals(0, CartridgeSettings.DEFAULTS.withSolarLevel(-3).solarLevel);
    }

    @Test public void malformedValuesFallBackOnTheirOwn() {
        preferences.edit().putString("cartridge.rumble", "false").putInt("cartridge.sensors", 0)
                .putBoolean("cartridge.solar_auto", false).putString("cartridge.solar_level", "70").commit();
        CartridgeSettings value = CartridgeSettings.load(context);
        assertTrue(value.rumble);
        assertTrue(value.sensors);
        assertFalse(value.solarAuto);
        assertEquals(50, value.solarLevel);
    }
}
