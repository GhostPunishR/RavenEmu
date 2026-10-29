package com.ravenemu.app.ui.player;
import com.ravenemu.app.bridge.NativeBridge;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseArray;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * Java counterpart of RavenEmu's touch controls: a sliding diagonal D-pad,
 * staggered A/B, and separate Select/Start. No emulation or JNI runs here.
 * Each child owns its pointers; the listener receives their combined mask.
 */
final class GameControlsView extends ViewGroup {
    interface Listener { void onButtonsChanged(int mask); }

    private static final int BORDER = 0xe0bbb3d4;
    private static final int ACCENT = 0xffd7caff;
    private static final long ACCESSIBLE_PRESS_MILLIS = 100L;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SparseArray<Runnable> accessibleReleases = new SparseArray<>();
    private final Dpad dpad;
    private final Control a, b, select, start, leftShoulder, rightShoulder;
    private boolean shoulders;
    private int accessibleMask;
    private int reportedMask;

    GameControlsView(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        setMotionEventSplittingEnabled(true);
        setClipChildren(false);
        setClipToPadding(false);
        setBackgroundColor(compact() ? Color.TRANSPARENT : Color.BLACK);
        dpad = new Dpad(context);
        a = new Control(context, "A", NativeBridge.A, false);
        b = new Control(context, "B", NativeBridge.B, false);
        select = new Control(context, "SELECT", NativeBridge.SELECT, true);
        start = new Control(context, "START", NativeBridge.START, true);
        leftShoulder = new Control(context, "L", NativeBridge.L, true);
        rightShoulder = new Control(context, "R", NativeBridge.R, true);
        addView(dpad);
        addView(a);
        addView(b);
        addView(select);
        addView(start);
        addView(leftShoulder);
        addView(rightShoulder);
        leftShoulder.setVisibility(GONE);
        rightShoulder.setVisibility(GONE);
    }

    void setShoulders(boolean value) {
        if (shoulders == value) return;
        releaseAll();
        shoulders = value;
        leftShoulder.setVisibility(value ? VISIBLE : GONE);
        rightShoulder.setVisibility(value ? VISIBLE : GONE);
        requestLayout();
    }

    /** Menu, focus loss and detachment must never leave a touch button held. */
    void releaseAll() {
        for (int i = 0; i < accessibleReleases.size(); i++)
            main.removeCallbacks(accessibleReleases.valueAt(i));
        accessibleReleases.clear();
        accessibleMask = 0;
        for (int i = 0; i < getChildCount(); i++) {
            TouchControl child = (TouchControl) getChildAt(i);
            child.mask = 0;
            child.tracking = false;
            child.invalidate();
        }
        publish();
    }

    private void publish() {
        int combined = accessibleMask;
        for (int i = 0; i < getChildCount(); i++)
            combined |= ((TouchControl) getChildAt(i)).mask;
        if (combined == reportedMask) return;
        reportedMask = combined;
        listener.onButtonsChanged(combined);
    }

    // A semantic click has a finite duration; its release never clears a
    // physical finger still holding the same button or another control.
    private void accessiblePress(int mask) {
        Runnable previous = accessibleReleases.get(mask);
        if (previous != null) main.removeCallbacks(previous);
        accessibleMask |= mask;
        publish();
        Runnable release = () -> {
            accessibleReleases.remove(mask);
            accessibleMask &= ~mask;
            publish();
        };
        accessibleReleases.put(mask, release);
        main.postDelayed(release, ACCESSIBLE_PRESS_MILLIS);
    }

