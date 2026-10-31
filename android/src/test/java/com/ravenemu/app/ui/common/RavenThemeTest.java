package com.ravenemu.app.ui.common;

import com.ravenemu.app.settings.PlaybackSettings;

import android.content.Context;
import android.content.ContextWrapper;
import android.view.ContextThemeWrapper;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36)
public final class RavenThemeTest {
    private Context app;

    @Before public void clear() {
        app = RuntimeEnvironment.getApplication();
        app.getSharedPreferences(PlaybackSettings.PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit();
    }

    @Test public void ravenEmuKeepsTheBetasColours() {
        RavenTheme theme = RavenTheme.RAVENEMU;
        // shared/theme/RavenColors.kt
        assertEquals(0xff0b0c12, theme.background);
        assertEquals(0xff191b28, theme.surface);
        assertEquals(0xffa78bfa, theme.accent);
        assertEquals(0xfff5f2ff, theme.text);
        assertEquals(0xffcbc4dc, theme.secondary);
        assertEquals(0xffaaa2bc, theme.muted);
        assertEquals(0xff3c4052, theme.outline);
        assertEquals("The beta's ChoiceRow", theme.surface, theme.selected);
    }

    @Test public void nvidiaTakesRetroArchsNvidiaShieldColours() {
        RavenTheme theme = RavenTheme.NVIDIA;
        // menu/drivers/materialui.c, materialui_theme_nvidia_shield
        assertEquals(0xff121212, theme.background);
        assertEquals(0xff1d1d1d, theme.surface);
        assertEquals(0xff242424, theme.card);
        assertEquals(0xff7ab547, theme.accent);
        assertEquals(0xffdedede, theme.text);
        assertEquals(0xffc4c4c4, theme.secondary);
        assertEquals(0xff999999, theme.muted);
        assertEquals(0xff255d00, theme.selected);
        assertEquals(0xff85bb5c, theme.switchOn);
        assertEquals(0xff498515, theme.switchOnTrack);
        assertEquals(0xff33691e, theme.switchOff);
        assertEquals(0xff003d00, theme.switchOffTrack);
    }

    @Test public void textStaysReadableInEveryTheme() {
        for (RavenTheme theme : RavenTheme.all()) {
            for (int ground : new int[]{theme.background, theme.surface, theme.card}) {
                assertReadable(theme.id + " text", theme.text, ground);
                assertReadable(theme.id + " muted", theme.muted, ground);
                assertReadable(theme.id + " accent", theme.accent, ground);
            }
            assertReadable(theme.id + " chosen row", theme.text, theme.selected);
            assertReadable(theme.id + " pressed label", theme.background, theme.accentLight);
            assertReadable(theme.id + " accent button", theme.background, theme.accent);
        }
    }

    @Test public void anUnknownOrMissingThemeIsRavenEmus() {
        assertSame(RavenTheme.RAVENEMU, RavenTheme.stored(app));
        assertSame(RavenTheme.RAVENEMU, RavenTheme.forId(null));
        assertSame(RavenTheme.RAVENEMU, RavenTheme.forId("violet"));
        assertSame(RavenTheme.NVIDIA, RavenTheme.forId("nvidia"));
        RavenTheme.store(app, RavenTheme.NVIDIA);
        assertSame(RavenTheme.NVIDIA, RavenTheme.stored(app));
        preferences().edit().putString(RavenTheme.KEY, "inconnu").commit();
        assertSame(RavenTheme.RAVENEMU, RavenTheme.stored(app));
        preferences().edit().putInt(RavenTheme.KEY, 1).commit();
        assertSame("A value of another type", RavenTheme.RAVENEMU, RavenTheme.stored(app));
    }

    @Test public void aViewTakesItsActivitysThemeNotTheOneStoredSince() {
        RavenTheme.store(app, RavenTheme.RAVENEMU);
        Context host = new Host(app, RavenTheme.NVIDIA);
        // A dialog's or a sheet's context wraps its Activity's.
        Context dialog = new ContextThemeWrapper(new ContextWrapper(host), 0);
        assertSame(RavenTheme.NVIDIA, RavenTheme.of(dialog));
        assertSame("No Activity: the stored theme", RavenTheme.RAVENEMU, RavenTheme.of(app));
        assertSame("An Activity not created yet", RavenTheme.RAVENEMU, RavenTheme.of(new Host(app, null)));
    }

    @Test public void eachThemeHasItsOwnIdAndAndroidStyles() {
        RavenTheme[] all = RavenTheme.all();
        assertSame("RavenEmu first, the default", RavenTheme.RAVENEMU, all[0]);
        assertNotEquals(all[0].id, all[1].id);
        assertNotEquals(all[0].activityStyle, all[1].activityStyle);
        assertNotEquals(all[0].sheetStyle, all[1].sheetStyle);
        all[0] = null;
        assertSame("A copy", RavenTheme.RAVENEMU, RavenTheme.all()[0]);
    }

    @Test public void alphaChangesOnlyTheOpacity() {
        assertEquals(0x29a78bfa, RavenTheme.alpha(0xffa78bfa, 0x29));
        assertEquals(0x007ab547, RavenTheme.alpha(0xff7ab547, -4));
        assertEquals(0xff7ab547, RavenTheme.alpha(0x107ab547, 300));
    }

    private android.content.SharedPreferences preferences() {
        return app.getSharedPreferences(PlaybackSettings.PREFERENCES, Context.MODE_PRIVATE);
    }

    /** WCAG's 4.5:1 for normal text. */
    private static void assertReadable(String what, int foreground, int background) {
        double light = luminance(foreground), dark = luminance(background);
        if (light < dark) { double swap = light; light = dark; dark = swap; }
        double ratio = (light + 0.05) / (dark + 0.05);
        assertTrue(what + String.format(" : %.2f", ratio), ratio >= 4.5);
    }

    private static double luminance(int color) {
        return 0.2126 * channel(color >> 16) + 0.7152 * channel(color >> 8) + 0.0722 * channel(color);
    }

    private static double channel(int value) {
        double c = (value & 0xff) / 255.0;
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    private static final class Host extends ContextWrapper implements RavenTheme.Host {
        private final RavenTheme theme;
        Host(Context base, RavenTheme theme) { super(base); this.theme = theme; }
        @Override public RavenTheme theme() { return theme; }
    }
}
