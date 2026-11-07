package com.ravenemu.app.ui.player;

import com.ravenemu.app.bridge.NativeAchievements;
import com.ravenemu.app.settings.AchievementSettings;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewParent;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import java.util.ArrayDeque;

/** RetroAchievements over the game, as RetroArch shows them, in RavenEmu's
 * own code. One popup at a time (AchievementPopup), eight waiting at most:
 * the game that starts, each unlock, the completed game and the session's
 * notices. Its badge slides in from the edge, then its text unfolds beside
 * it; it stays for Settings' duration, folds back and slides away; then the
 * next, at one of RetroArch's six anchors (Settings → RetroAchievements).
 * Each stage takes RetroArch's MSG_QUEUE_ANIMATION_DURATION, 330 ms,
 * eased out (quadratic). A popup opens once its badge is fetched, 2 s at
 * most, and never changes while shown. In the bottom right corner
 * (AchievementCorner): the progress of an achievement, the challenges and
 * the leaderboard trackers.
 *
 * Both are laid out over the game's picture itself, as the GLES2 renderer
 * draws it (setPicture): fitted to its surface, centred across and pinned
 * to the top, at a whole multiple of its size with Pixels entiers. The
 * overlay may cover more (the space left below the picture, or a whole
 * skin), but the bottom right corner is the picture's. Before a game is
 * loaded, the whole overlay. UI thread; it never takes a touch from the game. */
final class AchievementOverlay extends FrameLayout {
    /** RetroArch's MSG_QUEUE_ANIMATION_DURATION, for each stage. */
    static final int SLIDE_MILLIS = 330, UNFOLD_MILLIS = 330;
    /** A popup waits this long at most for its badge, then opens with the trophy. */
    static final int BADGE_WAIT_MILLIS = 2_000;
    /** RetroArch's CHEEVO_QUEUE_SIZE. */
    static final int MAX_QUEUED = 8;
    private final Handler main = new Handler(Looper.getMainLooper());
    /** The crow's cry, for each unlock and the completed game. */
    private final UnlockCry cry;
    private final ArrayDeque<AchievementNotices.Notice> queue = new ArrayDeque<>();
    private final BadgeCache badges;
    private final AchievementPopup popup;
    private final AchievementCorner corner;
    private AchievementSettings settings = AchievementSettings.DEFAULTS;
    private boolean soundOn = true;
    private int volume = 100;
    private AchievementNotices.Notice showing;
    private Animator running;
    /** The next popup while its badge is fetched; null otherwise. */
    private AchievementNotices.Notice fetching;
    private boolean released;
    /** The view the renderer draws in, the game's frame size and Pixels entiers; null before a game. */
    private View pictureView;
    private int frameWidth, frameHeight;
    private boolean integerScale;
    /** Where the picture is, in this overlay's coordinates; empty for the whole overlay. */
    private final Rect picture = new Rect();
    private final int[] origin = new int[2];
    private final View.OnLayoutChangeListener follow = (view, left, top, right, bottom, oldLeft, oldTop,
            oldRight, oldBottom) -> {
        // Every pass calls it, moved or not: only a move can change the picture.
        if (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom) pictureMoved();
    };
    private final Runnable relayout = this::requestLayout;
    private final Runnable leave = this::leave;
    private final Runnable waited = () -> { if (fetching != null) fetched(fetching); };

