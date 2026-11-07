package com.ravenemu.app.ui.player;

import com.ravenemu.app.bridge.NativeAchievements;
import com.ravenemu.app.settings.AchievementSettings;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import java.util.ArrayDeque;

/** RetroAchievements over the game, as RetroArch shows them, in RavenEmu's
 * own code. One popup at a time (AchievementPopup), eight waiting at most:
 * the game that starts, each unlock, the completed game and the session's
 * notices. Its badge slides in from the edge, then its text unfolds beside
 * it; it stays for Settings' duration, folds back and slides away; then the
 * next. Each stage takes RetroArch's MSG_QUEUE_ANIMATION_DURATION, 330 ms,
 * eased out (quadratic). A popup opens once its badge is fetched, 2 s at
 * most, and never changes while shown. In the bottom right corner
 * (AchievementCorner): the progress of an achievement, the challenges and
 * the leaderboard trackers. UI thread; it never takes a touch from the game. */
final class AchievementOverlay extends FrameLayout {
    /** RetroArch's MSG_QUEUE_ANIMATION_DURATION, for each stage. */
    static final int SLIDE_MILLIS = 330, UNFOLD_MILLIS = 330;
    /** A popup waits this long at most for its badge, then opens with the trophy. */
    static final int BADGE_WAIT_MILLIS = 2_000;
    /** RetroArch's CHEEVO_QUEUE_SIZE. */
    static final int MAX_QUEUED = 8;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final UnlockChime chime = new UnlockChime();
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
    private final Runnable leave = this::leave;
    private final Runnable waited = () -> { if (fetching != null) fetched(fetching); };

    AchievementOverlay(Context context, BadgeCache badges) {
        super(context);
        this.badges = badges;
        setClipChildren(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        corner = new AchievementCorner(context, badges);
        addView(corner, new LayoutParams(-1, -1));
        popup = new AchievementPopup(context);
        addView(popup, new LayoutParams(-1, -1));
        popup.setBottom(false);
    }

    /** Settings → RetroAchievements; Settings → Audio for the chime. */
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
        // A popup on screen keeps its edge; the next one takes the new one.
        if (showing == null) popup.setBottom(fromBottom());
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
            // No popup: the chime alone (a popup rings when it opens).
            if (settings.sound && soundOn) chime.play(volume);
            return;
        }
        if (queue.size() < MAX_QUEUED) queue.add(notice);
        next();
    }

    /** The game closes: nothing is left on screen or queued. */
    void clear() {
        queue.clear();
        leaveNow();
        corner.clear();
    }

    void release() {
        released = true;
        clear();
        corner.release();
        chime.release();
        main.removeCallbacksAndMessages(null);
    }

    /** The challenges' and trackers' lines, oldest first: for tests and accessibility. */
    java.util.List<String> indicatorLines() { return corner.indicatorLines(); }

    /** The popup's text, for tests and accessibility. */
    CharSequence popupText() { return showing == null ? null : showing.heading + " · " + showing.title; }

    private boolean fromBottom() { return AchievementSettings.BOTTOM.equals(settings.position); }

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
        popup.setBottom(fromBottom());
        // From the bottom, above what the corner shows, so that neither hides the other.
        popup.setLift(fromBottom() ? corner.occupiedHeight() : 0);
        // Fetched already, or the trophy for good: the popup never changes while shown.
        Bitmap badge = badges.cached(showing.image);
        popup.show(showing.heading, showing.title, badge);
        if (showing.award && settings.sound && soundOn) chime.play(volume);
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
