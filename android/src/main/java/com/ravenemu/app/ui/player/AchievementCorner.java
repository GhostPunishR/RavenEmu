package com.ravenemu.app.ui.player;

import com.ravenemu.app.ui.common.RavenIcon;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The bottom right corner of the picture, laid out as RetroArch's tracker
 * widget (gfx_widget_leaderboard_display.c), in RavenEmu's own code. From the
 * bottom: the leaderboard trackers, each its value in a box; above them the
 * challenges, a row of the badges about to be won, from the right; above
 * those the progress of an achievement, its locked badge and how far it got
 * ("3/10"), until a few seconds after its last change. Everything is spaced
 * by a sixty-fourth of the screen's shorter side, as RetroArch spaces it.
 *
 * A badge reaches the corner some time after it is asked for. As RetroArch
 * does, a progress whose badge is new waits for it, what is on screen staying
 * as it is, and a new challenge keeps its place without being drawn, for
 * BADGE_HOLD_MILLIS at most: the trophy is drawn after that, or at once when
 * the badge cannot come. UI thread; it never takes a touch from the game. */
final class AchievementCorner extends ViewGroup {
    static final int MAX_TRACKERS = 4, MAX_CHALLENGES = 8;
    /** RetroArch's CHEEVO_BADGE_HOLD_FRAMES, thirty frames, in time. */
    static final int BADGE_HOLD_MILLIS = 500;

    private static final class Challenge {
        final String id, title, image;
        final ImageView badge, placeholder;
        final AchievementPopup.Box box;
        boolean held;
        final Runnable release;
        Challenge(String id, String title, String image, AchievementPopup.Box box, ImageView badge, ImageView placeholder,
                  Runnable release) {
            this.id = id; this.title = title; this.image = image;
            this.box = box; this.badge = badge; this.placeholder = placeholder; this.release = release;
        }
    }

    /** An update waiting for its badge. */
    private static final class Progress {
        final String id, title, value, image;
        final int seconds;
        Progress(String id, String title, String value, String image, int seconds) {
            this.id = id; this.title = title; this.value = value; this.image = image; this.seconds = seconds;
        }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final BadgeCache badges;
    private final Map<String, TextView> trackers = new LinkedHashMap<>();
    private final Map<String, Challenge> challenges = new LinkedHashMap<>();
    /** What the corner says, "family:id" to its line, oldest first. */
    private final Map<String, String> lines = new LinkedHashMap<>();
    private final Set<String> fetching = new HashSet<>();
    private final AchievementPopup.Box progress;
    private final ImageView progressBadge, progressPlaceholder;
    private final TextView progressText;
    private String progressImage = "", progressId = "";
    private Progress pending;
    private boolean released;
    /** From the bottom edge to the top of what is shown. */
    private int occupied;
    private final Runnable hideProgress = this::hideProgress;
    private final Runnable holdOver = this::holdOver;

    AchievementCorner(Context context, BadgeCache badges) {
        super(context);
        this.badges = badges;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        progress = new AchievementPopup.Box(context);
        progress.setBackgroundColor(AchievementPopup.BACKDROP);
        progress.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        progressBadge = new ImageView(context);
        progressBadge.setScaleType(ImageView.ScaleType.FIT_XY);
        progressPlaceholder = trophy(context);
        progressText = text(context);
        progress.addView(progressBadge);
        progress.addView(progressPlaceholder);
        progress.addView(progressText);
        progress.setVisibility(GONE);
        addView(progress);
    }

    private static ImageView trophy(Context context) {
        ImageView view = RavenIcon.image(context, RavenIcon.Glyph.TROPHY, 0xffffffff, 24);
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        return view;
    }

    private static TextView text(Context context) {
        TextView view = new TextView(context);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, AchievementPopup.TEXT_SP);
        view.setTextColor(AchievementPopup.INFO);
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        view.setIncludeFontPadding(false);
        // RetroArch draws a tracker's digits at one width, so that they do not dance.
        view.setFontFeatureSettings("tnum");
        view.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        return view;
    }

    // ---- The progress of an achievement ------------------------------------------------------

    /** progress_show: on screen for `seconds` after this change, once its badge is known. */
    void showProgress(String id, String title, String value, String image, int seconds) {
        if (released) return;
        final Progress update = new Progress(id, title, value.isEmpty() ? title : value, image, seconds);
        if (badges.cached(image) != null || !badges.fetchable(image)) {
            commit(update, badges.cached(image));
            return;
        }
        // What is on screen stays, and is not hidden, until the badge is here.
        final boolean waiting = pending != null;
        pending = update;
        main.removeCallbacks(hideProgress);
        if (!waiting) main.postDelayed(holdOver, BADGE_HOLD_MILLIS);
        fetch(image);
    }

    /** progress_hide naming the one shown (a reset, a loaded state), or the game closing. */
    void hideProgress() {
        main.removeCallbacks(hideProgress);
        main.removeCallbacks(holdOver);
        pending = null;
        progressId = "";
        if (progress.getVisibility() != GONE) {
            progress.setVisibility(GONE);
            progressBadge.setImageBitmap(null);
            progressImage = "";
            requestLayout();
        }
    }