    AchievementOverlay(Context context, BadgeCache badges) {
        super(context);
        this.badges = badges;
        this.cry = new UnlockCry(context);
        setClipChildren(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        corner = new AchievementCorner(context, badges);
        addView(corner, new LayoutParams(-1, -1));
        popup = new AchievementPopup(context);
        addView(popup, new LayoutParams(-1, -1));
        popup.setAnchor(false, 0);
    }

    /** The game's picture: `view` is where the renderer draws, `frameWidth` by
     * `frameHeight` the core's frame, `integer` Settings → Vidéo → Pixels
     * entiers. The view and its ancestors are followed, so that a skin, which
     * moves the view, or this overlay going from the picture to the skin,
     * moves the corner with them. */
    void setPicture(View view, int frameWidth, int frameHeight, boolean integer) {
        if (pictureView != view) {
            unfollow();
            pictureView = view;
            for (View step = view; step != null; step = parentOf(step)) step.addOnLayoutChangeListener(follow);
        }
        this.frameWidth = Math.max(0, frameWidth);
        this.frameHeight = Math.max(0, frameHeight);
        this.integerScale = integer;
        requestLayout();
    }

    /** Settings → Vidéo → Pixels entiers, changed during the game. */
    void setIntegerScale(boolean integer) {
        if (integerScale == integer) return;
        integerScale = integer;
        requestLayout();
    }

    /** The picture, in this overlay's coordinates: for tests. */
    Rect pictureRect() { return new Rect(picture); }

    private static View parentOf(View view) {
        ViewParent parent = view.getParent();
        return parent instanceof View ? (View) parent : null;
    }

    private void unfollow() {
        for (View step = pictureView; step != null; step = parentOf(step)) step.removeOnLayoutChangeListener(follow);
    }

    /** Something on the way to the picture moved: lay out again, after this
     * pass, only if the picture is no longer where it was laid out. */
    private void pictureMoved() {
        if (released) return;
        Rect before = new Rect(picture);
        locate(getWidth(), getHeight());
        boolean moved = !before.equals(picture);
        picture.set(before);
        if (moved) {
            main.removeCallbacks(relayout);
            main.post(relayout);
        }
    }

    /** RavenEmu's GLES2 renderer's fit (gles2_renderer.c), for a surface of
     * this size: the whole frame inside it, at a whole multiple of its size
     * with Pixels entiers, centred across, pinned to the top. */
    static Rect fit(int surfaceWidth, int surfaceHeight, int frameWidth, int frameHeight, boolean integer) {
        int w = surfaceWidth, h = surfaceHeight;
        if ((long) surfaceWidth * frameHeight > (long) surfaceHeight * frameWidth)
            w = (int) ((long) h * frameWidth / frameHeight);
        else
            h = (int) ((long) w * frameHeight / frameWidth);
        if (integer) {
            int scale = Math.min(surfaceWidth / frameWidth, surfaceHeight / frameHeight);
            if (scale > 0) {
                w = frameWidth * scale;
                h = frameHeight * scale;
            }
        }
        w = Math.max(1, w);
        h = Math.max(1, h);
        int left = (surfaceWidth - w) / 2;
        return new Rect(left, 0, left + w, h);
    }

    /** Where the picture is in this overlay; false, and the whole overlay, when it is not known. */
    private boolean locate(int width, int height) {
        picture.set(0, 0, width, height);
        View view = pictureView;
        if (view == null || frameWidth == 0 || frameHeight == 0 || view.getWidth() == 0 || view.getHeight() == 0)
            return false;
        // The view's origin, through its ancestors, up to this overlay's parent.
        int x = 0, y = 0;
        View step = view;
        while (step != null && step != getParent()) {
            x += step.getLeft();
            y += step.getTop();
            step = parentOf(step);
        }
        if (step == null) return false;
        origin[0] = x - getLeft();
        origin[1] = y - getTop();
        Rect drawn = fit(view.getWidth(), view.getHeight(), frameWidth, frameHeight, integerScale);
        drawn.offset(origin[0], origin[1]);
        if (!drawn.intersect(0, 0, width, height)) return false;
        picture.set(drawn);
        return true;
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        super.onMeasure(widthSpec, heightSpec);
        locate(getMeasuredWidth(), getMeasuredHeight());
        final int w = MeasureSpec.makeMeasureSpec(picture.width(), MeasureSpec.EXACTLY);
        final int h = MeasureSpec.makeMeasureSpec(picture.height(), MeasureSpec.EXACTLY);
        popup.measure(w, h);
        corner.measure(w, h);
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        locate(right - left, bottom - top);
        popup.layout(picture.left, picture.top, picture.right, picture.bottom);
        corner.layout(picture.left, picture.top, picture.right, picture.bottom);
    }

    /** Settings → RetroAchievements; Settings → Audio for the cry. */
    void setSettings(AchievementSettings settings, boolean soundOn, int volume) {
        this.settings = settings;
        this.soundOn = soundOn;
        this.volume = volume;
        if (!settings.unlockPopups) {
            queue.removeIf(notice -> notice.award);
            if (showing != null && showing.award) leaveNow();
            if (fetching != null && fetching.award) stopFetching();
        }
        if (!settings.progressPopups) corner.hideProgress();
        if (!settings.sound || !soundOn || volume <= 0) cry.stop();
        // A popup on screen keeps its place; the next one takes the new one.
        if (showing == null) popup.setAnchor(settings.fromBottom(), settings.horizontal());
    }

    /** What the C module reported; see AchievementNotices. */
    void onEvent(NativeAchievements.Event event) {
        if (released) return;
        switch (event.kind) {
            case "progress_show":
                if (settings.progressPopups)
                    corner.showProgress(event.id, event.title, event.detail, event.image, settings.progressSeconds);
                return;
            case "progress_hide":
                // The SDK's own two-second timer names no achievement: the
                // corner keeps Settings' duration. A reset or a loaded state does.
                if (!event.id.isEmpty()) corner.hideProgress();
                return;
            case "challenge_show": corner.showChallenge(event.id, event.title, event.image); return;
            case "challenge_hide": corner.hideChallenge(event.id); return;
            case "tracker_show": corner.showTracker(event.id, event.title); return;
            case "tracker_hide": corner.hideTracker(event.id); return;
            case "hardcore_reset": corner.clearIndicators(); break;
            default: break;
        }
        AchievementNotices.Notice notice = AchievementNotices.of(event);
        if (notice == null) return;
        if (notice.award && !settings.unlockPopups) {
            // No popup: the cry alone (a popup cries when it opens).
            if (settings.sound && soundOn) cry.play(volume);
            return;
        }
        if (queue.size() < MAX_QUEUED) queue.add(notice);
        next();
    }

    /** The game closes: nothing is left on screen or queued, nothing heard. */
    void clear() {
        cry.stop();
        queue.clear();
        leaveNow();
        corner.clear();
    }

    /** The player goes to the background: the cry stops with the game's sound. */
    void pauseSound() { cry.stop(); }

    void release() {
        released = true;
        unfollow();
        pictureView = null;
        clear();
        corner.release();
        cry.release();
        main.removeCallbacksAndMessages(null);
    }

    /** The challenges' and trackers' lines, oldest first: for tests and accessibility. */
    java.util.List<String> indicatorLines() { return corner.indicatorLines(); }

    /** The popup's text, for tests and accessibility. */
    CharSequence popupText() { return showing == null ? null : showing.heading + " · " + showing.title; }

    /** The next popup, once its badge is known (BADGE_WAIT_MILLIS at most). */
    private void next() {
        if (showing != null || fetching != null || released) return;
        final AchievementNotices.Notice head = queue.peek();
        if (head == null) return;
        if (badges.cached(head.image) != null || !badges.fetchable(head.image)) {
            open();
            return;
        }
        fetching = head;
        // Armed first: a badge known at once opens the popup within fetch().
        main.postDelayed(waited, BADGE_WAIT_MILLIS);
        badges.fetch(head.image, image -> fetched(head));
    }

    /** The awaited badge arrived, failed, or took too long. */
    private void fetched(AchievementNotices.Notice notice) {
        if (released || fetching != notice) return;
        stopFetching();
        if (queue.peek() == notice) open();
        else next();
    }

    private void stopFetching() {
        fetching = null;
        main.removeCallbacks(waited);
    }

    /** RetroArch's start, then unfold: slide in, open, wait. */
    private void open() {
        showing = queue.poll();
        if (showing == null) return;
        popup.setAnchor(settings.fromBottom(), settings.horizontal());
        // From the bottom, above what the corner shows, so that neither hides the other.
        popup.setLift(settings.fromBottom() ? corner.occupiedHeight() : 0);
        // Fetched already, or the trophy for good: the popup never changes while shown.
        Bitmap badge = badges.cached(showing.image);
        popup.show(showing.heading, showing.title, badge);
        if (showing.award && settings.sound && soundOn) cry.play(volume);
        play(stage(0f, 1f, SLIDE_MILLIS, popup::setSlide), stage(0f, 1f, UNFOLD_MILLIS, popup::setUnfold),
                () -> main.postDelayed(leave, settings.popupSeconds * 1000L));
    }

    /** RetroArch's fold, then dismiss: fold back into the badge, which slides away; then the next. */
    private void leave() {
        if (showing == null) return;
        play(stage(popup.unfold(), 0f, UNFOLD_MILLIS, popup::setUnfold),
                stage(popup.slide(), 0f, SLIDE_MILLIS, popup::setSlide), () -> {
                    popup.hide();
                    showing = null;
                    next();
                });
    }

    private void leaveNow() {
        main.removeCallbacks(leave);
        if (running != null) {
            Animator stopped = running;
            running = null;
            stopped.cancel();
        }
        popup.hide();
        showing = null;
        stopFetching();
    }

    private interface Fraction { void set(float value); }

    /** One stage: EASING_OUT_QUAD is 1 - (1 - t)², which DecelerateInterpolator(1) is. */
    private static ValueAnimator stage(float from, float to, int millis, Fraction target) {
        ValueAnimator animator = ValueAnimator.ofFloat(from, to).setDuration(millis);
        animator.setInterpolator(new DecelerateInterpolator(1f));
        animator.addUpdateListener(animation -> target.set((Float) animation.getAnimatedValue()));
        return animator;
    }

    /** One stage after the other; `done` only if nothing replaced them. */
    private void play(ValueAnimator first, ValueAnimator second, Runnable done) {
        if (running != null) running.cancel();
        running = first;
        first.addListener(new AnimatorListenerAdapter() {
            boolean cancelled;
            @Override public void onAnimationCancel(Animator animation) { cancelled = true; }
            @Override public void onAnimationEnd(Animator animation) {
                if (cancelled || running != first) return;
                running = second;
                second.start();
            }
        });
        second.addListener(new AnimatorListenerAdapter() {
            boolean cancelled;
            @Override public void onAnimationCancel(Animator animation) { cancelled = true; }
            @Override public void onAnimationEnd(Animator animation) {
                if (cancelled || running != second) return;
                running = null;
                done.run();
            }
        });
        first.start();
    }
}
