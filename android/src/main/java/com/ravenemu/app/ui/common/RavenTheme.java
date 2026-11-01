package com.ravenemu.app.ui.common;

import com.ravenemu.app.R;
import com.ravenemu.app.settings.PlaybackSettings;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;

/** The app's colours, chosen in Settings → Interface → Thème: every screen,
 * icon, sheet and touch control takes them, so no colour of the interface is
 * written anywhere else. RavenEmu's is the beta's palette (RavenColors.kt);
 * NVIDIA's takes the values of RetroArch's MaterialUI "NVIDIA Shield" theme
 * (menu/drivers/materialui.c), only the colours, mapped on RavenEmu's roles;
 * the few roles MaterialUI has no colour for are derived from its own.
 * Unlocks keep their gold, errors their red and the console pictogram of the
 * Game Boy Color its coloured screen, whatever the theme. Immutable.
 *
 * An Activity takes its theme once, when it is created, and keeps it until it
 * is created again: everything it draws, its dialogs and sheets included,
 * reads the same palette through {@link #of(Context)}. */
public final class RavenTheme {
    /** Implemented by the Activities: the theme they were created with. */
    public interface Host { RavenTheme theme(); }

    static final String KEY = "interface.theme";

    public static final RavenTheme RAVENEMU = new Builder("ravenemu", "RavenEmu",
            R.style.RavenEmuNativeTheme, R.style.RavenGameSheetTheme)
            .background(0xff0b0c12).surface(0xff191b28).card(0xff202230).raised(0xff292a38)
            .frame(0xff10111b).cover(0xff233044).column(0xff151722)
            .accent(0xffa78bfa, 0xffbca8ff, 0xff8d6ff0, 0xff3c3454)
            .text(0xfff5f2ff, 0xffcbc4dc, 0xffaaa2bc).outline(0xff3c4052)
            // The beta's ChoiceRow: the chosen row on the surface.
            .selected(0xff191b28)
            .switches(0xffa78bfa, 0x66a78bfa, 0xffcbc4dc, 0xff3c4052)
            .controls(0xff2b2d40, 0xff474a63, 0xff2d2f42)
            .build();

    /** RetroArch's MaterialUI "NVIDIA Shield": list 121212, surface 1D1D1D,
     * bars 242424, icons 7AB547, highlight 255D00, text DEDEDE, hints 999999,
     * switches 85BB5C on 498515 and 33691E on 003D00. The raised buttons, the
     * list column and the faces of the touch controls are greys between them. */
    public static final RavenTheme NVIDIA = new Builder("nvidia", "NVIDIA",
            R.style.RavenEmuNativeTheme_Nvidia, R.style.RavenGameSheetTheme_Nvidia)
            .background(0xff121212).surface(0xff1d1d1d).card(0xff242424).raised(0xff2c2c2c)
            .frame(0xff000000).cover(0xff242424).column(0xff181818)
            .accent(0xff7ab547, 0xff85bb5c, 0xff498515, 0xff33691e)
            .text(0xffdedede, 0xffc4c4c4, 0xff999999).outline(0xff3b3b3b)
            .selected(0xff255d00)
            .switches(0xff85bb5c, 0xff498515, 0xff33691e, 0xff003d00)
            .controls(0xff2c2c2c, 0xff4a4a4a, 0xff303030)
            .build();

    private static final RavenTheme[] ALL = {RAVENEMU, NVIDIA};

    /** Stored in the preferences; the name shown in the settings. */
    public final String id, name;
    /** The Activity's and the bottom sheets' Android styles, for system widgets. */
    public final int activityStyle, sheetStyle;
    /** Screens, bars and cards, darkest first; frame holds a state's picture,
     * cover a game without artwork, column the list's artwork. */
    public final int background, surface, card, raised, frame, cover, column;
    /** Icons, labels and outlines; light for a pressed accent, deep below it,
     * dim for a pressed plain button. */
    public final int accent, accentLight, accentDeep, accentDim;
    public final int text, secondary, muted, outline;
    /** The chosen row of a list of choices. */
    public final int selected;
    public final int switchOn, switchOnTrack, switchOff, switchOffTrack;
    /** The touch controls' faces: the top of the plain gradient (its bottom is
     * the surface), and the high-contrast one. */
    public final int faceTop, contrastTop, contrastBottom;

