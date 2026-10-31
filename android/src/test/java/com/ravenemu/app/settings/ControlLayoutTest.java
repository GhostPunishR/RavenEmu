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
public final class ControlLayoutTest {
    private Context context;
    private SharedPreferences preferences;

    @Before public void prepare() {
        context = RuntimeEnvironment.getApplication();
        preferences = context.getSharedPreferences(PlaybackSettings.PREFERENCES, Context.MODE_PRIVATE);
        preferences.edit().clear().commit();
    }

    @Test public void everyControlStartsAtItsOwnPlace() {
        assertEquals(ControlLayout.DEFAULT, ControlLayout.load(context, false));
        assertEquals(ControlLayout.DEFAULT, ControlLayout.load(context, true));
        assertTrue(ControlLayout.DEFAULT.isDefault());
        for (int i = 0; i < ControlLayout.CONTROLS.length; i++) {
            assertEquals(0f, ControlLayout.DEFAULT.dx(i), 0f);
            assertEquals(0f, ControlLayout.DEFAULT.dy(i), 0f);
        }
        assertTrue(preferences.getAll().isEmpty());
    }

    @Test public void portraitAndLandscapeKeepTheirOwnLayout() {
        ControlLayout portrait = ControlLayout.DEFAULT.with(1, 12.34f, -40f);
        ControlLayout landscape = ControlLayout.DEFAULT.with(0, -8f, 120.5f).with(6, 3f, 0f);
        portrait.save(context, false);
        landscape.save(context, true);
        ControlLayout readPortrait = ControlLayout.load(context, false);
        assertEquals("A tenth of a dp, as stored", portrait, readPortrait);
        assertEquals(12.3f, readPortrait.dx(1), 0f);
        assertEquals(-40f, readPortrait.dy(1), 0f);
        assertEquals(landscape, ControlLayout.load(context, true));
        assertFalse(readPortrait.isDefault());
        ControlLayout.reset(context);
        assertEquals(ControlLayout.DEFAULT, ControlLayout.load(context, false));
        assertEquals(ControlLayout.DEFAULT, ControlLayout.load(context, true));
    }

    @Test public void aLayoutSavedBeforeTheMenuButtonKeepsItsPlaces() {
        assertEquals("dpad, a, b, select, start, l, r, menu", String.join(", ", ControlLayout.CONTROLS));
        preferences.edit().putString("controls.layout.portrait", "1,2,3,4,5,6,7,8,9,10,11,12,13,14").commit();
        ControlLayout layout = ControlLayout.load(context, false);
        assertEquals(1f, layout.dx(0), 0f);
        assertEquals(14f, layout.dy(6), 0f);
        assertEquals("The menu at its own place", 0f, layout.dx(7), 0f);
        assertEquals(0f, layout.dy(7), 0f);
        layout.with(7, 5f, -6f).save(context, false);
        assertEquals(16, preferences.getString("controls.layout.portrait", "").split(",").length);
        assertEquals(-6f, ControlLayout.load(context, false).dy(7), 0f);
        assertEquals(14f, ControlLayout.load(context, false).dy(6), 0f);
    }

    @Test public void aDamagedLayoutIsTheDefaultOne() {
        String[] damaged = {"", "1,2,3", "a,b,c,d,e,f,g,h,i,j,k,l,m,n", "0,0,0,0,0,0,0,0,0,0,0,0,0,NaN",
                "0,0,0,0,0,0,0,0,0,0,0,0,0,1e9", "0,0,0,0,0,0,0,0,0,0,0,0,0,Infinity",
                "0,0,0,0,0,0,0,0,0,0,0,0,0,0,0"};
        for (String value : damaged) {
            preferences.edit().putString("controls.layout.portrait", value).commit();
            assertEquals(value, ControlLayout.DEFAULT, ControlLayout.load(context, false));
        }
        preferences.edit().putInt("controls.layout.portrait", 4).commit();
        assertEquals(ControlLayout.DEFAULT, ControlLayout.load(context, false));
        assertEquals(4000f, ControlLayout.DEFAULT.with(2, 1e9f, Float.NaN).dx(2), 0f);
        assertEquals(0f, ControlLayout.DEFAULT.with(2, 1e9f, Float.NaN).dy(2), 0f);
    }
}