    boolean progressShown() { return progress.getVisibility() == VISIBLE; }
    /** The achievement shown, "" for none. */
    String progressId() { return progressId; }
    CharSequence progressValue() { return progressShown() ? progressText.getText() : null; }
    boolean progressWaits() { return pending != null; }
    boolean progressShowsPlaceholder() { return progressPlaceholder.getVisibility() == VISIBLE; }

    /** The wait is over: the update goes on screen, with the trophy if its badge is not here. */
    private void holdOver() {
        if (pending != null) commit(pending, badges.cached(pending.image));
    }

    private void commit(Progress update, Bitmap image) {
        main.removeCallbacks(holdOver);
        pending = null;
        progressId = update.id;
        progressImage = update.image;
        progressBadge.setImageBitmap(image);
        progressPlaceholder.setVisibility(image == null ? VISIBLE : GONE);
        if (!TextUtils.equals(progressText.getText(), update.value)) progressText.setText(update.value);
        progress.setContentDescription("Progression : " + update.title + ", " + update.value);
        if (progress.getVisibility() != VISIBLE) {
            progress.setVisibility(VISIBLE);
            requestLayout();
        }
        main.removeCallbacks(hideProgress);
        main.postDelayed(hideProgress, update.seconds * 1000L);
    }

    // ---- Challenges and leaderboard trackers -------------------------------------------------

    /** challenge_show: its badge, until its own hide; the oldest eight at most. */
    void showChallenge(String id, String title, String image) {
        if (released) return;
        lines.put("challenge:" + id, title.trim());
        if (challenges.containsKey(id)) {
            describe();
            return;
        }
        if (challenges.size() >= MAX_CHALLENGES) {
            describe();
            return;
        }
        final Context context = getContext();
        AchievementPopup.Box box = new AchievementPopup.Box(context);
        ImageView badge = new ImageView(context);
        badge.setScaleType(ImageView.ScaleType.FIT_XY);
        ImageView placeholder = trophy(context);
        box.addView(badge);
        box.addView(placeholder);
        box.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        final Challenge[] self = new Challenge[1];
        Challenge challenge = new Challenge(id, title, image, box, badge, placeholder, () -> reveal(self[0]));
        self[0] = challenge;
        challenges.put(id, challenge);
        addView(box);
        Bitmap known = badges.cached(image);
        if (known != null || !badges.fetchable(image)) {
            reveal(challenge);
        } else {
            // Its place is kept, so its neighbours do not move, but it is not drawn yet.
            challenge.held = true;
            box.setVisibility(INVISIBLE);
            main.postDelayed(challenge.release, BADGE_HOLD_MILLIS);
            fetch(image);
        }
        describe();
        requestLayout();
    }

    void hideChallenge(String id) {
        lines.remove("challenge:" + id);
        Challenge challenge = challenges.remove(id);
        if (challenge != null) {
            main.removeCallbacks(challenge.release);
            removeView(challenge.box);
            requestLayout();
        }
        describe();
    }

    /** tracker_show: its value, updated in place, until its own hide; four at most. */
    void showTracker(String id, String value) {
        if (released) return;
        lines.put("tracker:" + id, value.trim());
        TextView box = trackers.get(id);
        if (box == null) {
            if (trackers.size() >= MAX_TRACKERS) {
                describe();
                return;
            }
            box = text(getContext());
            box.setBackgroundColor(AchievementPopup.BACKDROP);
            trackers.put(id, box);
            addView(box);
        }
        if (!TextUtils.equals(box.getText(), value)) box.setText(value);
        describe();
    }

    void hideTracker(String id) {
        lines.remove("tracker:" + id);
        TextView box = trackers.remove(id);
        if (box != null) {
            removeView(box);
            requestLayout();
        }
        describe();
    }

    /** hardcore_reset: rcheevos starts its challenges and trackers again. */
    void clearIndicators() {
        for (Challenge challenge : challenges.values()) {
            main.removeCallbacks(challenge.release);
            removeView(challenge.box);
        }
        challenges.clear();
        for (TextView box : trackers.values()) removeView(box);
        trackers.clear();
        lines.clear();
        describe();
        requestLayout();
    }

    /** The game closes. */
    void clear() {
        clearIndicators();
        hideProgress();
    }

    void release() {
        released = true;
        clear();
        main.removeCallbacksAndMessages(null);
    }

    /** The challenges' and trackers' lines, oldest first: for tests and accessibility. */
    List<String> indicatorLines() { return new ArrayList<>(lines.values()); }
    int challengeCount() { return challenges.size(); }
    int trackerCount() { return trackers.size(); }
    /** From the bottom edge to the top of what the corner shows, 0 for nothing. */
    int occupiedHeight() { return occupied; }

    private void describe() {
        setContentDescription(lines.isEmpty() ? null : String.join(". ", lines.values()));
    }

