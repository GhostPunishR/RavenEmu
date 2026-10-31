package com.ravenemu.app.ui.player;
import com.ravenemu.app.bridge.NativeBridge;
import com.ravenemu.app.settings.ControlLayout;
import com.ravenemu.app.settings.ControlSettings;
import com.ravenemu.app.ui.common.RavenIcon;
import com.ravenemu.app.ui.library.HomeActivity;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.CornerPathEffect;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.Shader;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseArray;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * Java counterpart of RavenEmu's touch controls: a sliding diagonal D-pad,
 * staggered A/B, and separate Select/Start. No emulation or JNI runs here.
 * Each child owns its pointers; the listener receives their combined mask.
 * Settings → Interface sizes the D-pad and A/B, spaces the rows (portrait),
 * picks the style and the haptics, as the beta's GbTouchControls does.
 * Each control can also be moved: an offset from its default place, kept
 * inside this view, and dragged while editing.
 *
 * Drawn in the app's palette (the owner's redesign, not the beta's look):
 * one dark face with an accent outline, labels and arrows in the accent, the
 * pressed part filled with it. In portrait a Menu button sits at the top
 * centre, between L and R on the GBA, never between Select and Start; it
 * opens the game menu and is no console button.
 */
final class GameControlsView extends ViewGroup {
    interface Listener { void onButtonsChanged(int mask); }

    private static final int ACCENT = HomeActivity.ACCENT;
    /** The label on a pressed button. */
    private static final int INK = HomeActivity.BACKGROUND;
    private static final int EDGE = 0x99a78bfa;
    private static final int FACE_TOP = 0xff2b2d40, FACE_BOTTOM = 0xff191b28;
    private static final int CONTRAST_TOP = 0xff474a63, CONTRAST_BOTTOM = 0xff2d2f42;
    private static final int PRESSED_TOP = 0xffbca8ff, PRESSED_BOTTOM = 0xff8d6ff0;
    private static final long ACCESSIBLE_PRESS_MILLIS = 100L;
    /** Round A/B, the small Select/Start pills, the L/R shoulders. */
    private static final int ROUND = 0, PILL = 1, SHOULDER = 2;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SparseArray<Runnable> accessibleReleases = new SparseArray<>();
    private final Dpad dpad;
    private final Control a, b, select, start, leftShoulder, rightShoulder;
    private final MenuControl menu;
    private Runnable menuListener;
    private boolean shoulders;
    private int accessibleMask;
    private int reportedMask;
    private ControlSettings settings = ControlSettings.DEFAULTS;
    private boolean editing;

    GameControlsView(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        setMotionEventSplittingEnabled(true);
        setClipChildren(false);
        setClipToPadding(false);
        setBackgroundColor(compact() ? Color.TRANSPARENT : Color.BLACK);
        // In ControlLayout.CONTROLS order.
        dpad = new Dpad(context);
        a = new Control(context, 1, "A", NativeBridge.A, ROUND);
        b = new Control(context, 2, "B", NativeBridge.B, ROUND);
        select = new Control(context, 3, "SELECT", NativeBridge.SELECT, PILL);
        start = new Control(context, 4, "START", NativeBridge.START, PILL);
        leftShoulder = new Control(context, 5, "L", NativeBridge.L, SHOULDER);
        rightShoulder = new Control(context, 6, "R", NativeBridge.R, SHOULDER);
        menu = new MenuControl(context);
        addView(dpad);
        addView(a);
        addView(b);
        addView(select);
        addView(start);
        addView(leftShoulder);
        addView(rightShoulder);
        // Last: on top of the D-pad's empty corner on a narrow screen.
        addView(menu);
        leftShoulder.setVisibility(GONE);
        rightShoulder.setVisibility(GONE);
        menu.setVisibility(compact() ? GONE : VISIBLE);
    }

    /** What the Menu button opens; landscape keeps the bar's ⋮ only. */
    void setMenuListener(Runnable listener) { menuListener = listener; }