    private boolean compact() {
        return getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    private int dp(float size) { return Math.round(size * getResources().getDisplayMetrics().density); }

    private float cluster(float width) {
        return Math.max(60f, Math.min(132f, Math.min((width - 32f) / 2f, width - 104f)));
    }

    private float desiredHeight(float width) {
        return compact() ? 152f : (shoulders ? 86f : 0f) + cluster(width) + 46f + 40f + 16f;
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = resolveSize(dp(320), widthSpec);
        float widthDp = width / getResources().getDisplayMetrics().density;
        setMeasuredDimension(width, resolveSize(dp(desiredHeight(widthDp)), heightSpec));
        measureControls(width, getMeasuredHeight(), false);
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        measureControls(right - left, bottom - top, true);
    }

    private void measureControls(int widthPx, int heightPx, boolean layout) {
        float density = getResources().getDisplayMetrics().density;
        float width = widthPx / density;
        float height = heightPx / density;
        float desired = desiredHeight(width);
        float scale = Math.min(1f, height / desired);
        float offset = Math.max(0f, height - desired);
        if (compact()) {
            place(dpad, 16, offset + 32 * scale, 80, 80 * scale, layout);
            place(a, width - 64, offset + 32 * scale, 48, 48 * scale, layout);
            place(b, width - 104, offset + 80 * scale, 48, 48 * scale, layout);
            place(select, width / 2 - 101, offset + 112 * scale, 84, 40 * scale, layout);
            place(start, width / 2 + 17, offset + 112 * scale, 84, 40 * scale, layout);
            place(leftShoulder, 16, offset, 80, 28 * scale, layout);
            place(rightShoulder, width - 96, offset, 80, 28 * scale, layout);
        } else {
            float size = cluster(width);
            float row = shoulders ? 86f : 0f;
            float actionWidth = Math.min(144f, Math.max(60f, width - 44f - size));
            float gap = Math.min(50f, Math.max(8f, width - 168f));
            place(dpad, 16, offset + row * scale, size, size * scale, layout);
            place(a, width - 88, offset + row * scale, 60, 60 * scale, layout);
            place(b, width - 28 - actionWidth, offset + (row + size - 60) * scale, 60, 60 * scale, layout);
            place(select, (width - 168 - gap) / 2, offset + (row + size + 46) * scale, 84, 40 * scale, layout);
            place(start, (width + gap) / 2, offset + (row + size + 46) * scale, 84, 40 * scale, layout);
            place(leftShoulder, 16, offset, 96, 40 * scale, layout);
            place(rightShoulder, width - 112, offset, 96, 40 * scale, layout);
        }
    }

    private void place(View child, float x, float y, float width, float height, boolean layout) {
        int w = dp(width), h = dp(height);
        if (layout) child.layout(dp(x), dp(y), dp(x) + w, dp(y) + h);
        else child.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        if (oldw != 0 || oldh != 0) releaseAll();
    }

    @Override protected void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        releaseAll();
        setBackgroundColor(compact() ? Color.TRANSPARENT : Color.BLACK);
        requestLayout();
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (!focused) releaseAll();
    }

    @Override protected void onDetachedFromWindow() {
        releaseAll();
        super.onDetachedFromWindow();
    }

