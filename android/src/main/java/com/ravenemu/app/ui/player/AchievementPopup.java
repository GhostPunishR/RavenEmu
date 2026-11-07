package com.ravenemu.app.ui.player;

import com.ravenemu.app.ui.common.RavenIcon;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** One RetroAchievements popup, drawn as RetroArch's achievement widget
 * (gfx_widget_achievement_popup.c), in RavenEmu's own code: a square badge
 * on a black backdrop at 75 %, then a backdrop as tall holding two lines in
 * the same font, the first grey, the second white. It is four lines tall and
 * as wide as its longer line plus two thirds of a line on each side.
 *
 * Its motion has two stages, each a fraction from 0 to 1 that the overlay
 * animates: `slide` brings the badge in from the edge of the picture; `unfold`
 * opens the text beside it, which comes in from the left by half the width,
 * while the whole popup moves left by half the text's width so that it ends
 * centred. Positions are translations and clips only: nothing is laid out
 * again while it moves. It takes no touch. */
final class AchievementPopup extends ViewGroup {
    /** RetroArch's widget colours: TEXT_COLOR_FAINT, TEXT_COLOR_INFO, DEFAULT_BACKDROP. */
    static final int FAINT = 0xff888888, INFO = 0xffeeeeee, BACKDROP = 0xbf000000;
    static final float TEXT_SP = 13f;

    private final Box badgeBox;
    private final ImageView badge;
    /** RetroArch's achievement icon while no badge is known: a white trophy. */
    private final ImageView placeholder;
    private final Box panel;
    private final LinearLayout lines;
    private final TextView heading, title;
    private final Rect clip = new Rect();
    private boolean bottom;
    private float slide, unfold;
    /** Lifted above what the bottom right corner shows, from the bottom only. */
    private int lift;
    /** Line height, popup height, text padding and margin from the edges. */
    private int line, size, padding, margin, textWidth;

    AchievementPopup(Context context) {
        super(context);
        setClipChildren(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        badgeBox = new Box(context);
        badgeBox.setBackgroundColor(BACKDROP);
        badge = new ImageView(context);
        badge.setScaleType(ImageView.ScaleType.FIT_XY);
        badgeBox.addView(badge);
        placeholder = RavenIcon.image(context, RavenIcon.Glyph.TROPHY, 0xffffffff, 24);
        placeholder.setScaleType(ImageView.ScaleType.FIT_CENTER);
        badgeBox.addView(placeholder);
        addView(badgeBox);
        panel = new Box(context);
        panel.setBackgroundColor(BACKDROP);
        lines = new LinearLayout(context);
        lines.setOrientation(LinearLayout.VERTICAL);
        heading = line(context, FAINT);
        title = line(context, INFO);
        lines.addView(heading, new LinearLayout.LayoutParams(-2, -2));
        lines.addView(title, new LinearLayout.LayoutParams(-2, -2));
        panel.addView(lines);
        addView(panel);
        setVisibility(GONE);
    }

    private static TextView line(Context context, int color) {
        TextView view = new TextView(context);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SP);
        view.setTextColor(color);
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        view.setIncludeFontPadding(false);
        view.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        return view;
    }

    /** A new popup: its lines, and its badge or the trophy. Folded, off the picture. */
    void show(String headingText, String titleText, Bitmap image) {
        heading.setText(headingText);
        heading.setVisibility(headingText.isEmpty() ? GONE : VISIBLE);
        title.setText(titleText);
        title.setVisibility(titleText.isEmpty() ? GONE : VISIBLE);
        badge.setImageBitmap(image);
        placeholder.setVisibility(image == null ? VISIBLE : GONE);
        setContentDescription(headingText.isEmpty() ? titleText
                : titleText.isEmpty() ? headingText : headingText + ". " + titleText);
        slide = 0f;
        unfold = 0f;
        setVisibility(VISIBLE);
        requestLayout();
        place();
    }

    void hide() {
        setVisibility(GONE);
        badge.setImageBitmap(null);
    }

    /** From the top or the bottom edge, centred as RetroArch's TOPCENTER and BOTTOMCENTER. */
    void setBottom(boolean value) {
        bottom = value;
        place();
    }