    private RavenTheme(Builder b) {
        id = b.id; name = b.name; activityStyle = b.activityStyle; sheetStyle = b.sheetStyle;
        background = b.background; surface = b.surface; card = b.card; raised = b.raised;
        frame = b.frame; cover = b.cover; column = b.column;
        accent = b.accent; accentLight = b.accentLight; accentDeep = b.accentDeep; accentDim = b.accentDim;
        text = b.text; secondary = b.secondary; muted = b.muted; outline = b.outline;
        selected = b.selected;
        switchOn = b.switchOn; switchOnTrack = b.switchOnTrack; switchOff = b.switchOff;
        switchOffTrack = b.switchOffTrack;
        faceTop = b.faceTop; contrastTop = b.contrastTop; contrastBottom = b.contrastBottom;
    }

    /** The themes, in the settings' order. */
    public static RavenTheme[] all() { return ALL.clone(); }

    /** RavenEmu's for an unknown or missing id. */
    public static RavenTheme forId(String id) {
        for (RavenTheme theme : ALL) if (theme.id.equals(id)) return theme;
        return RAVENEMU;
    }

    /** The same colour with another opacity, 0 to 255. */
    public static int alpha(int color, int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (color & 0x00ffffff);
    }

    /** The theme of the Activity this context belongs to, otherwise the stored one. */
    public static RavenTheme of(Context context) {
        for (Context current = context; current != null; ) {
            if (current instanceof Host) {
                RavenTheme theme = ((Host) current).theme();
                if (theme != null) return theme;
            }
            if (!(current instanceof ContextWrapper)) break;
            Context base = ((ContextWrapper) current).getBaseContext();
            if (base == current) break;
            current = base;
        }
        return stored(context);
    }

    /** The theme chosen in the settings, for the next screen created. */
    public static RavenTheme stored(Context context) {
        Object value = context.getSharedPreferences(PlaybackSettings.PREFERENCES, Context.MODE_PRIVATE)
                .getAll().get(KEY);
        return forId(value instanceof String ? (String) value : null);
    }

    public static void store(Context context, RavenTheme theme) {
        context.getSharedPreferences(PlaybackSettings.PREFERENCES, Context.MODE_PRIVATE).edit()
                .putString(KEY, theme.id).apply();
    }

    /** Before the Activity's super.onCreate: the stored theme, and its Android style. */
    public static RavenTheme apply(Activity activity) {
        RavenTheme theme = stored(activity);
        activity.setTheme(theme.activityStyle);
        return theme;
    }

    private static final class Builder {
        final String id, name;
        final int activityStyle, sheetStyle;
        int background, surface, card, raised, frame, cover, column;
        int accent, accentLight, accentDeep, accentDim, text, secondary, muted, outline, selected;
        int switchOn, switchOnTrack, switchOff, switchOffTrack, faceTop, contrastTop, contrastBottom;

        Builder(String id, String name, int activityStyle, int sheetStyle) {
            this.id = id; this.name = name; this.activityStyle = activityStyle; this.sheetStyle = sheetStyle;
        }
        Builder background(int value) { background = value; return this; }
        Builder surface(int value) { surface = value; return this; }
        Builder card(int value) { card = value; return this; }
        Builder raised(int value) { raised = value; return this; }
        Builder frame(int value) { frame = value; return this; }
        Builder cover(int value) { cover = value; return this; }
        Builder column(int value) { column = value; return this; }
        Builder accent(int value, int light, int deep, int dim) {
            accent = value; accentLight = light; accentDeep = deep; accentDim = dim; return this;
        }
        Builder text(int value, int second, int dim) { text = value; secondary = second; muted = dim; return this; }
        Builder outline(int value) { outline = value; return this; }
        Builder selected(int value) { selected = value; return this; }
        Builder switches(int on, int onTrack, int off, int offTrack) {
            switchOn = on; switchOnTrack = onTrack; switchOff = off; switchOffTrack = offTrack; return this;
        }
        Builder controls(int face, int contrast, int contrastLow) {
            faceTop = face; contrastTop = contrast; contrastBottom = contrastLow; return this;
        }
        RavenTheme build() { return new RavenTheme(this); }
    }
}
