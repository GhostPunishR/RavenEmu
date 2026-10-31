package com.ravenemu.app.ui.player;
import com.ravenemu.app.bridge.NativeBridge;
import com.ravenemu.app.settings.ControlLayout;
import com.ravenemu.app.settings.ControlSettings;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36, qualifiers = "en-rUS-w320dp-h568dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class GameControlsViewTest {
    private final List<Integer> changes = new ArrayList<>();
    private GameControlsView controls;
    private ActivityController<Activity> host;
    private long downTime;

    @After public void release() {
        if (controls != null) controls.releaseAll();
        if (host != null) host.pause().stop().destroy();
    }

    @Test public void portraitUsesTheOriginalStaggeredLayout() {
        open(320, false);
        View dpad = labelled("Croix directionnelle"), a = labelled("A"), b = labelled("B");
        assertEquals(234, controls.getMeasuredHeight());
        assertEquals(16, dpad.getLeft());
        assertEquals(132, dpad.getWidth());
        assertEquals(60, a.getWidth());
        assertTrue(a.getLeft() > b.getLeft());
        assertTrue(a.getTop() < b.getTop());
        assertTrue(labelled("SELECT").getTop() > dpad.getBottom());
        assertTrue(labelled("START").getTop() > b.getBottom());
        assertEquals(View.GONE, labelled("L").getVisibility());
        assertEquals(View.GONE, labelled("R").getVisibility());
    }

    @Test public void onlyThePressedPartTakesTheAccent() {
        open(320, false);
        View dpad = labelled("Croix directionnelle"), a = labelled("A");
        int arm = dpad.getWidth() / 3, x = dpad.getWidth() / 2 - arm * 3 / 8;
        int up = dpad.getHeight() / 6, down = dpad.getHeight() - up;
        Bitmap idle = render(dpad);
        assertTrue("A dark face", red(idle.getPixel(x, up)) < 0x60);
        assertEquals("Outside the cross, nothing", 0, android.graphics.Color.alpha(idle.getPixel(2, 2)));
        touch(MotionEvent.ACTION_DOWN, 6, centerX(dpad), centerY(dpad) - 40);
        assertEquals(NativeBridge.UP, current());
        Bitmap pressed = render(dpad);
        assertTrue("Up lit", red(pressed.getPixel(x, up)) > 0x80);
        assertTrue("Down not", red(pressed.getPixel(x, down)) < 0x60);
        touch(MotionEvent.ACTION_UP, 6, centerX(dpad), centerY(dpad) - 40);

        int side = a.getWidth() / 4, middle = a.getHeight() / 2;
        assertTrue(red(render(a).getPixel(side, middle)) < 0x60);
        touch(MotionEvent.ACTION_DOWN, 7, centerX(a), centerY(a));
        assertTrue("A pressed takes the accent", red(render(a).getPixel(side, middle)) > 0x80);
        touch(MotionEvent.ACTION_UP, 7, centerX(a), centerY(a));
        assertTrue(red(render(a).getPixel(side, middle)) < 0x60);
    }

    @Test public void sizeAndSpacingFollowTheInterfaceSettingsLikeTheBeta() {
        open(320, false);
        apply(new ControlSettings(false, 115, 80, ControlSettings.STYLE_CLASSIC));
        View dpad = labelled("Croix directionnelle"), a = labelled("A"), b = labelled("B");
        assertEquals("132 dp at 115 %", 152, dpad.getWidth());
        assertEquals(69, a.getWidth());
        assertEquals(69, b.getWidth());
        assertEquals("D-pad, 80 dp, the pills and 16 dp", 288, controls.getMeasuredHeight());
        assertEquals(dpad.getBottom() + 80, labelled("SELECT").getTop(), 1);
        assertEquals(320 - 28, a.getRight(), 1);
        assertEquals(dpad.getBottom(), b.getBottom(), 1);

        apply(new ControlSettings(false, 80, 20, ControlSettings.STYLE_CLASSIC));
        assertEquals(106, dpad.getWidth());
        assertEquals("A and B never go under 48 dp", 48, a.getWidth());
        assertEquals(182, controls.getMeasuredHeight());
        assertEquals(dpad.getBottom() + 20, labelled("SELECT").getTop(), 1);

        apply(ControlSettings.DEFAULTS);
        assertEquals(234, controls.getMeasuredHeight());
        assertEquals(132, dpad.getWidth());
        assertEquals(60, a.getWidth());
    }

    @Test public void theShoulderRowKeepsTheChosenSpacing() {
        open(320, true);
        apply(new ControlSettings(false, 100, 20, ControlSettings.STYLE_CLASSIC));
        View l = labelled("L"), dpad = labelled("Croix directionnelle");
        assertEquals(0, l.getTop());
        assertEquals(l.getBottom() + 20, dpad.getTop(), 1);
        assertEquals(40 + 20 + 132 + 20 + 40 + 16, controls.getMeasuredHeight());
    }

    @Test public void gbaPortraitHasACenteredMenuWithoutExtraGameInput() {
        int[] menus = {0};
        open(320, true, () -> menus[0]++);
        View l = labelled("L"), r = labelled("R"), menu = labelled("Menu du jeu");
        assertEquals(View.VISIBLE, menu.getVisibility());
        assertEquals("The menu is centered", 160f, centerX(menu), 0.5f);
        assertEquals("Aligned with shoulder buttons", centerY(l), centerY(menu), 0.5f);
        assertTrue("No overlap with L", l.getRight() < menu.getLeft());
        assertTrue("No overlap with R", menu.getRight() < r.getLeft());
        assertEquals("Only the seven game controls and the menu", 8, controls.getChildCount());

        assertTrue(touch(MotionEvent.ACTION_DOWN, 12, centerX(menu), centerY(menu)));
        assertTrue(touch(MotionEvent.ACTION_UP, 12, centerX(menu), centerY(menu)));
        // View posts its click to the UI loop; controls publish their input synchronously.
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, menus[0]);
        assertTrue("Opening the menu must never press a core button", changes.isEmpty());

        controls.setEditing(true);
        assertFalse("The menu must not open during control editing", menu.isEnabled());
        controls.setEditing(false);
        assertTrue(menu.isEnabled());
    }

    @Test public void gbAndGbcMenuSitsAtTheTopCentreNeverBetweenSelectAndStart() {
        int[] menus = {0};
        open(360, false, () -> menus[0]++);
        View menu = labelled("Menu du jeu"), dpad = labelled("Croix directionnelle");
        View select = labelled("SELECT"), start = labelled("START"), a = labelled("A");
        assertEquals(View.VISIBLE, menu.getVisibility());
        assertEquals(View.GONE, labelled("L").getVisibility());
        assertEquals("No extra row takes space from the picture", 234, controls.getMeasuredHeight());
        assertEquals("At the top centre", 180f, centerX(menu), 0.5f);
        assertEquals(0, menu.getTop());
        assertTrue("Above the Select/Start row", menu.getBottom() < select.getTop());
        assertTrue("Clear of the D-pad", dpad.getRight() < menu.getLeft());
        assertTrue("Clear of A", menu.getRight() < a.getLeft());
        assertTrue("Clear of B", menu.getBottom() < labelled("B").getTop());
        assertTrue(select.getRight() < start.getLeft());
        assertTrue(touch(MotionEvent.ACTION_DOWN, 13, centerX(menu), centerY(menu)));
        assertTrue(touch(MotionEvent.ACTION_UP, 13, centerX(menu), centerY(menu)));
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, menus[0]);
        assertTrue("Menu never sends emulator buttons", changes.isEmpty());
    }

    @Test public void aNarrowGbScreenKeepsTheMenuOffTheDpadAndA() {
        open(320, false, () -> {});
        View menu = labelled("Menu du jeu");
        assertTrue(labelled("Croix directionnelle").getRight() < menu.getLeft());
        assertTrue(menu.getRight() < labelled("A").getLeft());
        assertEquals(0, menu.getTop());
    }

    @Test public void aVeryNarrowScreenSlimsTheMenuBetweenTheDpadAndA() {
        open(240, false, () -> {});
        View menu = labelled("Menu du jeu");
        assertTrue("Slimmer, still apart", labelled("Croix directionnelle").getRight() < menu.getLeft());
        assertTrue(menu.getRight() < labelled("A").getLeft());
        assertTrue(menu.getWidth() >= 24);
    }

    @Test public void menuMovesWithTheConsoleAndStaysHiddenWithoutAnAction() {
        open(320, false, () -> {});
        View menu = labelled("Menu du jeu");
        assertEquals(View.VISIBLE, menu.getVisibility());
        assertEquals(0, menu.getTop());
        controls.setShoulders(true);
        apply(ControlSettings.DEFAULTS);
        assertEquals(View.VISIBLE, menu.getVisibility());
        assertEquals(centerY(labelled("L")), centerY(menu), 0.5f);
        controls.setShoulders(false);
        apply(ControlSettings.DEFAULTS);
        assertEquals(View.VISIBLE, menu.getVisibility());
        assertEquals(0, menu.getTop());
        assertTrue(menu.getBottom() < labelled("SELECT").getTop());
    }

    @Test public void anUnwiredMenuIsNotShown() {
        open(320, false);
        assertEquals(View.GONE, labelled("Menu du jeu").getVisibility());
        controls.setShoulders(true);
        assertEquals(View.GONE, labelled("Menu du jeu").getVisibility());
    }

    @Test public void changingTheSizeReleasesAHeldButton() {
        open(320, false);
        View a = labelled("A");
        touch(MotionEvent.ACTION_DOWN, 3, centerX(a), centerY(a));
        assertEquals(NativeBridge.A, current());
        controls.setSettings(ControlSettings.DEFAULTS.withScale(90));
        assertEquals(0, current());
        controls.setSettings(ControlSettings.DEFAULTS.withScale(90).withStyle(ControlSettings.STYLE_CONTRAST));
        assertEquals("A new style alone moves nothing", 1, changes.stream().filter(m -> m == 0).count());
    }

    @Test public void hapticsOnlyWhenChosenAndOnlyAsAPressStarts() {
        open(320, false);
        View a = labelled("A"), dpad = labelled("Croix directionnelle");
        touch(MotionEvent.ACTION_DOWN, 4, centerX(a), centerY(a));
        touch(MotionEvent.ACTION_UP, 4, centerX(a), centerY(a));
        assertEquals("Off by default, as in the beta", -1, shadowOf(a).lastHapticFeedbackPerformed());
        controls.setSettings(ControlSettings.DEFAULTS.withHaptics(true));
        touch(MotionEvent.ACTION_DOWN, 4, centerX(a), centerY(a));
        assertEquals(HapticFeedbackConstants.LONG_PRESS, shadowOf(a).lastHapticFeedbackPerformed());
        touch(MotionEvent.ACTION_UP, 4, centerX(a), centerY(a));
        float x = centerX(dpad), y = centerY(dpad);
        touch(MotionEvent.ACTION_DOWN, 5, x, y);
        assertEquals("The neutral hub presses nothing", -1, shadowOf(dpad).lastHapticFeedbackPerformed());
        touch(MotionEvent.ACTION_MOVE, 5, x, y - 40);
        assertEquals(HapticFeedbackConstants.LONG_PRESS, shadowOf(dpad).lastHapticFeedbackPerformed());
        touch(MotionEvent.ACTION_UP, 5, x, y - 40);
    }

    @Test public void editingDragsAControlWithoutPressingItAndKeepsItInside() {
        open(320, false);
        View a = labelled("A");
        int left = a.getLeft(), top = a.getTop();
        controls.setEditing(true);
        touch(MotionEvent.ACTION_DOWN, 8, centerX(a), centerY(a));
        touch(MotionEvent.ACTION_MOVE, 8, centerX(a) - 100, centerY(a) + 30);
        relayout();
        assertEquals(left - 100, a.getLeft());
        assertEquals(top + 30, a.getTop());
        touch(MotionEvent.ACTION_MOVE, 8, centerX(a) + 1000, centerY(a) + 1000);
        relayout();
        assertEquals("Never past the right edge", 320, a.getRight());
        assertEquals("Never under the controls", controls.getHeight(), a.getBottom());
        touch(MotionEvent.ACTION_UP, 8, centerX(a), centerY(a));
        assertTrue("A drag presses nothing", changes.isEmpty());
        ControlLayout layout = controls.controlLayout();
        assertEquals(a.getLeft() - left, layout.dx(1), 1f);
        assertEquals(a.getTop() - top, layout.dy(1), 1f);

        controls.setEditing(false);
        touch(MotionEvent.ACTION_DOWN, 9, centerX(a), centerY(a));
        assertEquals("At its new place, A presses A", NativeBridge.A, current());
        touch(MotionEvent.ACTION_UP, 9, centerX(a), centerY(a));
    }

    @Test public void aSavedLayoutPlacesTheControlsAndTheDefaultBringsThemBack() {
        open(320, false);
        View dpad = labelled("Croix directionnelle"), start = labelled("START");
        int startLeft = start.getLeft();
        controls.setControlLayout(ControlLayout.DEFAULT.with(0, 20, -10).with(4, -30, 0));
        relayout();
        assertEquals(36, dpad.getLeft());
        assertEquals("Kept inside the view", 0, dpad.getTop());
        assertEquals(startLeft - 30, start.getLeft());
        controls.setControlLayout(ControlLayout.DEFAULT);
        relayout();
        assertEquals(16, dpad.getLeft());
        assertEquals(startLeft, start.getLeft());
    }

    @Test public void accessibilityMovesAControlOnlyWhileEditing() {
        open(320, false);
        View dpad = labelled("Croix directionnelle");
        final int moveRight = 0x02000012, up = 0x02000001;
        assertTrue(hasAction(dpad, up));
        assertFalse(hasAction(dpad, moveRight));
        dpad.performAccessibilityAction(moveRight, null);
        relayout();
        assertEquals(16, dpad.getLeft());
        controls.setEditing(true);
        assertTrue(hasAction(dpad, moveRight));
        assertFalse("No D-pad press while editing", hasAction(dpad, up));
        assertTrue(dpad.performAccessibilityAction(moveRight, null));
        relayout();
        assertEquals(24, dpad.getLeft());
        assertTrue(changes.isEmpty());
    }

    @Test public void aDpadGestureMayStartInTheHubSlideDiagonallyAndLeave() {
        open(320, false);
        View dpad = labelled("Croix directionnelle");
        float x = centerX(dpad), y = centerY(dpad);
        touch(MotionEvent.ACTION_DOWN, 7, x, y);
        assertEquals(0, current());
        touch(MotionEvent.ACTION_MOVE, 7, x + 40, y - 40);
        assertEquals(NativeBridge.UP | NativeBridge.RIGHT, current());
        touch(MotionEvent.ACTION_MOVE, 7, dpad.getLeft() - 4, y);
        assertEquals(0, current());
        touch(MotionEvent.ACTION_MOVE, 7, x - 40, y);
        assertEquals(NativeBridge.LEFT, current());
        touch(MotionEvent.ACTION_UP, 7, x - 40, y);
        assertEquals(0, current());
    }

    @Test public void independentFingersCombineActionAndDiagonalThenReleaseSeparately() {
        open(320, false);
        View dpad = labelled("Croix directionnelle"), a = labelled("A");
        float dx = centerX(dpad) - 40, dy = centerY(dpad) - 40;
        touch(MotionEvent.ACTION_DOWN, 7, dx, dy);
        assertEquals(NativeBridge.UP | NativeBridge.LEFT, current());
        touch(MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                new int[]{7, 21}, new float[]{dx, centerX(a)}, new float[]{dy, centerY(a)});
        assertEquals(NativeBridge.UP | NativeBridge.LEFT | NativeBridge.A, current());
        touch(MotionEvent.ACTION_POINTER_UP,
                new int[]{7, 21}, new float[]{dx, centerX(a)}, new float[]{dy, centerY(a)});
        assertEquals(NativeBridge.A, current());
        touch(MotionEvent.ACTION_UP, 21, centerX(a), centerY(a));
        assertEquals(0, current());
    }

    @Test public void releasingOneOfTwoFingersOnTheSameButtonDoesNotReleaseTheOther() {
        open(320, false);
        View a = labelled("A");
        touch(MotionEvent.ACTION_DOWN, 3, centerX(a), centerY(a));
        touch(MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                new int[]{3, 19}, new float[]{centerX(a), centerX(a) + 5},
                new float[]{centerY(a), centerY(a)});
        assertEquals(NativeBridge.A, current());
        touch(MotionEvent.ACTION_POINTER_UP,
                new int[]{3, 19}, new float[]{centerX(a), centerX(a) + 5},
                new float[]{centerY(a), centerY(a)});
        assertEquals(NativeBridge.A, current());
        touch(MotionEvent.ACTION_UP, 19, centerX(a) + 5, centerY(a));
        assertEquals(0, current());
        assertEquals(List.of(NativeBridge.A, 0), changes);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200));
        assertEquals("A touch release must not create an extra accessibility press", 0, current());
    }

    @Test public void slidingOffAnActionButtonAndCancellingNeverLeavesItHeld() {
        open(320, false);
        View a = labelled("A");
        touch(MotionEvent.ACTION_DOWN, 2, centerX(a), centerY(a));
        assertEquals(NativeBridge.A, current());
        touch(MotionEvent.ACTION_MOVE, 2, a.getLeft() - 10, centerY(a));
        assertEquals(0, current());
        touch(MotionEvent.ACTION_MOVE, 2, centerX(a), centerY(a));
        assertEquals(NativeBridge.A, current());
        touch(MotionEvent.ACTION_CANCEL, 2, centerX(a), centerY(a));
        assertEquals(0, current());
    }

    @Test public void anExternalReleaseIgnoresTheOldGestureUntilANewDown() {
        open(320, false);
        View a = labelled("A"), b = labelled("B");
        touch(MotionEvent.ACTION_DOWN, 1, centerX(a), centerY(a));
        controls.releaseAll();
        assertEquals(0, current());
        touch(MotionEvent.ACTION_MOVE, 1, centerX(a), centerY(a));
        assertEquals(0, current());
        touch(MotionEvent.ACTION_UP, 1, centerX(a), centerY(a));
        touch(MotionEvent.ACTION_DOWN, 1, centerX(b), centerY(b));
        assertEquals(NativeBridge.B, current());
        controls.onDetachedFromWindow();
        assertEquals(0, current());
    }

    @Test public void gbaShouldersHaveDistinctBitsAndDisappearWithoutHeldInputs() {
        open(320, true);
        assertEquals(320, controls.getMeasuredHeight());
        View l = labelled("L"), r = labelled("R");
        assertEquals(View.VISIBLE, l.getVisibility());
        assertTrue(l.getBottom() < labelled("Croix directionnelle").getTop());
        touch(MotionEvent.ACTION_DOWN, 5, centerX(l), centerY(l));
        touch(MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                new int[]{5, 8}, new float[]{centerX(l), centerX(r)}, new float[]{centerY(l), centerY(r)});
        assertEquals((1 << 10) | (1 << 11), current());
        controls.setShoulders(false);
        assertEquals(0, current());
        assertEquals(View.GONE, l.getVisibility());
        assertEquals(View.GONE, r.getVisibility());
    }

    @Test public void semanticClicksAreFiniteAndPreservePhysicalFingers() {
        open(320, false);
        View a = labelled("A"), b = labelled("B");
        AccessibilityNodeInfo node = b.createAccessibilityNodeInfo();
        assertEquals("android.widget.Button", node.getClassName());
        assertEquals("B", node.getContentDescription());
        assertTrue(node.isClickable());
        touch(MotionEvent.ACTION_DOWN, 4, centerX(a), centerY(a));
        assertTrue(b.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null));
        assertEquals(NativeBridge.A | NativeBridge.B, current());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150));
        assertEquals(NativeBridge.A, current());
        controls.releaseAll();
        assertTrue(labelled("START").performClick());
        assertEquals(NativeBridge.START, current());
        controls.onDetachedFromWindow();
        assertEquals(0, current());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200));
        assertEquals(0, current());
    }

    @Test public void theDpadExposesFourAccessibleDirectionActions() {
        open(320, false);
        View dpad = labelled("Croix directionnelle");
        AccessibilityNodeInfo node = dpad.createAccessibilityNodeInfo();
        int count = 0;
        for (AccessibilityNodeInfo.AccessibilityAction action : node.getActionList()) {
            if (action.getLabel() != null &&
                    List.of("Haut", "Bas", "Gauche", "Droite").contains(action.getLabel().toString())) {
                count++;
                if ("Haut".contentEquals(action.getLabel())) {
                    assertTrue(dpad.performAccessibilityAction(action.getId(), null));
                    assertEquals(NativeBridge.UP, current());
                    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150));
                    assertEquals(0, current());
                }
            }
        }
        assertEquals(4, count);
    }

    @Test @Config(qualifiers = "en-rUS-w640dp-h360dp-land-mdpi")
    public void landscapeControlsStayAtTheEdgesWithAnUntouchedCenter() {
        open(640, true);
        assertEquals(152, controls.getMeasuredHeight());
        View dpad = labelled("Croix directionnelle"), a = labelled("A"), b = labelled("B");
        assertEquals(80, dpad.getWidth());
        assertEquals(48, a.getWidth());
        assertTrue(dpad.getRight() <= 116);
        assertTrue(b.getLeft() >= 640 - 116);
        assertFalse(touch(MotionEvent.ACTION_DOWN, 10, 320, 50));
        assertEquals(0, current());
        assertTrue(labelled("SELECT").getTop() >= controls.getHeight() - 44);
        assertTrue(labelled("START").getBottom() <= controls.getHeight());
        assertEquals("The top centre is the picture's", View.GONE, labelled("Menu du jeu").getVisibility());
        touch(MotionEvent.ACTION_DOWN, 10, centerX(a), centerY(a));
        assertEquals(NativeBridge.A, current());
    }

    private void open(int width, boolean shoulders) {
        open(width, shoulders, null);
    }

    private void open(int width, boolean shoulders, Runnable menuAction) {
        host = Robolectric.buildActivity(Activity.class).setup().visible();
        controls = new GameControlsView(host.get(), changes::add, menuAction);
        controls.setShoulders(shoulders);
        controls.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.AT_MOST));
        host.get().setContentView(controls, new ViewGroup.LayoutParams(width, controls.getMeasuredHeight()));
        controls.layout(0, 0, controls.getMeasuredWidth(), controls.getMeasuredHeight());
        shadowOf(Looper.getMainLooper()).idle();
    }

    private void relayout() {
        controls.measure(View.MeasureSpec.makeMeasureSpec(controls.getWidth(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(controls.getHeight(), View.MeasureSpec.EXACTLY));
        controls.layout(0, 0, controls.getMeasuredWidth(), controls.getMeasuredHeight());
    }

    private static boolean hasAction(View view, int id) {
        AccessibilityNodeInfo info = view.createAccessibilityNodeInfo();
        for (AccessibilityNodeInfo.AccessibilityAction action : info.getActionList())
            if (action.getId() == id) return true;
        return false;
    }

    private void apply(ControlSettings settings) {
        controls.setSettings(settings);
        int width = controls.getWidth();
        controls.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.AT_MOST));
        controls.layout(0, 0, controls.getMeasuredWidth(), controls.getMeasuredHeight());
    }

    private View labelled(String name) {
        for (int i = 0; i < controls.getChildCount(); i++) {
            View child = controls.getChildAt(i);
            if (name.contentEquals(child.getContentDescription())) return child;
        }
        throw new AssertionError("Missing control: " + name);
    }

    private static Bitmap render(View view) {
        Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bitmap));
        return bitmap;
    }

    private static int red(int color) { return android.graphics.Color.red(color); }

    private int current() { return changes.isEmpty() ? 0 : changes.get(changes.size() - 1); }
    private float centerX(View view) { return view.getLeft() + view.getWidth() / 2f; }
    private float centerY(View view) { return view.getTop() + view.getHeight() / 2f; }

    private boolean touch(int action, int id, float x, float y) {
        return touch(action, new int[]{id}, new float[]{x}, new float[]{y});
    }

    private boolean touch(int action, int[] ids, float[] x, float[] y) {
        long now = SystemClock.uptimeMillis();
        if ((action & MotionEvent.ACTION_MASK) == MotionEvent.ACTION_DOWN) downTime = now;
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[ids.length];
        MotionEvent.PointerCoords[] positions = new MotionEvent.PointerCoords[ids.length];
        for (int i = 0; i < ids.length; i++) {
            properties[i] = new MotionEvent.PointerProperties();
            properties[i].id = ids[i];
            properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            positions[i] = new MotionEvent.PointerCoords();
            positions[i].x = x[i];
            positions[i].y = y[i];
            positions[i].pressure = positions[i].size = 1;
        }
        MotionEvent event = MotionEvent.obtain(downTime, now, action, ids.length, properties, positions,
                0, 0, 1, 1, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        try { return controls.dispatchTouchEvent(event); }
        finally { event.recycle(); }
    }
}