    void setLift(int pixels) {
        lift = Math.max(0, pixels);
        place();
    }

    void setSlide(float value) {
        slide = clamp(value);
        place();
    }

    void setUnfold(float value) {
        unfold = clamp(value);
        place();
    }

    float slide() { return slide; }
    float unfold() { return unfold; }
    boolean fromBottom() { return bottom; }
    boolean showsPlaceholder() { return placeholder.getVisibility() == VISIBLE; }
    Bitmap badge() {
        return badge.getDrawable() instanceof android.graphics.drawable.BitmapDrawable
                ? ((android.graphics.drawable.BitmapDrawable) badge.getDrawable()).getBitmap() : null;
    }
    /** Where the badge's top and left edges are now, in the overlay: for tests. */
    float badgeTop() { return badgeBox.getTranslationY(); }
    float badgeLeft() { return badgeBox.getTranslationX(); }
    /** The popup's height, four lines of its font. */
    int popupHeight() { return size; }
    /** The text's backdrop, its whole width and the part of it uncovered. */
    int textWidth() { return textWidth; }
    int textShown() { return panel.getClipBounds() == null ? textWidth : panel.getClipBounds().width(); }
    /** The space left to the edge of the picture. */
    int edgeMargin() { return margin; }

    private static float clamp(float value) { return Math.max(0f, Math.min(1f, value)); }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        final int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec);
        line = Math.max(1, Math.round(heading.getPaint().getFontSpacing()));
        size = 4 * line;
        padding = Math.round(line * 2f / 3f);
        // RetroArch's automatic padding, msg_queue_rect_start_x, in lines of this font.
        margin = Math.max(1, Math.round(line * 0.35f));
        final int room = Math.max(0, width - 2 * margin - size - 2 * padding);
        lines.measure(MeasureSpec.makeMeasureSpec(room, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(size,
                MeasureSpec.AT_MOST));
        textWidth = lines.getMeasuredWidth() + 2 * padding;
        final int square = MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY);
        badgeBox.measure(square, square);
        badge.measure(square, square);
        int inset = Math.round(size * 0.18f);
        placeholder.setPadding(inset, inset, inset, inset);
        placeholder.measure(square, square);
        panel.measure(MeasureSpec.makeMeasureSpec(textWidth, MeasureSpec.EXACTLY), square);
        setMeasuredDimension(width, height);
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottomEdge) {
        badgeBox.layout(0, 0, size, size);
        badge.layout(0, 0, size, size);
        placeholder.layout(0, 0, size, size);
        panel.layout(0, 0, textWidth, size);
        int y = (size - lines.getMeasuredHeight()) / 2;
        lines.layout(padding, y, padding + lines.getMeasuredWidth(), y + lines.getMeasuredHeight());
        place();
    }

    /** RetroArch's positions for these fractions, as translations and a clip. */
    private void place() {
        if (size == 0) return;
        final int width = getWidth(), height = getHeight();
        final float x = (width - size) / 2f - textWidth / 2f * unfold;
        // From beyond the edge to its place; from the bottom, above what the corner shows.
        final float y = bottom ? height - (margin + size + lift) * slide : -size + (margin + size) * slide;
        badgeBox.setTranslationX(x);
        badgeBox.setTranslationY(y);
        panel.setTranslationX(x + size);
        panel.setTranslationY(y);
        clip.set(0, 0, Math.round(textWidth * unfold), size);
        panel.setClipBounds(clip);
        panel.setVisibility(unfold > 0f ? VISIBLE : INVISIBLE);
        lines.setTranslationX(-(1f - unfold) * textWidth * 0.5f);
    }

    @Override public boolean hasOverlappingRendering() { return false; }

    /** A plain container: its children are measured and placed by its owner,
     * never moved by a layout of its own. */
    static final class Box extends ViewGroup {
        Box(Context context) { super(context); }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            setMeasuredDimension(MeasureSpec.getSize(widthSpec), MeasureSpec.getSize(heightSpec));
        }
        @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {}
        @Override public boolean hasOverlappingRendering() { return false; }
    }
}
