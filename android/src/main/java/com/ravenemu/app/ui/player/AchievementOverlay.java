package com.ravenemu.app.ui.player;

import com.ravenemu.app.bridge.NativeAchievements;
import com.ravenemu.app.settings.AchievementSettings;
import com.ravenemu.app.ui.library.HomeActivity;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayDeque;

/** RetroAchievements over the game, after the beta's RetroOverlays.kt: the
 * one-at-a-time banner (its badge drops in, then the band unfolds; the
 * reverse to leave; an award is framed in gold), and under it the measured
 * progress of an achievement. At the top of the picture, or at the bottom.
 * UI thread; it never takes a touch from the game. */
final class AchievementOverlay extends FrameLayout {
    static final int GOLD = 0xfff4c152;
    static final int DROP_MILLIS = 300, UNFOLD_MILLIS = 350;
    private static final int MAX_QUEUED = 8;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final UnlockChime chime = new UnlockChime();
    private final ArrayDeque<AchievementNotices.Notice> queue = new ArrayDeque<>();
    private final BadgeCache badges;
    private final LinearLayout column;
    private final LinearLayout banner;
    private final FrameLayout bannerBadge;
    private final ImageView bannerImage;
    private final TextView bannerStar, bannerHeading, bannerTitle;
    private final Unfold bannerText;
    private final LinearLayout progress;
    private final ImageView progressImage;
    private final TextView progressStar, progressTitle, progressDetail;
    private AchievementSettings settings = AchievementSettings.DEFAULTS;
    private boolean soundOn = true;
    private int volume = 100;
    private AchievementNotices.Notice showing;
    private String progressBadge = "";
    private Animator running;
    private int serial;
    private boolean released;
    private final Runnable leave = this::leave;
    private final Runnable hideProgress = () -> fadeProgress(false);

    AchievementOverlay(Context context, BadgeCache badges) {
        super(context);
        this.badges = badges;
        setClipChildren(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        column.setClipChildren(false);
        addView(column, new LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL | Gravity.TOP));

        banner = new LinearLayout(context);
        banner.setGravity(Gravity.CENTER_VERTICAL);
        banner.setBackground(round(0xf00b0c12, 10, 0, 0));
        banner.setElevation(dp(6));
        banner.setVisibility(GONE);
        banner.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        bannerBadge = new FrameLayout(context);
        bannerBadge.setClipToOutline(true);
        bannerImage = new ImageView(context);
        bannerImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
        bannerBadge.addView(bannerImage, new LayoutParams(-1, -1));
        bannerStar = text("★", 24, HomeActivity.ACCENT);
        bannerStar.setGravity(Gravity.CENTER);
        bannerBadge.addView(bannerStar, new LayoutParams(-1, -1));
        LinearLayout.LayoutParams badgeSize = new LinearLayout.LayoutParams(dp(46), dp(46));
        badgeSize.setMargins(dp(5), dp(5), dp(5), dp(5));
        banner.addView(bannerBadge, badgeSize);
        LinearLayout lines = new LinearLayout(context);
        lines.setOrientation(LinearLayout.VERTICAL);
        lines.setPadding(dp(8), 0, dp(16), 0);
        bannerHeading = line(12, HomeActivity.SECONDARY);
        bannerTitle = line(15, HomeActivity.TEXT);
        bannerTitle.setTypeface(null, Typeface.BOLD);
        lines.addView(bannerHeading);
        lines.addView(bannerTitle);
        bannerText = new Unfold(context, dp(420));
        bannerText.addView(lines, new LayoutParams(-2, -2, Gravity.CENTER_VERTICAL));
        banner.addView(bannerText, new LinearLayout.LayoutParams(-2, -2));

        progress = new LinearLayout(context);
        progress.setGravity(Gravity.CENTER_VERTICAL);
        progress.setBackground(round(HomeActivity.SURFACE, 12, 1, HomeActivity.ACCENT));
        progress.setPadding(dp(14), dp(10), dp(14), dp(10));
        progress.setVisibility(GONE);
        progress.setAlpha(0f);
        FrameLayout progressBadgeBox = new FrameLayout(context);
        progressImage = new ImageView(context);
        progressImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        progressBadgeBox.addView(progressImage, new LayoutParams(-1, -1));
        progressStar = text("★", 21, HomeActivity.ACCENT);
        progressStar.setGravity(Gravity.CENTER);
        progressBadgeBox.addView(progressStar, new LayoutParams(-1, -1));
        progress.addView(progressBadgeBox, new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout progressLines = new LinearLayout(context);
        progressLines.setOrientation(LinearLayout.VERTICAL);
        progressTitle = line(12, HomeActivity.TEXT);
        progressDetail = line(12, HomeActivity.TEXT);
        progressLines.addView(progressTitle);
        progressLines.addView(progressDetail);
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-2, -2);
        gap.setMarginStart(dp(8));
        progress.addView(progressLines, gap);
        column.addView(banner, new LinearLayout.LayoutParams(-2, dp(56)));
        LinearLayout.LayoutParams below = new LinearLayout.LayoutParams(-2, -2);
        below.topMargin = dp(8);
        column.addView(progress, below);
        placeColumn();
    }

