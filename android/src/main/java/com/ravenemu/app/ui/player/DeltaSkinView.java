package com.ravenemu.app.ui.player;

import com.ravenemu.app.data.skins.DeltaSkin.Kind;
import com.ravenemu.app.data.skins.DeltaSkinException;
import com.ravenemu.app.data.skins.DeltaSkinPdf;
import com.ravenemu.app.data.skins.DeltaSkins;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A Delta skin in the player, after the beta's DeltaSkinControllerView: the
 * skin's PDF as it is, at the bottom of the area, and only a faint mark over
 * what is pressed; never a RavenEmu button. The page is drawn off the UI
 * thread; until then, or when it fails, the view shows nothing and takes no
 * touch. The listener places the picture in the game's area. UI thread. */
final class DeltaSkinView extends View {
    interface Listener {
        void onButtons(int mask);
        void onMenu();
        /** The skin is drawn: the picture goes in the layout's game area. */
        void onReady(DeltaSkinLayout layout);
        void onError(DeltaSkinException.Code code);
    }

    /** Draws a page; the platform's PdfRenderer, replaced in tests. */
    interface Renderer {
        Bitmap render(java.io.File pdf, String sha256, Kind kind, int width, int height) throws DeltaSkinException;
    }

    static Renderer renderer = DeltaSkinPdf::render;
    static final ExecutorService RENDER = Executors.newSingleThreadExecutor();
    private static final long FADE_MILLIS = 90;

    private final Listener listener;
    private final DeltaSkinInput.Fingers fingers;
    private final Paint feedbackFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint feedbackLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF destination = new RectF(), mark = new RectF();
    private DeltaSkins.Configuration configuration;
    private boolean haptics;
    private Kind kind;
    private DeltaSkins.Asset asset;
    private DeltaSkinLayout layout;
    private DeltaSkinInput input;
    private Bitmap page;
    private long generation;
    private boolean ready;
    private Set<DeltaSkinInput.Target> active = Collections.emptySet(), fading = Collections.emptySet();
    private long fadeStarted;
    private final java.util.Map<Integer, DeltaSkinInput.Mapped> pointers = new java.util.HashMap<>();

    DeltaSkinView(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        fingers = new DeltaSkinInput.Fingers(new DeltaSkinInput.Fingers.Listener() {
            @Override public void buttons(int mask) { listener.onButtons(mask); }
            @Override public void menu() { listener.onMenu(); }
            @Override public void visuals(Set<DeltaSkinInput.Target> visuals) { visualsChanged(visuals); }
        });
        feedbackFill.setColor(Color.BLACK);
        feedbackLine.setColor(Color.WHITE);
        feedbackLine.setStyle(Paint.Style.STROKE);
        feedbackLine.setStrokeWidth(getResources().getDisplayMetrics().density);
        setContentDescription("Skin Delta");
        setVisibility(GONE);
    }

    /** The skin to show, or null for none; haptics as the touch controls'. */
    void setConfiguration(DeltaSkins.Configuration value, boolean haptics) {
        this.haptics = haptics;
        if (value == configuration) return;
        releaseAll();
        generation++;
        configuration = value;
        page = null;
        kind = null;
        asset = null;
        layout = null;
        input = null;
        ready = false;
        setVisibility(value == null ? GONE : INVISIBLE);
        if (value != null) update();
    }

    DeltaSkins.Configuration configuration() { return configuration; }

    boolean ready() { return ready; }

    DeltaSkinLayout layout() { return layout; }

    /** Pause, focus loss and detachment never leave a button held. */
    void releaseAll() {
        pointers.clear();
        fingers.releaseAll();
        active = Collections.emptySet();
        fading = Collections.emptySet();
        invalidate();
    }

