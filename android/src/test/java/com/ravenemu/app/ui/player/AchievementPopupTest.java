package com.ravenemu.app.ui.player;

import android.app.Activity;
import android.os.Looper;
import android.widget.FrameLayout;
import java.time.Duration;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** RetroArch's popup geometry (gfx_widget_achievement_popup.c), stage by
 * stage, with the fractions set by hand: four lines tall, a square badge,
 * the text two thirds of a line in from each side; the badge slides from
 * beyond the edge to its place, then the text unfolds; centred, the popup
 * moves left by half its text, on the right by the whole of it, on the left
 * not at all. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36, qualifiers = "fr-rFR-w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class AchievementPopupTest {
    private AchievementPopup popup;
    private FrameLayout parent;

    @Before public void prepare() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        parent = new FrameLayout(activity);
        popup = new AchievementPopup(activity);
        parent.addView(popup, new FrameLayout.LayoutParams(-1, -1));
        activity.setContentView(parent);
        popup.show("Succès débloqué", "Premier pas (5)", null);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20));
    }

    @Test public void fourLinesTallWithASquareBadgeAndRetroArchsColours() {
        assertTrue(popup.popupHeight() > 0);
        assertEquals("Two lines, a line above and below", 0, popup.popupHeight() % 4);
        assertTrue("The longer line and two thirds of a line each side",
                popup.textWidth() > popup.popupHeight() / 4 * 4 / 3);
        assertEquals(0xff888888, AchievementPopup.FAINT);
        assertEquals(0xffeeeeee, AchievementPopup.INFO);
        assertEquals("Black at 75 %", 0xbf000000, AchievementPopup.BACKDROP);
        assertEquals("Succès débloqué. Premier pas (5)", String.valueOf(popup.getContentDescription()));
        assertTrue(popup.showsPlaceholder());
    }

    @Test public void fromTheTopTheBadgeSlidesInThenTheTextUnfoldsToTheCentre() {
        final int size = popup.popupHeight(), width = parent.getWidth(), text = popup.textWidth();
        popup.setSlide(0f);
        popup.setUnfold(0f);
        assertEquals("Beyond the top", -size, popup.badgeTop(), 0.5f);
        assertEquals("The badge alone, centred", (width - size) / 2f, popup.badgeLeft(), 0.5f);
        assertEquals("No text yet", 0, popup.textShown());
        popup.setSlide(0.5f);
        assertEquals(-size + (popup.edgeMargin() + size) * 0.5f, popup.badgeTop(), 0.5f);
        popup.setSlide(1f);
        assertEquals("A margin below the top", popup.edgeMargin(), popup.badgeTop(), 0.5f);
        popup.setUnfold(0.5f);
        assertEquals("Half of the text", Math.round(text * 0.5f), popup.textShown());
        assertEquals("Moving left by half of what is shown", (width - size) / 2f - text / 4f, popup.badgeLeft(), 0.5f);
        popup.setUnfold(1f);
        assertEquals(text, popup.textShown());
        assertEquals("Centred, badge and text", width / 2f, popup.badgeLeft() + (size + text) / 2f, 0.5f);
    }

    @Test public void onTheLeftItStaysAgainstTheLeftEdgeAndOnTheRightItEndsAgainstTheRightOne() {
        final int size = popup.popupHeight(), width = parent.getWidth(), text = popup.textWidth();
        popup.setAnchor(false, -1);
        popup.setSlide(1f);
        popup.setUnfold(0f);
        assertEquals("RetroArch's ANCHOR_LEFT: a margin from the left", popup.edgeMargin(), popup.badgeLeft(), 0.5f);
        popup.setUnfold(1f);
        assertEquals("It does not move while it unfolds", popup.edgeMargin(), popup.badgeLeft(), 0.5f);
        popup.setAnchor(false, 1);
        popup.setUnfold(0f);
        assertEquals("ANCHOR_RIGHT: the badge alone against the right", width - size - popup.edgeMargin(),
                popup.badgeLeft(), 0.5f);
        popup.setUnfold(0.5f);
        assertEquals("Moving left by what is shown", width - size - popup.edgeMargin() - text * 0.5f,
                popup.badgeLeft(), 0.5f);
        popup.setUnfold(1f);
        assertEquals("Badge and text against the right", width - popup.edgeMargin(),
                popup.badgeLeft() + size + text, 0.5f);
        popup.setAnchor(true, 1);
        popup.setSlide(1f);
        assertEquals("Bottom right: a margin from the bottom too", parent.getHeight() - popup.edgeMargin() - size,
                popup.badgeTop(), 0.5f);
    }

    @Test public void fromTheBottomItStartsBelowAndRestsAboveTheLift() {
        final int size = popup.popupHeight(), height = parent.getHeight();
        popup.setAnchor(true, 0);
        popup.setLift(40);
        popup.setSlide(0f);
        assertEquals("Below the picture", height, popup.badgeTop(), 0.5f);
        popup.setSlide(1f);
        assertEquals("Above what the corner shows", height - 40 - popup.edgeMargin() - size, popup.badgeTop(), 0.5f);
    }

    @Test public void aLongLineNeverLeavesThePicture() {
        StringBuilder longTitle = new StringBuilder();
        for (int i = 0; i < 40; i++) longTitle.append("Très long titre ");
        popup.show("Succès débloqué", longTitle.toString(), null);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20));
        popup.setSlide(1f);
        popup.setUnfold(1f);
        assertTrue(popup.badgeLeft() >= popup.edgeMargin() - 1);
        assertTrue(popup.badgeLeft() + popup.popupHeight() + popup.textWidth() <= parent.getWidth() - popup.edgeMargin() + 1);
    }
}