    /** Settings → RetroAchievements; Settings → Audio for the chime. */
    void setSettings(AchievementSettings settings, boolean soundOn, int volume) {
        this.settings = settings;
        this.soundOn = soundOn;
        this.volume = volume;
        if (!settings.unlockPopups) {
            queue.removeIf(notice -> notice.award);
            if (showing != null && showing.award) leaveNow();
        }
        if (!settings.progressPopups) fadeProgress(false);
        placeColumn();
    }

    /** What the C module reported; see AchievementNotices. */
    void onEvent(NativeAchievements.Event event) {
        if (released) return;
        if ("progress_show".equals(event.kind)) {
            if (settings.progressPopups) showProgress(event);
            return;
        }
        if ("progress_hide".equals(event.kind)) {
            fadeProgress(false);
            return;
        }
        AchievementNotices.Notice notice = AchievementNotices.of(event);
        if (notice == null) return;
        if (notice.award && settings.sound && soundOn) chime.play(volume);
        if (notice.award && !settings.unlockPopups) return;
        if (queue.size() < MAX_QUEUED) queue.add(notice);
        if (showing == null) next();
    }

    /** The game closes: nothing is left on screen or queued. */
    void clear() {
        queue.clear();
        leaveNow();
        fadeProgress(false);
    }

    void release() {
        released = true;
        clear();
        chime.release();
        main.removeCallbacksAndMessages(null);
    }

    /** The banner's text, for tests and accessibility. */
    CharSequence bannerText() { return showing == null ? null : showing.heading + " · " + showing.title; }

    private void next() {
        showing = queue.poll();
        if (showing == null) return;
        final int shown = ++serial;
        bannerHeading.setText(showing.heading);
        bannerHeading.setVisibility(showing.heading.isEmpty() ? GONE : VISIBLE);
        bannerHeading.setTextColor(showing.award ? GOLD : HomeActivity.SECONDARY);
        bannerTitle.setText(showing.title);
        bannerTitle.setVisibility(showing.title.isEmpty() ? GONE : VISIBLE);
        bannerStar.setTextColor(showing.award ? GOLD : HomeActivity.ACCENT);
        bannerBadge.setBackground(round(HomeActivity.SURFACE, 7, 2, showing.award ? GOLD : HomeActivity.OUTLINE));
        banner.setContentDescription(showing.heading + ". " + showing.title);
        badge(showing.image, bannerImage, bannerStar, () -> shown == serial);
        banner.setVisibility(VISIBLE);
        banner.setTranslationY(offscreen());
        bannerText.setFraction(0f);
        ValueAnimator drop = ValueAnimator.ofFloat(offscreen(), 0f).setDuration(DROP_MILLIS);
        drop.setInterpolator(new DecelerateInterpolator());
        drop.addUpdateListener(animation -> banner.setTranslationY((Float)animation.getAnimatedValue()));
        ValueAnimator unfold = ValueAnimator.ofFloat(0f, 1f).setDuration(UNFOLD_MILLIS);
        unfold.addUpdateListener(animation -> bannerText.setFraction((Float)animation.getAnimatedValue()));
        play(drop, unfold, () -> main.postDelayed(leave, settings.popupSeconds * 1000L));
    }