    @Override public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        if (!enabled) releaseAll();
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (configuration != null) update();
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (!focused) releaseAll();
    }

    @Override protected void onDetachedFromWindow() {
        releaseAll();
        generation++;
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas canvas) {
        if (layout == null || page == null) return;
        destination.set((float) layout.panel.left, (float) layout.panel.top, (float) layout.panel.right,
                (float) layout.panel.bottom);
        canvas.drawBitmap(page, null, destination, null);
        for (DeltaSkinInput.Target target : active) mark(canvas, target, 1f);
        if (!fading.isEmpty()) {
            float alpha = 1f - (SystemClock.uptimeMillis() - fadeStarted) / (float) FADE_MILLIS;
            if (alpha > 0) {
                for (DeltaSkinInput.Target target : fading) mark(canvas, target, alpha);
                postInvalidateOnAnimation();
            } else fading = Collections.emptySet();
        }
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!ready || input == null || !isEnabled()) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int index = event.getActionIndex();
                press(event.getPointerId(index), event.getX(index), event.getY(index));
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                break;
            }
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < event.getPointerCount(); i++) press(event.getPointerId(i), event.getX(i), event.getY(i));
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                int pointer = event.getPointerId(event.getActionIndex());
                pointers.remove(pointer);
                fingers.release(pointer);
                break;
            }
            case MotionEvent.ACTION_CANCEL:
                releaseAll();
                break;
            default:
                break;
        }
        return true;
    }

    private void press(int pointer, float x, float y) {
        DeltaSkinInput.Mapped mapped = input.at(x, y);
        DeltaSkinInput.Mapped previous = pointers.get(pointer);
        if (previous == null) previous = DeltaSkinInput.Mapped.NONE;
        boolean started = (mapped.mask & ~previous.mask) != 0 || (mapped.menu && !previous.menu);
        if (started && haptics) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        if (mapped.empty()) pointers.remove(pointer);
        else pointers.put(pointer, mapped);
        fingers.update(pointer, mapped);
    }

    private void visualsChanged(Set<DeltaSkinInput.Target> visuals) {
        Set<DeltaSkinInput.Target> removed = new HashSet<>(active);
        removed.removeAll(visuals);
        if (!removed.isEmpty()) {
            fading = removed;
            fadeStarted = SystemClock.uptimeMillis();
            postInvalidateOnAnimation();
        }
        active = visuals;
        if (!fading.isEmpty()) {
            Set<DeltaSkinInput.Target> still = new HashSet<>(fading);
            still.removeAll(visuals);
            fading = still;
        }
        invalidate();
    }

    private void mark(Canvas canvas, DeltaSkinInput.Target target, float alpha) {
        feedbackFill.setAlpha(Math.round(48 * alpha));
        feedbackLine.setAlpha(Math.round(90 * alpha));
        mark.set((float) target.box.left, (float) target.box.top, (float) target.box.right, (float) target.box.bottom);
        canvas.drawRoundRect(mark, 4, 4, feedbackFill);
        canvas.drawRoundRect(mark, 4, 4, feedbackLine);
    }

    /** The kind for this size, its layout, then its page, drawn once per size. */
    private void update() {
        DeltaSkins.Configuration config = configuration;
        if (config == null || getWidth() <= 0 || getHeight() <= 0) return;
        Kind chosen = DeltaSkinLayout.select(config.assets.keySet(), getWidth(), getHeight());
        if (chosen == null) {
            fail(DeltaSkinException.Code.PORTRAIT_REPRESENTATION_MISSING);
            return;
        }
        DeltaSkins.Asset chosenAsset = config.assets.get(chosen);
        DeltaSkinLayout next = DeltaSkinLayout.calculate(getWidth(), getHeight(), chosenAsset.representation.mapping,
                config.screenWidth, config.screenHeight);
        if (next == null) {
            fail(DeltaSkinException.Code.INVALID_MAPPING_SIZE);
            return;
        }
        boolean moved = layout == null || !layout.panel.equals(next.panel) || !layout.gameArea.equals(next.gameArea);
        layout = next;
        input = new DeltaSkinInput(config.console, chosenAsset.representation, next.panel);
        int width = Math.max(1, (int) next.panel.width()), height = Math.max(1, (int) next.panel.height());
        if (chosen == kind && chosenAsset == asset && page != null && page.getWidth() == width
                && page.getHeight() == height) {
            if (!ready) becomeReady();
            else if (moved) listener.onReady(next);
            invalidate();
            return;
        }
        releaseAll();
        kind = chosen;
        asset = chosenAsset;
        page = null;
        ready = false;
        setVisibility(INVISIBLE);
        final long token = ++generation;
        final Kind renderKind = chosen;
        RENDER.execute(() -> {
            try {
                Bitmap rendered = renderer.render(chosenAsset.pdf, config.sha256, renderKind, width, height);
                post(() -> {
                    if (token != generation || configuration != config) return;
                    page = rendered;
                    becomeReady();
                    invalidate();
                });
            } catch (DeltaSkinException error) {
                post(() -> { if (token == generation) fail(error.code); });
            } catch (RuntimeException error) {
                post(() -> { if (token == generation) fail(DeltaSkinException.Code.PDF_INVALID); });
            }
        });
    }

    private void becomeReady() {
        if (layout == null) return;
        ready = true;
        setVisibility(VISIBLE);
        listener.onReady(layout);
    }

    private void fail(DeltaSkinException.Code code) {
        releaseAll();
        ready = false;
        setVisibility(GONE);
        listener.onError(code);
    }
}
