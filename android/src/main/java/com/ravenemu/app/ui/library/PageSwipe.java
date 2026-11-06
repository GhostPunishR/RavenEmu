package com.ravenemu.app.ui.library;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;

/** The library's console pages turned with a horizontal swipe, as the beta's
 * HorizontalPager: the page follows the finger; let go past a third of the
 * width, or flung, it leaves for the neighbouring console's page, which comes
 * in from the other side; otherwise it settles back. Without a neighbour on
 * that side, the page only gives a little. A vertical scroll of the grid,
 * once started, keeps the gesture. The console icons still turn the pages.
 * UI thread. */
final class PageSwipe extends FrameLayout {
    /** The pages around the one shown. */
    interface Pages {
        /** Whether there is a page in that direction: +1 the next, -1 the previous. */
        boolean has(int direction);
        /** Shows the page in that direction (while the content is off screen). */
        void turn(int direction);
    }

    static final long TURN_MILLIS = 180, SETTLE_MILLIS = 160;
    /** Without a neighbour, the page follows a third of the finger. */
    private static final float EDGE_RESISTANCE = 1f / 3f;

    private final Pages pages;
    private final int slop, minimumFling;
    private VelocityTracker velocity;
    private float downX, downY, dragStartX;
    /** A touch that began here (not during a turn) is followed. */
    private boolean tracking;
    private boolean dragging;
    private ValueAnimator motion;

    PageSwipe(Context context, Pages pages) {
        super(context);
        this.pages = pages;
        ViewConfiguration configuration = ViewConfiguration.get(context);
        slop = configuration.getScaledTouchSlop();
        minimumFling = configuration.getScaledMinimumFlingVelocity() * 4;
    }

    private View content() { return getChildCount() > 0 ? getChildAt(0) : null; }

    /** While a page turns or settles, touches wait for it. */
    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        if (motion != null) return true;
        track(event);
        return dragging;
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (motion != null) return true;
        track(event);
        // Kept from the first touch, so that a swipe over an empty page turns it too.
        return true;
    }

    private void track(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                tracking = true;
                dragging = false;
                if (velocity != null) velocity.recycle();
                velocity = VelocityTracker.obtain();
                velocity.addMovement(event);
                break;
            case MotionEvent.ACTION_MOVE:
                if (!tracking) break;
                if (velocity != null) velocity.addMovement(event);
                float dx = event.getX() - downX, dy = event.getY() - downY;
                // Mostly sideways: the pages; otherwise the grid scrolls.
                if (!dragging && Math.abs(dx) > slop && Math.abs(dx) > 1.5f * Math.abs(dy)) {
                    dragging = true;
                    dragStartX = event.getX();
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                }
                if (dragging) follow(event.getX() - dragStartX);
                break;
            case MotionEvent.ACTION_UP:
                if (velocity != null) velocity.addMovement(event);
                if (dragging) release(event.getX() - dragStartX);
                reset();
                break;
            case MotionEvent.ACTION_CANCEL:
                if (dragging) settle();
                reset();
                break;
            default:
                break;
        }
    }

    private void reset() {
        tracking = false;
        dragging = false;
        if (velocity != null) {
            velocity.recycle();
            velocity = null;
        }
    }

    /** Swiping left (a negative offset) brings the next page. */
    private static int direction(float offset) { return offset < 0 ? 1 : -1; }

    private void follow(float offset) {
        View content = content();
        if (content == null) return;
        content.setTranslationX(pages.has(direction(offset)) ? offset : offset * EDGE_RESISTANCE);
    }

    private void release(float offset) {
        int direction = direction(offset);
        float speed = 0;
        if (velocity != null) {
            velocity.computeCurrentVelocity(1000);
            speed = velocity.getXVelocity();
        }
        boolean far = Math.abs(offset) > getWidth() / 3f;
        boolean flung = Math.abs(speed) > minimumFling && Math.signum(speed) == Math.signum(offset);
        if (offset != 0 && pages.has(direction) && (far || flung)) turn(direction);
        else settle();
    }

    /** Out on one side, the neighbouring page in from the other. */
    private void turn(int direction) {
        final View content = content();
        if (content == null) return;
        final float width = getWidth();
        animate(content, -direction * width, TURN_MILLIS, () -> {
            pages.turn(direction);
            content.setTranslationX(direction * width);
            animate(content, 0f, TURN_MILLIS, null);
        });
    }

    private void settle() {
        View content = content();
        if (content == null || content.getTranslationX() == 0f) return;
        animate(content, 0f, SETTLE_MILLIS, null);
    }

    private void animate(View content, float to, long millis, Runnable done) {
        if (motion != null) motion.cancel();
        ValueAnimator next = ValueAnimator.ofFloat(content.getTranslationX(), to).setDuration(millis);
        next.setInterpolator(new DecelerateInterpolator());
        next.addUpdateListener(animation -> content.setTranslationX((Float) animation.getAnimatedValue()));
        next.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;
            @Override public void onAnimationCancel(Animator animation) { cancelled = true; }
            @Override public void onAnimationEnd(Animator animation) {
                if (motion == next) motion = null;
                content.setTranslationX(cancelled ? content.getTranslationX() : to);
                if (!cancelled && done != null) done.run();
            }
        });
        motion = next;
        next.start();
    }

    @Override protected void onDetachedFromWindow() {
        if (motion != null) motion.cancel();
        motion = null;
        View content = content();
        if (content != null) content.setTranslationX(0f);
        reset();
        super.onDetachedFromWindow();
    }
}