    /** Folds back into the badge, which leaves; then the next one. */
    private void leave() {
        if (showing == null) return;
        ValueAnimator fold = ValueAnimator.ofFloat(bannerText.fraction, 0f).setDuration(UNFOLD_MILLIS);
        fold.addUpdateListener(animation -> bannerText.setFraction((Float)animation.getAnimatedValue()));
        ValueAnimator rise = ValueAnimator.ofFloat(banner.getTranslationY(), offscreen()).setDuration(DROP_MILLIS);
        rise.addUpdateListener(animation -> banner.setTranslationY((Float)animation.getAnimatedValue()));
        play(fold, rise, () -> {
            banner.setVisibility(GONE);
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
        banner.setVisibility(GONE);
        showing = null;
        ++serial;
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

    private void showProgress(NativeAchievements.Event event) {
        progressTitle.setText("Progression · " + event.title);
        progressTitle.setMaxLines(event.detail.isEmpty() ? 2 : 1);
        progressDetail.setText(event.detail);
        progressDetail.setVisibility(event.detail.isEmpty() ? GONE : VISIBLE);
        progress.setContentDescription(progressTitle.getText() + " " + event.detail);
        if (!progressBadge.equals(event.image)) {
            progressBadge = event.image;
            final String wanted = event.image;
            badge(event.image, progressImage, progressStar, () -> wanted.equals(progressBadge));
        }
        fadeProgress(true);
        main.removeCallbacks(hideProgress);
        main.postDelayed(hideProgress, settings.progressSeconds * 1000L);
    }

    private void fadeProgress(boolean in) {
        main.removeCallbacks(hideProgress);
        progress.animate().cancel();
        if (in) {
            progress.setVisibility(VISIBLE);
            progress.animate().alpha(1f).setDuration(300).start();
        } else if (progress.getVisibility() == VISIBLE) {
            progress.animate().alpha(0f).setDuration(300).withEndAction(() -> progress.setVisibility(GONE)).start();
        }
    }

    /** The image when known; the star until then, or for good. */
    private void badge(String url, ImageView image, TextView star, java.util.function.BooleanSupplier still) {
        Bitmap known = badges.cached(url);
        image.setImageBitmap(known);
        star.setVisibility(known == null ? VISIBLE : GONE);
        if (known == null) badges.load(url, decoded -> {
            if (released) return;
            image.setImageBitmap(decoded);
            star.setVisibility(GONE);
        }, still);
    }

    /** At the top or the bottom of the picture; as in the beta, the banner
     * always comes first and the progress card under it. */
    private void placeColumn() {
        final boolean bottom = AchievementSettings.BOTTOM.equals(settings.position);
        LayoutParams place = (LayoutParams)column.getLayoutParams();
        place.gravity = Gravity.CENTER_HORIZONTAL | (bottom ? Gravity.BOTTOM : Gravity.TOP);
        place.setMargins(dp(12), dp(10), dp(12), dp(10));
        column.setLayoutParams(place);
    }

    /** Beyond the picture's edge, on the side the banner comes from. */
    private float offscreen() {
        final float distance = dp(56 + 24);
        return AchievementSettings.BOTTOM.equals(settings.position) ? distance : -distance;
    }

    private GradientDrawable round(int color, int radius, int stroke, int strokeColor) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        if (stroke > 0) shape.setStroke(dp(stroke), strokeColor);
        return shape;
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(getContext());
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        return view;
    }

    private TextView line(int size, int color) {
        TextView view = text("", size, color);
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        return view;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    /** Lays its child out whole but keeps only `fraction` of its width: the
     * text is uncovered as the band unfolds, never squeezed. */
    static final class Unfold extends FrameLayout {
        private final int maxWidth;
        float fraction = 1f;

        Unfold(Context context, int maxWidth) {
            super(context);
            this.maxWidth = maxWidth;
            setClipChildren(true);
        }

        void setFraction(float value) {
            fraction = Math.max(0f, Math.min(1f, value));
            requestLayout();
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int available = MeasureSpec.getMode(widthSpec) == MeasureSpec.UNSPECIFIED
                    ? maxWidth : Math.min(maxWidth, MeasureSpec.getSize(widthSpec));
            super.onMeasure(MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST), heightSpec);
            setMeasuredDimension(Math.round(getMeasuredWidth() * fraction), getMeasuredHeight());
        }

        @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                int y = Math.max(0, (bottom - top - child.getMeasuredHeight()) / 2);
                child.layout(0, y, child.getMeasuredWidth(), y + child.getMeasuredHeight());
            }
        }
    }
}
