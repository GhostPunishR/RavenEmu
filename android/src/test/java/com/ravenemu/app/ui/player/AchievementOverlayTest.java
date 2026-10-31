package com.ravenemu.app.ui.player;

import com.ravenemu.app.bridge.NativeAchievements;
import com.ravenemu.app.settings.AchievementSettings;

import android.app.Activity;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.time.Duration;
import java.util.ArrayDeque;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** The beta's banner rules: one at a time, each for its duration, awards
 * only with "Déblocages et bilan", progress under it. No network: the events
 * carry no image. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36, qualifiers = "fr-rFR-w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class AchievementOverlayTest {
    private static final int MOTION = AchievementOverlay.DROP_MILLIS + AchievementOverlay.UNFOLD_MILLIS;
    private AchievementOverlay overlay;

    @Before public void prepare() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        overlay = new AchievementOverlay(activity, new BadgeCache());
        activity.setContentView(overlay);
        overlay.setSettings(AchievementSettings.DEFAULTS.withSound(false), true, 100);
    }

    private static NativeAchievements.Event event(String kind, String title, String detail, String extra) {
        return new NativeAchievements.Event(kind, title, detail, extra, "", "1");
    }

    private static void advance(long millis) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis)); }

    @Test public void oneBannerAtATimeEachForItsDuration() {
        overlay.onEvent(event("achievement", "Premier", "", "5"));
        overlay.onEvent(event("achievement", "Second", "", "10"));
        assertEquals("Succès débloqué · 5 points · Premier", String.valueOf(overlay.bannerText()));
        advance(MOTION + 5_000 - 100);
        assertEquals("Shown for Settings' five seconds", "Succès débloqué · 5 points · Premier",
                String.valueOf(overlay.bannerText()));
        advance(MOTION + 200);
        assertEquals("Succès débloqué · 10 points · Second", String.valueOf(overlay.bannerText()));
        advance(2 * MOTION + 5_100);
        assertNull("Nothing left", overlay.bannerText());
        View banner = ReflectionHelpers.getField(overlay, "banner");
        assertEquals(View.GONE, banner.getVisibility());
    }

    @Test public void unlocksCanBeSilencedButTheGameStillIntroducesItself() {
        overlay.setSettings(AchievementSettings.DEFAULTS.withUnlockPopups(false).withSound(false), true, 100);
        overlay.onEvent(event("achievement", "Premier", "", "5"));
        overlay.onEvent(event("completed", "Synthetic game", "1", "1"));
        assertNull(overlay.bannerText());
        overlay.onEvent(event("game", "Synthetic game", "3", "0"));
        assertEquals("3 succès disponibles · Synthetic game", String.valueOf(overlay.bannerText()));
    }

    @Test public void turningUnlocksOffDropsTheWaitingOnesAndTheOneShown() {
        overlay.onEvent(event("achievement", "Premier", "", "5"));
        overlay.onEvent(event("game", "Synthetic game", "3", "0"));
        overlay.onEvent(event("achievement", "Second", "", "5"));
        overlay.setSettings(AchievementSettings.DEFAULTS.withUnlockPopups(false), true, 100);
        assertNull(overlay.bannerText());
        ArrayDeque<?> queue = ReflectionHelpers.getField(overlay, "queue");
        assertEquals("Only the game's banner waits", 1, queue.size());
    }

    @Test public void theChimeNeverStopsTheBanner() {
        overlay.setSettings(AchievementSettings.DEFAULTS, true, 80);
        overlay.onEvent(event("achievement", "Premier", "", "5"));
        assertEquals("Succès débloqué · 5 points · Premier", String.valueOf(overlay.bannerText()));
        overlay.release();
        assertNull(overlay.bannerText());
        overlay.onEvent(event("achievement", "Après", "", "5"));
        assertNull("Released: nothing more", overlay.bannerText());
    }

    @Test public void theQueueIsBounded() {
        for (int i = 0; i < 20; i++) overlay.onEvent(event("achievement", "Succès " + i, "", "1"));
        ArrayDeque<?> queue = ReflectionHelpers.getField(overlay, "queue");
        assertEquals(8, queue.size());
        overlay.clear();
        assertEquals(0, queue.size());
        assertNull(overlay.bannerText());
    }

    @Test public void progressShowsForItsOwnDurationUnlessTurnedOff() {
        overlay.onEvent(event("progress_show", "Collectionneur", "3/10", ""));
        LinearLayout progress = ReflectionHelpers.getField(overlay, "progress");
        TextView title = ReflectionHelpers.getField(overlay, "progressTitle");
        TextView detail = ReflectionHelpers.getField(overlay, "progressDetail");
        assertEquals(View.VISIBLE, progress.getVisibility());
        assertEquals("Progression · Collectionneur", title.getText().toString());
        assertEquals("3/10", detail.getText().toString());
        advance(2_000 + 400);
        assertEquals("Gone after Settings' two seconds", View.GONE, progress.getVisibility());
        overlay.onEvent(event("progress_show", "Collectionneur", "4/10", ""));
        overlay.onEvent(event("progress_hide", "", "", ""));
        advance(400);
        assertEquals(View.GONE, progress.getVisibility());
        overlay.setSettings(AchievementSettings.DEFAULTS.withProgressPopups(false).withSound(false), true, 100);
        overlay.onEvent(event("progress_show", "Collectionneur", "5/10", ""));
        assertEquals(View.GONE, progress.getVisibility());
    }

    @Test public void bottomPopupsComeFromTheBottomWithTheBannerFirstAsInTheBeta() {
        overlay.setSettings(AchievementSettings.DEFAULTS.withPosition(AchievementSettings.BOTTOM).withSound(false),
                true, 100);
        LinearLayout column = ReflectionHelpers.getField(overlay, "column");
        View banner = ReflectionHelpers.getField(overlay, "banner");
        assertEquals(Gravity.BOTTOM, ((FrameLayout.LayoutParams)column.getLayoutParams()).gravity & Gravity.VERTICAL_GRAVITY_MASK);
        assertSame(banner, column.getChildAt(0));
        assertSame(ReflectionHelpers.getField(overlay, "progress"), column.getChildAt(1));
        overlay.onEvent(event("achievement", "Premier", "", "5"));
        assertTrue("Starts below the picture", banner.getTranslationY() > 0);
        overlay.setSettings(AchievementSettings.DEFAULTS.withSound(false), true, 100);
        assertEquals(Gravity.TOP, ((FrameLayout.LayoutParams)column.getLayoutParams()).gravity & Gravity.VERTICAL_GRAVITY_MASK);
        assertSame(banner, column.getChildAt(0));
    }
}