    void setShoulders(boolean value) {
        if (shoulders == value) return;
        releaseAll();
        shoulders = value;
        leftShoulder.setVisibility(value ? VISIBLE : GONE);
        rightShoulder.setVisibility(value ? VISIBLE : GONE);
        requestLayout();
    }

    /** A new size or spacing lays the controls out again, releasing them. */
    void setSettings(ControlSettings next) {
        if (next == null) next = ControlSettings.DEFAULTS;
        boolean moved = next.scale != settings.scale || next.gap != settings.gap;
        settings = next;
        if (moved) {
            releaseAll();
            requestLayout();
        }
        for (int i = 0; i < getChildCount(); i++) getChildAt(i).invalidate();
    }

    /** The places the player chose for this orientation. */
    void setControlLayout(ControlLayout layout) {
        if (layout == null) layout = ControlLayout.DEFAULT;
        if (layout.equals(controlLayout())) return;
        releaseAll();
        for (int i = 0; i < getChildCount(); i++) {
            TouchControl child = (TouchControl) getChildAt(i);
            child.dx = layout.dx(child.index);
            child.dy = layout.dy(child.index);
        }
        requestLayout();
    }

    /** Where each control is now, as the layout pass kept it inside the view. */
    ControlLayout controlLayout() {
        ControlLayout layout = ControlLayout.DEFAULT;
        for (int i = 0; i < getChildCount(); i++) {
            TouchControl child = (TouchControl) getChildAt(i);
            layout = layout.with(child.index, child.dx, child.dy);
        }
        return layout;
    }

    /** While editing, a drag moves a control instead of pressing it. */
    void setEditing(boolean value) {
        if (editing == value) return;
        releaseAll();
        editing = value;
        for (int i = 0; i < getChildCount(); i++) {
            TouchControl child = (TouchControl) getChildAt(i);
            child.dragging = false;
            child.invalidate();
        }
    }

    boolean isEditing() { return editing; }

    /** Landscape lays the controls over the picture, with its own layout. */
    boolean isLandscape() { return compact(); }

