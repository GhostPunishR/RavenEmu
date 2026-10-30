package com.ravenemu.app.ui.player;
import com.ravenemu.app.bridge.NativeBridge;
import com.ravenemu.app.settings.ControlLayout;
import com.ravenemu.app.settings.ControlSettings;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
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
    private final MenuButton menuButton;
    private final Runnable onMenu;
    private boolean shoulders;
    private int accessibleMask;
    private int reportedMask;
    private ControlSettings settings = ControlSettings.DEFAULTS;
    private boolean editing;

    GameControlsView(Context context, Listener listener) {
        this(context, listener, null);
    }

    GameControlsView(Context context, Listener listener, Runnable onMenu) {
        super(context);
        this.listener = listener;
        this.onMenu = onMenu;
        setMotionEventSplittingEnabled(true);
        setClipChildren(false);
        setClipToPadding(false);
        setBackgroundColor(compact() ? Color.TRANSPARENT : Color.BLACK);
        // In ControlLayout.CONTROLS order.
        dpad = new Dpad(context);
        a = new Control(context, 1, "A", NativeBridge.A, false);
        b = new Control(context, 2, "B", NativeBridge.B, false);
        select = new Control(context, 3, "SELECT", NativeBridge.SELECT, true);
        start = new Control(context, 4, "START", NativeBridge.START, true);
        leftShoulder = new Control(context, 5, "L", NativeBridge.L, true);
        rightShoulder = new Control(context, 6, "R", NativeBridge.R, true);
        menuButton = new MenuButton(context);
        addView(dpad);
        addView(a);
        addView(b);
        addView(select);
        addView(start);
        addView(leftShoulder);
        addView(rightShoulder);
        addView(menuButton);
        leftShoulder.setVisibility(GONE);
        rightShoulder.setVisibility(GONE);
        menuButton.setVisibility(GONE);
    }

    void setShoulders(boolean value) {
        if (shoulders == value) return;
        releaseAll();
        shoulders = value;
        leftShoulder.setVisibility(value ? VISIBLE : GONE);
        rightShoulder.setVisibility(value ? VISIBLE : GONE);
        menuButton.setVisibility(value && !compact() && onMenu != null ? VISIBLE : GONE);
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
            if (!(getChildAt(i) instanceof TouchControl)) continue;
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
            if (!(getChildAt(i) instanceof TouchControl)) continue;
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
        menuButton.setEnabled(isEnabled() && !editing);
        for (int i = 0; i < getChildCount(); i++) {
            if (!(getChildAt(i) instanceof TouchControl)) continue;
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
            if (!(getChildAt(i) instanceof TouchControl)) continue;
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
            if (getChildAt(i) instanceof TouchControl)
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
            place(menuButton, (width - 48) / 2, offset, 48, 28 * scale, layout);
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
            // Portrait GBA: L, the game menu and R share the upper row.
            place(menuButton, (width - 48) / 2, offset, 48, 40 * scale, layout);
        }
    }

    /** At its default place moved by the player's offset, never outside this view. */
    private void place(View child, float x, float y, float width, float height, boolean layout) {
        int w = dp(width), h = dp(height);
        if (!layout) {
            child.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
            return;
        }
        float density = getResources().getDisplayMetrics().density;
        float maxX = Math.max(0f, getWidth() / density - width), maxY = Math.max(0f, getHeight() / density - height);
        TouchControl movable = child instanceof TouchControl ? (TouchControl) child : null;
        float left = Math.max(0f, Math.min(maxX, x + (movable == null ? 0f : movable.dx)));
        float top = Math.max(0f, Math.min(maxY, y + (movable == null ? 0f : movable.dy)));
        // The menu is fixed; stored offsets continue to cover only game buttons.
        if (editing && movable != null) { movable.dx = left - x; movable.dy = top - y; }
        child.layout(dp(left), dp(top), dp(left) + w, dp(top) + h);
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        if (oldw != 0 || oldh != 0) releaseAll();
    }

    @Override protected void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        releaseAll();
        setBackgroundColor(compact() ? Color.TRANSPARENT : Color.BLACK);
        menuButton.setVisibility(shoulders && !compact() && onMenu != null ? VISIBLE : GONE);
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
        menuButton.setEnabled(enabled && !editing);
    }

    /** Modern game menu between L and R; it never sends an emulator input bit. */
    private final class MenuButton extends View {
        private final Paint background = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);

        MenuButton(Context context) {
            super(context);
            setContentDescription("Menu du jeu");
            setFocusable(true);
            setOnClickListener(view -> {
                if (!editing && onMenu != null) onMenu.run();
            });
        }

        @Override protected void drawableStateChanged() {
            super.drawableStateChanged();
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            boolean pressed = isPressed();
            boolean contrast = settings.highContrast();
            background.setStyle(Paint.Style.FILL);
            background.setShader(new LinearGradient(0, 0, 0, Math.max(1, getHeight()),
                    pressed ? 0xff735bb3 : contrast ? 0xff55546d : 0xff35334a,
                    pressed ? 0xff33284f : contrast ? 0xff22232e : 0xff161620,
                    Shader.TileMode.CLAMP));
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float radius = Math.min(cx, cy) - dp(2);
            canvas.drawCircle(cx, cy, radius, background);
            outline.setShader(null);
            outline.setStyle(Paint.Style.STROKE);
            outline.setColor(pressed ? ACCENT : contrast ? Color.WHITE : BORDER);
            outline.setStrokeWidth(dp(1.5f));
            canvas.drawCircle(cx, cy, radius, outline);
            outline.setColor(pressed ? Color.WHITE : ACCENT);
            outline.setStrokeWidth(dp(2.2f));
            for (int i = -1; i <= 1; i++)
                canvas.drawLine(cx - dp(7), cy + i * dp(5), cx + dp(7), cy + i * dp(5), outline);
        }
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

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (editing) return drag(event);
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
            normal = new LinearGradient(0, 0, 0, Math.max(1, h), 0xff35334a, 0xff161620, Shader.TileMode.CLAMP);
            contrast = new LinearGradient(0, 0, 0, Math.max(1, h), 0xff55546d, 0xff22232e, Shader.TileMode.CLAMP);
            pressed = new LinearGradient(0, 0, 0, Math.max(1, h), 0xff735bb3, 0xff33284f, Shader.TileMode.CLAMP);
        }

        /** The beta's style: high contrast lightens the buttons and outlines them in white. */
        void paint() {
            boolean contrasted = settings.highContrast();
            fill.setShader(mask != 0 ? pressed : contrasted ? contrast : normal);
            line.setShader(null);
            line.setColor(mask != 0 ? ACCENT : contrasted ? Color.WHITE : BORDER);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(dp(mask == 0 ? 1.5f : 2));
        }

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
        final boolean pill;
        final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF bounds = new RectF();

        Control(Context context, int index, String label, int button, boolean pill) {
            super(context, index, label);
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
            paintEditing(canvas);
        }

        @Override public boolean performClick() {
            if (editing || !isEnabled() || getVisibility() != VISIBLE) return false;
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
            super(context, 0, "Croix directionnelle");
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
            float offset = Math.min(getWidth(), getHeight()) * .36f;
            float arrow = Math.min(getWidth(), getHeight()) * .045f;
            line.setColor(ACCENT);
            line.setStrokeWidth(dp(2.3f));
            // Directional chevrons echo the reference controller, in the existing palette.
            canvas.drawLine(cx - arrow, cy - offset + arrow, cx, cy - offset - arrow, line);
            canvas.drawLine(cx, cy - offset - arrow, cx + arrow, cy - offset + arrow, line);
            canvas.drawLine(cx - arrow, cy + offset - arrow, cx, cy + offset + arrow, line);
            canvas.drawLine(cx, cy + offset + arrow, cx + arrow, cy + offset - arrow, line);
            canvas.drawLine(cx - offset + arrow, cy - arrow, cx - offset - arrow, cy, line);
            canvas.drawLine(cx - offset - arrow, cy, cx - offset + arrow, cy + arrow, line);
            canvas.drawLine(cx + offset - arrow, cy - arrow, cx + offset + arrow, cy, line);
            canvas.drawLine(cx + offset + arrow, cy, cx + offset - arrow, cy + arrow, line);
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