    private void reveal(Challenge challenge) {
        if (challenge == null || !challenges.containsKey(challenge.id)) return;
        main.removeCallbacks(challenge.release);
        challenge.held = false;
        Bitmap known = badges.cached(challenge.image);
        challenge.badge.setImageBitmap(known);
        challenge.placeholder.setVisibility(known == null ? VISIBLE : GONE);
        challenge.box.setVisibility(VISIBLE);
    }

    /** One fetch per badge at a time, however often it is asked for. */
    private void fetch(String image) {
        if (!fetching.add(image)) return;
        badges.fetch(image, bitmap -> {
            fetching.remove(image);
            if (!released) arrived(image, bitmap);
        });
    }

    /** A badge came, or will not: whoever waits for it is drawn. */
    private void arrived(String image, Bitmap bitmap) {
        if (pending != null && pending.image.equals(image)) commit(pending, bitmap);
        else if (bitmap != null && progressShown() && image.equals(progressImage)
                && progressPlaceholder.getVisibility() == VISIBLE) {
            // Drawn with the trophy after the wait: its badge, now that it is here.
            progressBadge.setImageBitmap(bitmap);
            progressPlaceholder.setVisibility(GONE);
        }
        for (Challenge challenge : new ArrayList<>(challenges.values())) {
            if (!challenge.image.equals(image)) continue;
            if (challenge.held) reveal(challenge);
            else if (bitmap != null && challenge.placeholder.getVisibility() == VISIBLE) {
                challenge.badge.setImageBitmap(bitmap);
                challenge.placeholder.setVisibility(GONE);
            }
        }
    }

    // ---- RetroArch's layout ------------------------------------------------------------------

    /** A sixty-fourth of the screen's shorter side, as RetroArch's widgets space themselves. */
    private int spacing() {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int shorter = Math.min(metrics.widthPixels, metrics.heightPixels);
        return Math.max(Math.round(4 * metrics.density), Math.round(shorter / 64f));
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        final int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec);
        final int s = spacing(), image = 4 * s;
        final int loose = MeasureSpec.makeMeasureSpec(Math.max(0, width - 2 * s), MeasureSpec.AT_MOST);
        final int unbounded = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
        // RetroArch's CHEEVO_LBOARD_DISPLAY_PADDING, three pixels, kept readable on a phone.
        final int pad = Math.max(3, Math.round(3 * getResources().getDisplayMetrics().density));
        for (TextView box : trackers.values()) {
            box.setPadding(pad, pad / 2, pad, pad / 2);
            box.measure(loose, unbounded);
        }
        final int square = MeasureSpec.makeMeasureSpec(image, MeasureSpec.EXACTLY);
        final int inset = Math.round(image * 0.12f);
        for (Challenge challenge : challenges.values()) {
            challenge.placeholder.setPadding(inset, inset, inset, inset);
            challenge.box.measure(square, square);
            challenge.badge.measure(square, square);
            challenge.placeholder.measure(square, square);
        }
        if (progress.getVisibility() != GONE) {
            progressBadge.measure(square, square);
            progressPlaceholder.setPadding(inset, inset, inset, inset);
            progressPlaceholder.measure(square, square);
            progressText.measure(MeasureSpec.makeMeasureSpec(Math.max(0, width - 2 * s - image - 2 * s),
                    MeasureSpec.AT_MOST), unbounded);
            final int trackerWidth = progressText.getMeasuredWidth() + image + 2 * s;
            progress.measure(MeasureSpec.makeMeasureSpec(trackerWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(image + s, MeasureSpec.EXACTLY));
        }
        setMeasuredDimension(width, height);
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        final int width = right - left, height = bottom - top;
        final int s = spacing(), image = 4 * s;
        int y = height;
        for (TextView box : trackers.values()) {
            final int w = box.getMeasuredWidth(), h = box.getMeasuredHeight();
            y -= h + s;
            box.layout(width - w - s, y, width - s, y + h);
        }
        if (!challenges.isEmpty()) {
            y -= image + s;
            int x = width;
            for (Challenge challenge : challenges.values()) {
                x -= image + s;
                challenge.box.layout(x, y, x + image, y + image);
                challenge.badge.layout(0, 0, image, image);
                challenge.placeholder.layout(0, 0, image, image);
            }
        }
        if (progress.getVisibility() != GONE) {
            final int w = progress.getMeasuredWidth(), h = progress.getMeasuredHeight();
            y -= h + s;
            progress.layout(width - w - s, y, width - s, y + h);
            final int inner = s / 2;
            progressBadge.layout(inner, inner, inner + image, inner + image);
            progressPlaceholder.layout(inner, inner, inner + image, inner + image);
            final int textLeft = inner + image + s, textHeight = progressText.getMeasuredHeight();
            final int textTop = (h - textHeight) / 2;
            progressText.layout(textLeft, textTop, textLeft + progressText.getMeasuredWidth(), textTop + textHeight);
        }
        occupied = y < height ? height - y : 0;
    }

    @Override public boolean hasOverlappingRendering() { return false; }

    View progressView() { return progress; }
}