    /** Menu, focus loss and detachment must never leave a touch button held. */
    void releaseAll() {
        for (int i = 0; i < accessibleReleases.size(); i++)
            main.removeCallbacks(accessibleReleases.valueAt(i));
        accessibleReleases.clear();
        accessibleMask = 0;
        for (int i = 0; i < getChildCount(); i++) {
            TouchControl child = (TouchControl) getChildAt(i);
            child.clear();
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

    private float size() { return settings.scale / 100f; }

    /** The D-pad: half the width, at most 132 dp, then the chosen size. */
    private float cluster(float width) {
        float base = Math.max(0f, Math.min(132f, (width - 32f) / 2f));
        return Math.max(60f, Math.min(base * size(), Math.max(60f, width - 104f)));
    }

    /** A and B: 60 dp at 100 %, between 48 and 69 dp. */
    private float diameter() {
        return Math.max(48, Math.min(69, Math.round(60f * size())));
    }

    private float desiredHeight(float width) {
        if (compact()) return 72f + 80f * size();
        return (shoulders ? 40f + settings.gap : 0f) + cluster(width) + settings.gap + 40f + 16f;
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
            // A fixed overlay beside the picture: only the D-pad and A/B grow.
            float pad = 80 * size(), button = 48 * size();
            place(dpad, 16, offset + 32 * scale, pad, pad * scale, layout);
            place(a, width - 16 - button, offset + 32 * scale, button, button * scale, layout);
            place(b, width - 56 - button, offset + (32 + button) * scale, button, button * scale, layout);
            place(select, width / 2 - 101, offset + (32 + pad) * scale, 84, 40 * scale, layout);
            place(start, width / 2 + 17, offset + (32 + pad) * scale, 84, 40 * scale, layout);
            place(leftShoulder, 16, offset, 80, 28 * scale, layout);
            place(rightShoulder, width - 96, offset, 80, 28 * scale, layout);
            // Hidden: the top centre is the picture's.
            place(menu, (width - 56) / 2, offset, 56, 40 * scale, layout);
        } else {
            float size = cluster(width), button = diameter(), rows = settings.gap;
            float row = shoulders ? 40f + rows : 0f;
            float actionWidth = Math.min(144f, Math.max(60f, width - 44f - size));
            float gap = Math.min(50f, Math.max(8f, width - 168f));
            place(dpad, 16, offset + row * scale, size, size * scale, layout);
            place(a, width - 28 - button, offset + row * scale, button, button * scale, layout);
            place(b, width - 28 - actionWidth, offset + (row + size - button) * scale, button, button * scale, layout);
            place(select, (width - 168 - gap) / 2, offset + (row + size + rows) * scale, 84, 40 * scale, layout);
            place(start, (width + gap) / 2, offset + (row + size + rows) * scale, 84, 40 * scale, layout);
            place(leftShoulder, 16, offset, 96, 40 * scale, layout);
            place(rightShoulder, width - 112, offset, 96, 40 * scale, layout);
            // The owner's place: top centre, in the shoulders' row on the GBA.
            place(menu, (width - 56) / 2, offset, 56, 40 * scale, layout);
        }
    }

    /** At its default place moved by the player's offset, never outside this view. */
    private void place(TouchControl child, float x, float y, float width, float height, boolean layout) {
        int w = dp(width), h = dp(height);
        if (!layout) {
            child.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
            return;
        }
        float density = getResources().getDisplayMetrics().density;
        float maxX = Math.max(0f, getWidth() / density - width), maxY = Math.max(0f, getHeight() / density - height);
        float left = Math.max(0f, Math.min(maxX, x + child.dx));
        float top = Math.max(0f, Math.min(maxY, y + child.dy));
        // While editing, the offset is the place the control could take.
        if (editing) { child.dx = left - x; child.dy = top - y; }
        child.layout(dp(left), dp(top), dp(left) + w, dp(top) + h);
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        if (oldw != 0 || oldh != 0) releaseAll();
    }

    @Override protected void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        releaseAll();
        setBackgroundColor(compact() ? Color.TRANSPARENT : Color.BLACK);
        menu.setVisibility(compact() ? GONE : VISIBLE);
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
        // Custom accessibility actions, separate from the platform's actions.
        static final int MOVE_LEFT = 0x02000011, MOVE_RIGHT = 0x02000012;
        static final int MOVE_UP = 0x02000013, MOVE_DOWN = 0x02000014;
        static final float STEP = 8f;
        final int index;
        int mask;
        boolean tracking, dragging;
        /** The player's offset from the default place, in dp. */
        float dx, dy;
        private float dragX, dragY, startDx, startDy;
        final Paint frame = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        LinearGradient normal, contrast, pressed;
        TouchControl(Context context, int index, String description) {
            super(context);
            this.index = index;
            line.setStyle(Paint.Style.STROKE);
            frame.setStyle(Paint.Style.STROKE);
            frame.setColor(ACCENT);
            frame.setStrokeWidth(dp(1.5f));
            frame.setPathEffect(new DashPathEffect(new float[]{dp(6), dp(4)}, 0));
            setContentDescription(description);
            setFocusable(true);
            setClickable(true);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        }

        abstract int at(float x, float y);
        boolean acceptsDown(float x, float y) { return at(x, y) != 0; }

        /** Nothing held, nothing tracked: the next gesture starts afresh. */
        void clear() {
            mask = 0;
            tracking = false;
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (editing) return drag(event);
            if (!isEnabled()) return false;
            return press(event);
        }

        /** Console buttons: every finger on this control makes its mask. */
        boolean press(MotionEvent event) {
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
            // As in the beta: a short vibration when a press starts.
            if (mask == 0 && next != 0 && settings.haptics)
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
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

        /** The finger moves the control. Its position is read in the parent,
         * which stays put while the control follows the finger. */
        private boolean drag(MotionEvent event) {
            float density = getResources().getDisplayMetrics().density;
            float x = getLeft() + event.getX(), y = getTop() + event.getY();
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    dragging = true;
                    dragX = x;
                    dragY = y;
                    startDx = dx;
                    startDy = dy;
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    invalidate();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!dragging) return false;
                    moveTo(startDx + (x - dragX) / density, startDy + (y - dragY) / density);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    dragging = false;
                    invalidate();
                    return true;
                default:
                    return dragging;
            }
        }

