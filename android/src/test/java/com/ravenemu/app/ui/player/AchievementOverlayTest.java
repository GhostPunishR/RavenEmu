package com.ravenemu.app.ui.player;

import com.ravenemu.app.bridge.NativeAchievements;
import com.ravenemu.app.settings.AchievementSettings;

import com.ravenemu.app.platform.AchievementsHttp;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Looper;
import android.view.View;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
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

/** RetroArch's popups and corner: one popup at a time, each for Settings'
 * duration, its badge sliding in then its text unfolding, awards only with
 * "Déblocages et bilan"; a popup opens once its image is known (2 s at most)
 * and never changes while shown. In the bottom right corner, from the
 * bottom: trackers, challenges, then the progress, which keeps Settings'
 * duration and waits for a new badge rather than flashing the trophy. No
 * network: the icon tests give their image through a held fake. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36, qualifiers = "fr-rFR-w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class AchievementOverlayTest {
    private static final int MOTION = AchievementOverlay.SLIDE_MILLIS + AchievementOverlay.UNFOLD_MILLIS;
    private AchievementOverlay overlay;

    @Before public void prepare() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        overlay = new AchievementOverlay(activity, new BadgeCache());
        activity.setContentView(overlay);
        overlay.setSettings(AchievementSettings.DEFAULTS.withSound(false), true, 100);
        advance(10);
    }

    private static NativeAchievements.Event event(String kind, String title, String detail, String extra) {
        return new NativeAchievements.Event(kind, title, detail, extra, "", "1");
    }

    private static NativeAchievements.Event indicator(String kind, String title, String detail, String image,
                                                      String id) {
        return new NativeAchievements.Event(kind, title, detail, "", image, id);
    }

    private static void advance(long millis) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis)); }

    private static AchievementPopup popup(AchievementOverlay overlay) { return ReflectionHelpers.getField(overlay, "popup"); }
    private static AchievementCorner corner(AchievementOverlay overlay) { return ReflectionHelpers.getField(overlay, "corner"); }

    @Test public void onePopupAtATimeEachForItsDuration() {
        overlay.onEvent(event("achievement", "Premier", "", "5"));
        overlay.onEvent(event("achievement", "Second", "", "10"));
        assertEquals("Succès débloqué · Premier (5)", String.valueOf(overlay.popupText()));
        advance(MOTION + 5_000 - 100);
        assertEquals("Shown for Settings' five seconds", "Succès débloqué · Premier (5)",
                String.valueOf(overlay.popupText()));
        advance(MOTION + 200);
        assertEquals("Succès débloqué · Second (10)", String.valueOf(overlay.popupText()));
        advance(2 * MOTION + 5_100);
        assertNull("Nothing left", overlay.popupText());
        assertEquals(View.GONE, popup(overlay).getVisibility());
    }

    /** Robolectric runs an animator to its end at once: the stages are checked
     * by their outcome here, their geometry in AchievementPopupTest. */
    @Test public void eachStageTakesRetroArchsDurationAndLeavesItsPopupWhereItShould() {
        assertEquals("MSG_QUEUE_ANIMATION_DURATION", 330, AchievementOverlay.SLIDE_MILLIS);
        assertEquals(330, AchievementOverlay.UNFOLD_MILLIS);
        AchievementPopup popup = popup(overlay);
        overlay.onEvent(event("achievement", "Premier", "", "5"));
        advance(MOTION + 40);
        assertEquals("Slid in", 1f, popup.slide(), 0f);
        assertEquals("Then unfolded", 1f, popup.unfold(), 0f);
        assertEquals(popup.edgeMargin(), popup.badgeTop(), 0.5f);
        assertTrue("The trophy without a badge", popup.showsPlaceholder());
        assertEquals("Centred once open", overlay.getWidth() / 2f,
                popup.badgeLeft() + (popup.popupHeight() + popup.textWidth()) / 2f, 1f);
        advance(5_000 + MOTION + 40);
        assertNull(overlay.popupText());
        assertEquals(View.GONE, popup.getVisibility());
    }

    @Test public void unlocksCanBeSilencedButTheGameStillIntroducesItself() {
        overlay.setSettings(AchievementSettings.DEFAULTS.withUnlockPopups(false).withSound(false), true, 100);
        overlay.onEvent(event("achievement", "Premier", "", "5"));
        overlay.onEvent(event("completed", "Synthetic game", "1", "1"));
        assertNull(overlay.popupText());
        overlay.onEvent(event("game", "Synthetic game", "3", "1"));
        assertEquals("Synthetic game · Vous avez débloqué 1 succès sur 3", String.valueOf(overlay.popupText()));
    }

    @Test public void turningUnlocksOffDropsTheWaitingOnesAndTheOneShown() {
        overlay.onEvent(event("achievement", "Premier", "", "5"));
        overlay.onEvent(event("game", "Synthetic game", "3", "0"));
        overlay.onEvent(event("achievement", "Second", "", "5"));
        overlay.setSettings(AchievementSettings.DEFAULTS.withUnlockPopups(false), true, 100);
        assertNull(overlay.popupText());
        ArrayDeque<?> queue = ReflectionHelpers.getField(overlay, "queue");
        assertEquals("Only the game's popup waits", 1, queue.size());
    }

    @Test public void theChimeNeverStopsThePopup() {
        overlay.setSettings(AchievementSettings.DEFAULTS, true, 80);
        overlay.onEvent(event("achievement", "Premier", "", "5"));
        assertEquals("Succès débloqué · Premier (5)", String.valueOf(overlay.popupText()));
        overlay.release();
        assertNull(overlay.popupText());
        overlay.onEvent(event("achievement", "Après", "", "5"));
        assertNull("Released: nothing more", overlay.popupText());
    }

    @Test public void theQueueIsBoundedAsRetroArchs() {
        for (int i = 0; i < 20; i++) overlay.onEvent(event("achievement", "Succès " + i, "", "1"));
        ArrayDeque<?> queue = ReflectionHelpers.getField(overlay, "queue");
        assertEquals(8, queue.size());
        overlay.clear();
        assertEquals(0, queue.size());
        assertNull(overlay.popupText());
    }

    @Test public void bottomPopupsComeFromTheBottomAboveTheCorner() {
        overlay.setSettings(AchievementSettings.DEFAULTS.withPosition(AchievementSettings.BOTTOM).withSound(false),
                true, 100);
        overlay.onEvent(indicator("tracker_show", "01:23.45", "", "", "4401"));
        advance(10);
        AchievementCorner corner = corner(overlay);
        int occupied = corner.occupiedHeight();
        assertTrue("The tracker takes the bottom", occupied > 0);
        overlay.onEvent(event("achievement", "Premier", "", "5"));
        advance(10);
        AchievementPopup popup = popup(overlay);
        assertTrue(popup.fromBottom());
        advance(MOTION + 40);
        assertEquals("Rests above the tracker",
                overlay.getHeight() - occupied - popup.edgeMargin() - popup.popupHeight(), popup.badgeTop(), 0.5f);
        // The next popup takes the new edge.
        overlay.setSettings(AchievementSettings.DEFAULTS.withSound(false), true, 100);
        assertTrue("The one shown keeps its edge", popup.fromBottom());
        advance(5_000 + MOTION + 40);
        overlay.onEvent(event("achievement", "Second", "", "5"));
        assertFalse(popup.fromBottom());
    }

    @Test public void eachOfTheSixAnchorsPlacesTheNextPopup() {
        AchievementPopup popup = popup(overlay);
        int[] sides = {-1, 0, 1, -1, 0, 1};
        long gap = MOTION + 5_000L + MOTION + 80;
        for (int i = 0; i < AchievementSettings.POSITIONS.size(); i++) {
            overlay.setSettings(AchievementSettings.DEFAULTS.withPosition(AchievementSettings.POSITIONS.get(i))
                    .withSound(false), true, 100);
            overlay.onEvent(event("achievement", "Succès " + i, "", "5"));
            advance(MOTION + 40);
            String position = AchievementSettings.POSITIONS.get(i);
            assertEquals(position, i >= 3, popup.fromBottom());
            assertEquals(position, sides[i], popup.horizontal());
            float left = popup.badgeLeft(), right = left + popup.popupHeight() + popup.textWidth();
            if (sides[i] < 0) assertEquals(position, popup.edgeMargin(), left, 0.5f);
            else if (sides[i] > 0) assertEquals(position, overlay.getWidth() - popup.edgeMargin(), right, 0.5f);
            else assertEquals(position, overlay.getWidth() / 2f, (left + right) / 2f, 1f);
            if (i >= 3) assertEquals(position, overlay.getHeight() - popup.edgeMargin() - popup.popupHeight(),
                    popup.badgeTop(), 0.5f);
            else assertEquals(position, popup.edgeMargin(), popup.badgeTop(), 0.5f);
            advance(gap - MOTION - 40);
            assertNull(overlay.popupText());
        }
    }

    // ---- The bottom right corner -------------------------------------------------------------

    @Test public void progressSitsInTheBottomRightCornerAndKeepsSettingsDuration() {
        overlay.setSettings(AchievementSettings.DEFAULTS.withProgressSeconds(5).withSound(false), true, 100);
        overlay.onEvent(indicator("progress_show", "Collectionneur", "3/10", "", "55"));
        advance(10);
        AchievementCorner corner = corner(overlay);
        assertTrue(corner.progressShown());
        assertEquals("RetroArch shows how far, not the title", "3/10", String.valueOf(corner.progressValue()));
        View box = corner.progressView();
        assertTrue("A spacing in from the right", overlay.getWidth() - box.getRight() > 0);
        assertEquals("As far from the right as from the bottom", overlay.getWidth() - box.getRight(),
                overlay.getHeight() - box.getBottom());
        assertTrue("At the bottom", box.getBottom() > overlay.getHeight() * 0.9);
        assertTrue("At the right", box.getLeft() > overlay.getWidth() / 2);
        assertEquals("Progression : Collectionneur, 3/10", String.valueOf(box.getContentDescription()));
        // The SDK's own two seconds name no achievement: Settings' five seconds count.
        advance(2_000);
        overlay.onEvent(indicator("progress_hide", "", "", "", ""));
        assertTrue("Not cut at two seconds", corner.progressShown());
        overlay.onEvent(indicator("progress_show", "Collectionneur", "4/10", "", "55"));
        assertEquals("Updated in place", "4/10", String.valueOf(corner.progressValue()));
        advance(5_000 - 100);
        assertTrue("Five seconds after its last change", corner.progressShown());
        advance(200);
        assertFalse(corner.progressShown());
        // A reset or a loaded state names the one shown: hidden at once.
        overlay.onEvent(indicator("progress_show", "Collectionneur", "5/10", "", "55"));
        overlay.onEvent(indicator("progress_hide", "", "", "", "55"));
        assertFalse(corner.progressShown());
        overlay.setSettings(AchievementSettings.DEFAULTS.withProgressPopups(false).withSound(false), true, 100);
        overlay.onEvent(indicator("progress_show", "Collectionneur", "6/10", "", "55"));
        assertFalse("Turned off", corner.progressShown());
        // Settings' default, two seconds.
        overlay.setSettings(AchievementSettings.DEFAULTS.withSound(false), true, 100);
        overlay.onEvent(indicator("progress_show", "Collectionneur", "7/10", "", "55"));
        advance(2_000 + 50);
        assertFalse(corner.progressShown());
    }

    @Test public void theCornerStacksTrackersThenChallengesThenTheProgress() {
        overlay.onEvent(indicator("challenge_show", "Sans dégât", "", "", "7"));
        overlay.onEvent(indicator("tracker_show", "01:23.45", "", "", "4401"));
        overlay.onEvent(indicator("progress_show", "Collectionneur", "3/10", "", "55"));
        advance(10);
        AchievementCorner corner = corner(overlay);
        assertEquals(java.util.Arrays.asList("Sans dégât", "01:23.45"), overlay.indicatorLines());
        overlay.onEvent(indicator("tracker_show", "01:24.00", "", "", "4401"));
        assertEquals("Updated in place", java.util.Arrays.asList("Sans dégât", "01:24.00"), overlay.indicatorLines());
        assertNull("No popup for them", overlay.popupText());
        advance(10);
        View tracker = corner.getChildAt(2), challenge = corner.getChildAt(1), progress = corner.progressView();
        assertTrue("The tracker is a box at the very bottom", tracker instanceof android.widget.TextView);
        assertTrue("The challenge above it", challenge.getBottom() <= tracker.getTop());
        assertTrue("The progress above both", progress.getBottom() <= challenge.getTop());
        assertEquals("A challenge is a square badge", challenge.getWidth(), challenge.getHeight());
        assertEquals("As tall as the progress's badge", challenge.getHeight() * 5 / 4, progress.getHeight());
        assertEquals("Right-aligned", tracker.getRight(), progress.getRight());
        for (int i = 0; i < 10; i++) overlay.onEvent(indicator("challenge_show", "Défi " + i, "", "", "c" + i));
        assertEquals("Eight challenges at most, as RetroArch", AchievementCorner.MAX_CHALLENGES, corner.challengeCount());
        for (int i = 0; i < 6; i++) overlay.onEvent(indicator("tracker_show", "0" + i, "", "", "t" + i));
        assertEquals("Four trackers at most", AchievementCorner.MAX_TRACKERS, corner.trackerCount());
        overlay.onEvent(indicator("challenge_hide", "", "", "", "7"));
        assertFalse(overlay.indicatorLines().contains("Sans dégât"));
        overlay.onEvent(event("hardcore_reset", "", "", ""));
        assertTrue(overlay.indicatorLines().isEmpty());
        assertEquals(0, corner.challengeCount());
        assertEquals(0, corner.trackerCount());
        overlay.onEvent(indicator("tracker_show", "01:24.00", "", "", "4401"));
        overlay.clear();
        assertTrue("The game closes: they go", overlay.indicatorLines().isEmpty());
        assertFalse(corner.progressShown());
    }

    // ---- Badges fetched from the network -----------------------------------------------------

    private static final String ICON = "https://media.retroachievements.org/Images/000001.png";
    private static final String LOCKED = "https://media.retroachievements.org/Badge/00235_lock.png";
    private static final String OTHER = "https://media.retroachievements.org/Badge/00236_lock.png";

    /** Each badge given out when the test says so; the others at once. */
    private static final class HeldHttp extends AchievementsHttp {
        final Map<String, CountDownLatch> gates = new HashMap<>();
        final CountDownLatch go = new CountDownLatch(1);
        final Bitmap image;
        HeldHttp(Bitmap image) {
            super("RavenEmu/test");
            this.image = image;
        }
        HeldHttp hold(String url) {
            gates.put(url, new CountDownLatch(1));
            return this;
        }
        void open(String url) { gates.get(url).countDown(); }
        @Override public Bitmap badge(String url) {
            try {
                CountDownLatch gate = gates.get(url);
                (gate != null ? gate : go).await(5, TimeUnit.SECONDS);
            } catch (InterruptedException stop) {
                Thread.currentThread().interrupt();
            }
            return image;
        }
        void openAll() {
            go.countDown();
            for (CountDownLatch gate : gates.values()) gate.countDown();
        }
    }

    private AchievementOverlay overlayWith(HeldHttp http) {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        BadgeCache badges = new BadgeCache();
        badges.setHttp(http);
        AchievementOverlay withBadges = new AchievementOverlay(activity, badges);
        activity.setContentView(withBadges);
        withBadges.setSettings(AchievementSettings.DEFAULTS.withSound(false), true, 100);
        advance(10);
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

    private static NativeAchievements.Event game(String image) {
        return new NativeAchievements.Event("game", "Synthetic game", "3", "0", image, "1");
    }

    private static final String GAME_TEXT = "Synthetic game · Vous avez débloqué 0 succès sur 3";

    @Test public void theGamePopupOpensWithItsIconNeverWithTheTrophyFirst() throws InterruptedException {
        HeldHttp http = new HeldHttp(Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888));
        AchievementOverlay withIcon = overlayWith(http);
        try {
            withIcon.onEvent(game(ICON));
            AchievementPopup popup = popup(withIcon);
            assertNull("Not before its icon", withIcon.popupText());
            assertEquals(View.GONE, popup.getVisibility());
            http.go.countDown();
            until(() -> withIcon.popupText() != null);
            assertEquals(View.VISIBLE, popup.getVisibility());
            assertFalse("The icon, no trophy", popup.showsPlaceholder());
            assertNotNull(popup.badge());
        } finally {
            http.openAll();
        }
    }

    @Test public void aSlowIconLeavesTheTrophyForTheWholePopup() throws InterruptedException {
        HeldHttp http = new HeldHttp(Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888));
        AchievementOverlay slow = overlayWith(http);
        try {
            slow.onEvent(game(ICON));
            slow.onEvent(event("online", "", "", ""));
            advance(AchievementOverlay.BADGE_WAIT_MILLIS - 100);
            assertNull("Still waiting for the icon", slow.popupText());
            advance(200);
            assertEquals(GAME_TEXT, String.valueOf(slow.popupText()));
            AchievementPopup popup = popup(slow);
            assertTrue(popup.showsPlaceholder());
            http.go.countDown();
            BadgeCache badges = ReflectionHelpers.getField(slow, "badges");
            until(() -> badges.cached(ICON) != null);
            assertTrue("Never changes while shown", popup.showsPlaceholder());
            assertNull(popup.badge());
            advance(MOTION + 5_000 + MOTION + 100);
            assertEquals("Then the next one, without waiting", "RetroAchievements · De nouveau en ligne",
                    String.valueOf(slow.popupText()));
        } finally {
            http.openAll();
        }
    }

    @Test public void noIconToFetchOpensAtOnce() throws InterruptedException {
        HeldHttp failing = new HeldHttp(null);
        failing.go.countDown();
        AchievementOverlay withoutIcon = overlayWith(failing);
        withoutIcon.onEvent(game("http://example.org/not-allowed.png"));
        assertEquals("Not an allowed address: the trophy at once", GAME_TEXT, String.valueOf(withoutIcon.popupText()));
        withoutIcon.clear();
        withoutIcon.onEvent(game(ICON));
        until(() -> withoutIcon.popupText() != null);
        assertTrue("A failed fetch: the trophy, without the full wait", popup(withoutIcon).showsPlaceholder());
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
            assertNull(closing.popupText());
            closing.onEvent(game(ICON));
            assertEquals("The icon known: at once", GAME_TEXT, String.valueOf(closing.popupText()));
        } finally {
            http.openAll();
        }
    }

    /** The bug: a progress whose badge was not fetched yet flashed the star
     * (now the trophy), then its badge; one that changed hands between two
     * achievements flashed at every change. As RetroArch: what is on screen
     * stays until the new badge is here, half a second at most. */
    @Test public void aProgressWaitsForItsBadgeInsteadOfFlashingTheTrophy() throws InterruptedException {
        HeldHttp http = new HeldHttp(Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)).hold(LOCKED).hold(OTHER);
        AchievementOverlay held = overlayWith(http);
        try {
            AchievementCorner corner = corner(held);
            held.onEvent(indicator("progress_show", "Collectionneur", "3/10", LOCKED, "55"));
            assertFalse("Nothing yet: its badge is coming", corner.progressShown());
            assertTrue(corner.progressWaits());
            held.onEvent(indicator("progress_show", "Collectionneur", "4/10", LOCKED, "55"));
            http.open(LOCKED);
            until(corner::progressShown);
            assertFalse("Drawn with its badge, never the trophy first", corner.progressShowsPlaceholder());
            assertEquals("The latest value", "4/10", String.valueOf(corner.progressValue()));
            // Another achievement takes the corner: the first stays until the new badge is here.
            held.onEvent(indicator("progress_show", "Chasseur", "1/3", OTHER, "56"));
            assertEquals("55", corner.progressId());
            assertEquals("4/10", String.valueOf(corner.progressValue()));
            advance(AchievementCorner.BADGE_HOLD_MILLIS + 20);
            assertEquals("Half a second at most: then the trophy", "56", corner.progressId());
            assertTrue(corner.progressShowsPlaceholder());
            http.open(OTHER);
            BadgeCache badges = ReflectionHelpers.getField(held, "badges");
            until(() -> badges.cached(OTHER) != null && !corner.progressShowsPlaceholder());
            // Going back to the first costs nothing: its badge is kept.
            held.onEvent(indicator("progress_show", "Collectionneur", "5/10", LOCKED, "55"));
            assertEquals("At once", "5/10", String.valueOf(corner.progressValue()));
            assertFalse(corner.progressShowsPlaceholder());
        } finally {
            http.openAll();
        }
    }

    @Test public void aChallengeKeepsItsPlaceUntilItsBadgeArrives() throws InterruptedException {
        HeldHttp http = new HeldHttp(Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)).hold(ICON);
        AchievementOverlay held = overlayWith(http);
        try {
            AchievementCorner corner = corner(held);
            held.onEvent(indicator("challenge_show", "Sans dégât", "", ICON, "7"));
            advance(10);
            View box = corner.getChildAt(1);
            assertEquals("Its place, not drawn", View.INVISIBLE, box.getVisibility());
            http.open(ICON);
            until(() -> box.getVisibility() == View.VISIBLE);
        } finally {
            http.openAll();
        }
    }
}
