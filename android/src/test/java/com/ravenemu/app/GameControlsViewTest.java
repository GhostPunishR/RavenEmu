package com.ravenemu.app;

import android.app.Activity;
import android.os.Looper;
import android.os.SystemClock;
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
        touch(MotionEvent.ACTION_DOWN, 10, centerX(a), centerY(a));
        assertEquals(NativeBridge.A, current());
    }

    private void open(int width, boolean shoulders) {
        host = Robolectric.buildActivity(Activity.class).setup().visible();
        controls = new GameControlsView(host.get(), changes::add);
        controls.setShoulders(shoulders);
        controls.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.AT_MOST));
        host.get().setContentView(controls, new ViewGroup.LayoutParams(width, controls.getMeasuredHeight()));
        controls.layout(0, 0, controls.getMeasuredWidth(), controls.getMeasuredHeight());
        shadowOf(Looper.getMainLooper()).idle();
    }

    private View labelled(String name) {
        for (int i = 0; i < controls.getChildCount(); i++) {
            View child = controls.getChildAt(i);
            if (name.contentEquals(child.getContentDescription())) return child;
        }
        throw new AssertionError("Missing control: " + name);
    }

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