        void moveTo(float x, float y) {
            dx = x;
            dy = y;
            GameControlsView.this.requestLayout();
        }

        void paintEditing(Canvas canvas) {
            if (!editing) return;
            frame.setAlpha(dragging ? 255 : 170);
            float inset = frame.getStrokeWidth();
            canvas.drawRect(inset, inset, getWidth() - inset, getHeight() - inset, frame);
        }

        boolean moveBy(int action) {
            switch (action) {
                case MOVE_LEFT: moveTo(dx - STEP, dy); return true;
                case MOVE_RIGHT: moveTo(dx + STEP, dy); return true;
                case MOVE_UP: moveTo(dx, dy - STEP); return true;
                case MOVE_DOWN: moveTo(dx, dy + STEP); return true;
                default: return false;
            }
        }

        @Override public boolean performAccessibilityAction(int action, Bundle arguments) {
            if (editing && moveBy(action)) return true;
            return super.performAccessibilityAction(action, arguments);
        }

        @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            normal = new LinearGradient(0, 0, 0, Math.max(1, h), FACE_TOP, FACE_BOTTOM, Shader.TileMode.CLAMP);
            contrast = new LinearGradient(0, 0, 0, Math.max(1, h), CONTRAST_TOP, CONTRAST_BOTTOM, Shader.TileMode.CLAMP);
            pressed = new LinearGradient(0, 0, 0, Math.max(1, h), PRESSED_TOP, PRESSED_BOTTOM, Shader.TileMode.CLAMP);
        }

        /** The face and its outline; high contrast lightens the face and
         * outlines it in white, as the beta's style does. */
        void paint(boolean down) {
            boolean contrasted = settings.highContrast();
            fill.setShader(down ? pressed : contrasted ? contrast : normal);
            line.setColor(down ? PRESSED_TOP : contrasted ? Color.WHITE : EDGE);
            line.setStrokeWidth(dp(down ? 2f : 1.5f));
        }

        /** Labels, arrows and glyphs: the accent, dark on a pressed face. */
        int ink(boolean down) { return down ? INK : ACCENT; }

        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setClassName("android.widget.Button");
            if (!editing) return;
            info.addAction(new AccessibilityNodeInfo.AccessibilityAction(MOVE_LEFT, "Déplacer à gauche"));
            info.addAction(new AccessibilityNodeInfo.AccessibilityAction(MOVE_RIGHT, "Déplacer à droite"));
            info.addAction(new AccessibilityNodeInfo.AccessibilityAction(MOVE_UP, "Déplacer vers le haut"));
            info.addAction(new AccessibilityNodeInfo.AccessibilityAction(MOVE_DOWN, "Déplacer vers le bas"));
        }
    }

    private final class Control extends TouchControl {
        final String label;
        final int button;
        final int kind;
        final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF bounds = new RectF();

        Control(Context context, int index, String label, int button, int kind) {
            super(context, index, label);
            this.label = label;
            this.button = button;
            this.kind = kind;
            text.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            text.setTextAlign(Paint.Align.CENTER);
            float scaled = getResources().getDisplayMetrics().scaledDensity;
            text.setTextSize((kind == ROUND ? 22 : kind == PILL ? 11 : 15) * scaled);
            if (kind == PILL) text.setLetterSpacing(.08f);
        }

        @Override int at(float x, float y) {
            if (x < 0 || y < 0 || x >= getWidth() || y >= getHeight()) return 0;
            if (kind == ROUND) {
                float dx = (x - getWidth() / 2f) / (getWidth() / 2f);
                float dy = (y - getHeight() / 2f) / (getHeight() / 2f);
                if (dx * dx + dy * dy > 1f) return 0;
            }
            return button;
        }

        @Override protected void onDraw(Canvas canvas) {
            boolean down = mask != 0;
            paint(down);
            float edge = line.getStrokeWidth() / 2f + dp(.5f);
            float width = getWidth(), height = getHeight();
            if (kind == PILL) {
                // A slim pill; the whole box still answers the finger.
                float face = Math.min(height - 2 * edge, height * .72f);
                bounds.set(edge + dp(3), (height - face) / 2f, width - edge - dp(3), (height + face) / 2f);
            } else bounds.set(edge, edge, width - edge, height - edge);
            float radius = Math.min(bounds.width(), bounds.height()) / 2f;
            canvas.drawRoundRect(bounds, radius, radius, fill);
            canvas.drawRoundRect(bounds, radius, radius, line);
            text.setColor(ink(down));
            canvas.drawText(label, bounds.centerX(), bounds.centerY() - (text.ascent() + text.descent()) / 2f, text);
            paintEditing(canvas);
        }

        @Override public boolean performClick() {
            if (editing || !isEnabled() || getVisibility() != VISIBLE) return false;
            accessiblePress(button);
            super.performClick();
            return true;
        }
    }

    /** Opens the game menu: a tap, released on it, never a console button. */
    private final class MenuControl extends TouchControl {
        final RectF bounds = new RectF();
        final RavenIcon glyph;
        boolean held;

        MenuControl(Context context) {
            super(context, 7, "Ouvrir le menu du jeu");
            glyph = new RavenIcon(RavenIcon.Glyph.MENU, ACCENT, dp(20));
        }

        @Override int at(float x, float y) {
            return x >= 0 && y >= 0 && x < getWidth() && y < getHeight() ? 1 : 0;
        }

        @Override void clear() {
            super.clear();
            held = false;
        }

        @Override boolean press(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                if (at(event.getX(), event.getY()) == 0) return false;
                tracking = true;
                if (settings.haptics) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
            } else if (!tracking) return false;
            boolean inside = at(event.getX(), event.getY()) != 0;
            boolean open = action == MotionEvent.ACTION_UP && held && inside;
            boolean next = inside && action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL;
            if (next != held) {
                held = next;
                invalidate();
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) tracking = false;
            if (open) performClick();
            return true;
        }

        @Override protected void onDraw(Canvas canvas) {
            paint(held);
            float edge = line.getStrokeWidth() / 2f + dp(.5f);
            float width = getWidth(), height = getHeight();
            float face = Math.min(height - 2 * edge, height * .72f);
            bounds.set(edge + dp(4), (height - face) / 2f, width - edge - dp(4), (height + face) / 2f);
            float radius = bounds.height() / 2f;
            canvas.drawRoundRect(bounds, radius, radius, fill);
            canvas.drawRoundRect(bounds, radius, radius, line);
            int size = Math.round(Math.min(dp(20), face * .75f));
            glyph.setTint(ink(held));
            glyph.setBounds(Math.round(bounds.centerX() - size / 2f), Math.round(bounds.centerY() - size / 2f),
                    Math.round(bounds.centerX() + size / 2f), Math.round(bounds.centerY() + size / 2f));
            glyph.draw(canvas);
            paintEditing(canvas);
        }

        @Override public boolean performClick() {
            if (editing || !isEnabled() || getVisibility() != VISIBLE) return false;
            super.performClick();
            if (menuListener != null) menuListener.run();
            return true;
        }
    }

    private final class Dpad extends TouchControl {
        // Custom accessibility actions, separate from the platform's actions.
        private static final int ACTION_UP = 0x02000001, ACTION_DOWN = 0x02000002;
        private static final int ACTION_LEFT = 0x02000003, ACTION_RIGHT = 0x02000004;
        private final int[] directions = {NativeBridge.UP, NativeBridge.DOWN, NativeBridge.LEFT, NativeBridge.RIGHT};
        final Path cross = new Path();
        final Path[] arrows = {new Path(), new Path(), new Path(), new Path()};
        final RectF[] lit = {new RectF(), new RectF(), new RectF(), new RectF()};
        final Paint detail = new Paint(Paint.ANTI_ALIAS_FLAG);
        float cx, cy, hub;

        Dpad(Context context) {
            super(context, 0, "Croix directionnelle");
            setClickable(false);
            detail.setPathEffect(new CornerPathEffect(dp(1.5f)));
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

        @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            // One cross, one outline; an arrow in the middle of each arm.
            float size = Math.min(w, h), arm = size / 3f, edge = dp(1.25f);
            cx = w / 2f;
            cy = h / 2f;
            float corner = arm * .22f, reach = size / 2f - edge;
            cross.reset();
            Path across = new Path();
            cross.addRoundRect(cx - arm / 2f, cy - reach, cx + arm / 2f, cy + reach, corner, corner, Path.Direction.CW);
            across.addRoundRect(cx - reach, cy - arm / 2f, cx + reach, cy + arm / 2f, corner, corner, Path.Direction.CW);
            cross.op(across, Path.Op.UNION);
            float half = arm * .2f, depth = arm * .14f;
            for (int i = 0; i < 4; i++) {
                // Up, down, left, right: the tip outward.
                float sx = i == 2 ? -1 : i == 3 ? 1 : 0, sy = i == 0 ? -1 : i == 1 ? 1 : 0;
                float x = cx + sx * arm, y = cy + sy * arm;
                arrows[i].reset();
                arrows[i].moveTo(x + sx * depth, y + sy * depth);
                arrows[i].lineTo(x - sx * depth + sy * half, y - sy * depth + sx * half);
                arrows[i].lineTo(x - sx * depth - sy * half, y - sy * depth - sx * half);
                arrows[i].close();
            }
            hub = arm * .2f;
            lit[0].set(cx - arm / 2f, 0, cx + arm / 2f, cy - arm / 2f);
            lit[1].set(cx - arm / 2f, cy + arm / 2f, cx + arm / 2f, h);
            lit[2].set(0, cy - arm / 2f, cx - arm / 2f, cy + arm / 2f);
            lit[3].set(cx + arm / 2f, cy - arm / 2f, w, cy + arm / 2f);
        }

        @Override protected void onDraw(Canvas canvas) {
            paint(false);
            canvas.drawPath(cross, fill);
            if (mask != 0) {
                // Only the pressed arms take the accent.
                canvas.save();
                canvas.clipPath(cross);
                fill.setShader(pressed);
                for (int i = 0; i < 4; i++) if ((mask & directions[i]) != 0) canvas.drawRect(lit[i], fill);
                canvas.restore();
            }
            line.setColor(mask != 0 ? PRESSED_TOP : settings.highContrast() ? Color.WHITE : EDGE);
            canvas.drawPath(cross, line);
            detail.setStyle(Paint.Style.STROKE);
            detail.setStrokeWidth(dp(1.25f));
            detail.setColor(settings.highContrast() ? 0x99ffffff : 0x66a78bfa);
            canvas.drawCircle(cx, cy, hub, detail);
            detail.setStyle(Paint.Style.FILL);
            for (int i = 0; i < 4; i++) {
                detail.setColor(ink((mask & directions[i]) != 0));
                canvas.drawPath(arrows[i], detail);
            }
            paintEditing(canvas);
        }

        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            if (editing) return;
            info.addAction(new AccessibilityNodeInfo.AccessibilityAction(ACTION_UP, "Haut"));
            info.addAction(new AccessibilityNodeInfo.AccessibilityAction(ACTION_DOWN, "Bas"));
            info.addAction(new AccessibilityNodeInfo.AccessibilityAction(ACTION_LEFT, "Gauche"));
            info.addAction(new AccessibilityNodeInfo.AccessibilityAction(ACTION_RIGHT, "Droite"));
        }

        @Override public boolean performAccessibilityAction(int action, Bundle arguments) {
            if (editing) return super.performAccessibilityAction(action, arguments);
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
