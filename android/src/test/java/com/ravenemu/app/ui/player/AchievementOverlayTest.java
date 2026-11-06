package com.ravenemu.app.ui.player;

import com.ravenemu.app.bridge.NativeAchievements;
import com.ravenemu.app.settings.AchievementSettings;

import com.ravenemu.app.platform.AchievementsHttp;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
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
 * only with "Déblocages et bilan", progress under it; a banner opens once its
 * image is known (2 s at most) and never changes while shown. No network:
 * the icon tests give their image through a held fake. */
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

    @Test public void challengesAndTrackersStayUntilTheirOwnHide() {
        NativeAchievements.Event challenge = new NativeAchievements.Event("challenge_show", "Sans dégât", "", "", "", "7");
        NativeAchievements.Event tracker = new NativeAchievements.Event("tracker_show", "01:23.45", "", "", "", "4401");
        overlay.onEvent(challenge);
        overlay.onEvent(tracker);
        assertEquals(java.util.Arrays.asList("Sans dégât", "01:23.45"), overlay.indicatorLines());
        overlay.onEvent(new NativeAchievements.Event("tracker_show", "01:24.00", "", "", "", "4401"));
        assertEquals("Updated in place", java.util.Arrays.asList("Sans dégât", "01:24.00"), overlay.indicatorLines());
        assertNull("No banner for them", overlay.bannerText());
        LinearLayout lines = ReflectionHelpers.getField(overlay, "indicators");
        assertEquals(View.VISIBLE, lines.getVisibility());
        // At the bottom, the popups stay above them.
        overlay.setSettings(AchievementSettings.DEFAULTS.withPosition(AchievementSettings.BOTTOM).withSound(false),
                true, 100);
        LinearLayout column = ReflectionHelpers.getField(overlay, "column");
        assertTrue(((FrameLayout.LayoutParams)column.getLayoutParams()).bottomMargin > lines.getPaddingTop() * 5);
        for (int i = 0; i < 6; i++)
            overlay.onEvent(new NativeAchievements.Event("challenge_show", "Défi " + i, "", "", "", "c" + i));
        assertEquals("At most four lines on screen", 4, lines.getChildCount());
        overlay.onEvent(new NativeAchievements.Event("challenge_hide", "", "", "", "", "7"));
        assertFalse(overlay.indicatorLines().contains("Sans dégât"));
        overlay.onEvent(new NativeAchievements.Event("hardcore_reset", "", "", "", "", ""));
        assertTrue(overlay.indicatorLines().isEmpty());
        assertEquals(View.GONE, lines.getVisibility());
        overlay.onEvent(tracker);
        overlay.clear();
        assertTrue("The game closes: they go", overlay.indicatorLines().isEmpty());
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

    private static final String ICON = "https://media.retroachievements.org/Images/000001.png";

    /** The game's icon, given out when the test says so. */
    private static final class HeldHttp extends AchievementsHttp {
        final CountDownLatch go = new CountDownLatch(1);
        final Bitmap image;
        HeldHttp(Bitmap image) {
            super("RavenEmu/test");
            this.image = image;
        }
        @Override public Bitmap badge(String url) {
            try {
                go.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException stop) {
                Thread.currentThread().interrupt();
            }
            return image;
        }
    }

    private AchievementOverlay overlayWith(HeldHttp http) {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        BadgeCache badges = new BadgeCache();
        badges.setHttp(http);
        AchievementOverlay withBadges = new AchievementOverlay(activity, badges);
        activity.setContentView(withBadges);
        withBadges.setSettings(AchievementSettings.DEFAULTS.withSound(false), true, 100);
        return withBadges;
    }

    /** The fetch runs on its own thread: the UI thread runs what it posts. */
    private static void until(BooleanSupplier done) throws InterruptedException {
        for (int i = 0; i < 500 && !done.getAsBoolean(); i++) {
            Thread.sleep(10);
            shadowOf(Looper.getMainLooper()).idle();
        }
        assertTrue(done.getAsBoolean());
    }

    /** The picture an ImageView shows: setImageBitmap(null) leaves an empty drawable. */
    private static Bitmap shown(ImageView view) {
        return view.getDrawable() instanceof android.graphics.drawable.BitmapDrawable
                ? ((android.graphics.drawable.BitmapDrawable) view.getDrawable()).getBitmap() : null;
    }

    private static NativeAchievements.Event game(String image) {
        return new NativeAchievements.Event("game", "Synthetic game", "3", "0", image, "1");
    }

    @Test public void theGameBannerOpensWithItsIconNeverWithTheStarFirst() throws InterruptedException {
        HeldHttp http = new HeldHttp(Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888));
        AchievementOverlay withIcon = overlayWith(http);
        try {
            withIcon.onEvent(game(ICON));
            View banner = ReflectionHelpers.getField(withIcon, "banner");
            assertNull("Not before its icon", withIcon.bannerText());
            assertEquals(View.GONE, banner.getVisibility());
            http.go.countDown();
            until(() -> withIcon.bannerText() != null);
            ImageView star = ReflectionHelpers.getField(withIcon, "bannerStar");
            ImageView icon = ReflectionHelpers.getField(withIcon, "bannerImage");
            assertEquals(View.VISIBLE, banner.getVisibility());
            assertEquals("The icon, no star", View.GONE, star.getVisibility());
            assertNotNull(shown(icon));
        } finally {
            http.go.countDown();
        }
    }

    @Test public void aSlowIconLeavesTheStarForTheWholeBannerAsInTheBeta() throws InterruptedException {
        HeldHttp http = new HeldHttp(Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888));
        AchievementOverlay slow = overlayWith(http);
        try {
            slow.onEvent(game(ICON));
            slow.onEvent(event("online", "", "", ""));
            advance(AchievementOverlay.BADGE_WAIT_MILLIS - 100);
            assertNull("Still waiting for the icon", slow.bannerText());
            advance(200);
            assertEquals("3 succès disponibles · Synthetic game", String.valueOf(slow.bannerText()));
            ImageView star = ReflectionHelpers.getField(slow, "bannerStar");
            ImageView icon = ReflectionHelpers.getField(slow, "bannerImage");
            assertEquals(View.VISIBLE, star.getVisibility());
            http.go.countDown();
            BadgeCache badges = ReflectionHelpers.getField(slow, "badges");
            until(() -> badges.cached(ICON) != null);
            assertEquals("Never changes while shown", View.VISIBLE, star.getVisibility());
            assertNull(shown(icon));
            advance(MOTION + 5_000 + MOTION + 100);
            assertEquals("Then the next one, without waiting", "RetroAchievements · De nouveau en ligne",
                    String.valueOf(slow.bannerText()));
        } finally {
            http.go.countDown();
        }
    }

    @Test public void noIconToFetchOpensAtOnce() throws InterruptedException {
        HeldHttp failing = new HeldHttp(null);
        failing.go.countDown();
        AchievementOverlay withoutIcon = overlayWith(failing);
        withoutIcon.onEvent(game("http://example.org/not-allowed.png"));
        assertEquals("Not an allowed address: the star at once", "3 succès disponibles · Synthetic game",
                String.valueOf(withoutIcon.bannerText()));
        withoutIcon.clear();
        withoutIcon.onEvent(game(ICON));
        until(() -> withoutIcon.bannerText() != null);
        ImageView star = ReflectionHelpers.getField(withoutIcon, "bannerStar");
        assertEquals("A failed fetch: the star, without the full wait", View.VISIBLE, star.getVisibility());
    }

    @Test public void closingTheGameWhileTheIconComesShowsNothing() throws InterruptedException {
        HeldHttp http = new HeldHttp(Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888));
        AchievementOverlay closing = overlayWith(http);
        try {
            closing.onEvent(game(ICON));
            closing.clear();
            http.go.countDown();
            BadgeCache badges = ReflectionHelpers.getField(closing, "badges");
            until(() -> badges.cached(ICON) != null);
            advance(AchievementOverlay.BADGE_WAIT_MILLIS + 100);
            assertNull(closing.bannerText());
            closing.onEvent(game(ICON));
            assertEquals("The icon known: at once", "3 succès disponibles · Synthetic game",
                    String.valueOf(closing.bannerText()));
        } finally {
            http.go.countDown();
        }
    }
}