    @Override public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        if (!enabled) releaseAll();
        for (int i = 0; i < getChildCount(); i++) getChildAt(i).setEnabled(enabled);
    }

    private abstract class TouchControl extends View {
        int mask;
        boolean tracking;
        final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        LinearGradient normal, pressed;
        TouchControl(Context context, String description) {
            super(context);
            setContentDescription(description);
            setFocusable(true);
            setClickable(true);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        }

        abstract int at(float x, float y);
        boolean acceptsDown(float x, float y) { return at(x, y) != 0; }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (!isEnabled()) return false;
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                if (!acceptsDown(event.getX(), event.getY())) return false;
                tracking = true;
            } else if (!tracking) return false;
            int next = 0;
            if (action != MotionEvent.ACTION_CANCEL && action != MotionEvent.ACTION_OUTSIDE) {
                for (int i = 0; i < event.getPointerCount(); i++) {
                    if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) &&
                            i == event.getActionIndex()) continue;
                    next |= at(event.getX(i), event.getY(i));
                }
            }
            boolean clicked = action == MotionEvent.ACTION_UP && mask != 0 && at(event.getX(), event.getY()) != 0;
            if (mask != next) {
                mask = next;
                invalidate();
                publish();
            }
            if (action == MotionEvent.ACTION_DOWN && getParent() != null)
                getParent().requestDisallowInterceptTouchEvent(true);
            // Touch already produced its press/release. Only announce its click;
            // performClick() is reserved for keyboard/accessibility activation.
            if (clicked) super.performClick();
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL ||
                    action == MotionEvent.ACTION_OUTSIDE) tracking = false;
            return true;
        }

        @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            normal = new LinearGradient(0, 0, 0, Math.max(1, h), 0xff35334a, 0xff161620, Shader.TileMode.CLAMP);
            pressed = new LinearGradient(0, 0, 0, Math.max(1, h), 0xff735bb3, 0xff33284f, Shader.TileMode.CLAMP);
        }

        void paint() {
            fill.setShader(mask == 0 ? normal : pressed);
            line.setShader(null);
            line.setColor(mask == 0 ? BORDER : ACCENT);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(dp(mask == 0 ? 1.5f : 2));
        }

        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setClassName("android.widget.Button");
        }
    }

    private final class Control extends TouchControl {
        final String label;
        final int button;
        final boolean pill;
        final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF bounds = new RectF();

        Control(Context context, String label, int button, boolean pill) {
            super(context, label);
            this.label = label;
            this.button = button;
            this.pill = pill;
            text.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
            text.setTextAlign(Paint.Align.CENTER);
            text.setTextSize((pill ? 11 : 24) * getResources().getDisplayMetrics().scaledDensity);
        }

        @Override int at(float x, float y) {
            if (x < 0 || y < 0 || x >= getWidth() || y >= getHeight()) return 0;
            if (!pill) {
                float dx = (x - getWidth() / 2f) / (getWidth() / 2f);
                float dy = (y - getHeight() / 2f) / (getHeight() / 2f);
                if (dx * dx + dy * dy > 1f) return 0;
            }
            return button;
        }

        @Override protected void onDraw(Canvas canvas) {
            paint();
            float inset = dp(1);
            bounds.set(inset, inset, getWidth() - inset, getHeight() - inset);
            float radius = Math.min(getWidth(), getHeight()) / 2f;
            canvas.drawRoundRect(bounds, radius, radius, fill);
            canvas.drawRoundRect(bounds, radius, radius, line);
            text.setColor(mask == 0 ? ACCENT : Color.WHITE);
            canvas.drawText(label, getWidth() / 2f,
                    (getHeight() - text.ascent() - text.descent()) / 2f, text);
        }

        @Override public boolean performClick() {
            if (!isEnabled() || getVisibility() != VISIBLE) return false;
            accessiblePress(button);
            super.performClick();
            return true;
        }
    }

    private final class Dpad extends TouchControl {
        // Custom accessibility actions, separate from the platform's actions.
        private static final int ACTION_UP = 0x02000001, ACTION_DOWN = 0x02000002;
        private static final int ACTION_LEFT = 0x02000003, ACTION_RIGHT = 0x02000004;
        final RectF bounds = new RectF();

        Dpad(Context context) {
            super(context, "Croix directionnelle");
            setClickable(false);
        }

        @Override boolean acceptsDown(float x, float y) {
            // Starting in the neutral hub must still allow a slide into a direction.
            return x >= 0 && y >= 0 && x < getWidth() && y < getHeight();
        }

        @Override int at(float x, float y) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float reach = Math.min(getWidth(), getHeight()) * .49f;
            float dx = x - cx, dy = y - cy;
            if (reach <= 0 || Math.abs(dx) > reach || Math.abs(dy) > reach) return 0;
            float dead = reach * .18f;
            int result = 0;
            if (dx < -dead) result |= NativeBridge.LEFT;
            if (dx > dead) result |= NativeBridge.RIGHT;
            if (dy < -dead) result |= NativeBridge.UP;
            if (dy > dead) result |= NativeBridge.DOWN;
            return result;
        }

        @Override protected void onDraw(Canvas canvas) {
            paint();
            float arm = getWidth() / 3f, inset = dp(1), corner = dp(8);
            bounds.set(arm, inset, getWidth() - arm, getHeight() - inset);
            canvas.drawRoundRect(bounds, corner, corner, fill);
            canvas.drawRoundRect(bounds, corner, corner, line);
            bounds.set(inset, getHeight() / 3f, getWidth() - inset, getHeight() * 2f / 3f);
            canvas.drawRoundRect(bounds, corner, corner, fill);
            canvas.drawRoundRect(bounds, corner, corner, line);
            fill.setShader(null);
            fill.setColor(0xff0f1019);
            canvas.drawCircle(getWidth() / 2f, getHeight() / 2f, arm * .38f, fill);
            canvas.drawCircle(getWidth() / 2f, getHeight() / 2f, arm * .36f, line);
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float offset = Math.min(getWidth(), getHeight()) * .36f, half = arm * .11f;
            line.setColor(ACCENT);
            line.setStrokeWidth(dp(2.3f));
            canvas.drawLine(cx - half, cy - offset, cx + half, cy - offset, line);
            canvas.drawLine(cx - half, cy + offset, cx + half, cy + offset, line);
            canvas.drawLine(cx - offset, cy - half, cx - offset, cy + half, line);
            canvas.drawLine(cx + offset, cy - half, cx + offset, cy + half, line);
        }

        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.addAction(new AccessibilityNodeInfo.AccessibilityAction(ACTION_UP, "Haut"));
            info.addAction(new AccessibilityNodeInfo.AccessibilityAction(ACTION_DOWN, "Bas"));
            info.addAction(new AccessibilityNodeInfo.AccessibilityAction(ACTION_LEFT, "Gauche"));
            info.addAction(new AccessibilityNodeInfo.AccessibilityAction(ACTION_RIGHT, "Droite"));
        }

        @Override public boolean performAccessibilityAction(int action, Bundle arguments) {
            if (!isEnabled()) return false;
            int button;
            switch (action) {
                case ACTION_UP: button = NativeBridge.UP; break;
                case ACTION_DOWN: button = NativeBridge.DOWN; break;
                case ACTION_LEFT: button = NativeBridge.LEFT; break;
                case ACTION_RIGHT: button = NativeBridge.RIGHT; break;
                default: return super.performAccessibilityAction(action, arguments);
            }
            accessiblePress(button);
            return true;
        }
    }
}
