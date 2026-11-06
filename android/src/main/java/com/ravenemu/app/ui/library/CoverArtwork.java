package com.ravenemu.app.ui.library;

import com.ravenemu.app.data.library.RomLibrary;
import com.ravenemu.app.ui.common.RavenIcon;
import com.ravenemu.app.ui.common.RavenTheme;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** A game's cover as the library shows it, after the beta's CoverArtwork:
 * the picture cropped to fill a frame rounded at 10 dp, a thin outline over
 * it; without a picture, the console's pictogram over its name on the
 * surface colour. The grid, the list and the game's sheet share it, so a
 * cover looks the same everywhere. UI thread. */
final class CoverArtwork extends FrameLayout {
    /** A grid tile's cover: the column's width, this height. */
    static final int GRID_HEIGHT_DP = 124;
    /** A tile's padding on each side of its cover, in the grid. */
    static final int GRID_SIDE_DP = 4;

    final ImageView image;
    private final LinearLayout placeholder;
    private final ImageView console;
    private final TextView system;
    private final RavenTheme theme;
    private final boolean small;

    /** `small`: the list's 44 dp cover, with a smaller pictogram. */
    CoverArtwork(Context context, RavenTheme theme, boolean small) {
        super(context);
        this.theme = theme;
        this.small = small;
        float density = context.getResources().getDisplayMetrics().density;
        GradientDrawable back = new GradientDrawable();
        back.setColor(theme.surface);
        back.setCornerRadius(10 * density);
        setBackground(back);
        // The picture takes the rounded frame's shape, as the beta's clip.
        setClipToOutline(true);
        GradientDrawable outline = new GradientDrawable();
        outline.setColor(0x00000000);
        outline.setCornerRadius(10 * density);
        outline.setStroke(Math.max(1, Math.round(density)), RavenTheme.alpha(theme.outline, 0xbf));
        setForeground(outline);

        image = new ImageView(context);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        addView(image, new LayoutParams(-1, -1));

        placeholder = new LinearLayout(context);
        placeholder.setGravity(Gravity.CENTER);
        placeholder.setOrientation(LinearLayout.VERTICAL);
        console = new ImageView(context);
        console.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        console.setScaleType(ImageView.ScaleType.CENTER);
        int consoleSide = dp(small ? 22 : 30);
        LinearLayout.LayoutParams consoleSize = new LinearLayout.LayoutParams(consoleSide, consoleSide);
        consoleSize.bottomMargin = dp(small ? 1 : 4);
        placeholder.addView(console, consoleSize);
        system = new TextView(context);
        system.setTextSize(13);
        system.setTextColor(theme.secondary);
        system.setGravity(Gravity.CENTER);
        system.setTypeface(null, Typeface.BOLD);
        placeholder.addView(system);
        addView(placeholder, new LayoutParams(-1, -1));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** The game shown: its console for the placeholder, its title for TalkBack. */
    void setGame(RomLibrary.Entry entry) {
        String name = entry.system.toUpperCase(java.util.Locale.ROOT);
        system.setText(name);
        console.setImageDrawable(new RavenIcon("gba".equals(entry.system) ? RavenIcon.Glyph.GBA
                : "gbc".equals(entry.system) ? RavenIcon.Glyph.GBC : RavenIcon.Glyph.GB,
                theme.accent, dp(small ? 20 : 28)));
        image.setContentDescription("Jaquette de " + entry.title);
    }

    /** The picture, or null for the placeholder. */
    void setCover(Bitmap cover) {
        if (cover != null && !cover.isRecycled()) {
            image.setImageBitmap(cover);
            placeholder.setVisibility(GONE);
        } else {
            image.setImageDrawable(null);
            placeholder.setVisibility(VISIBLE);
        }
    }

    boolean showsCover() { return placeholder.getVisibility() != VISIBLE; }

    /** A grid tile's cover in pixels, for a grid of this width: the
     * library's columns (106 dp at least, 8 dp apart, stretched to the
     * width) less the tile's padding, by 124 dp; as GridView lays them out. */
    static int[] gridSize(int gridWidth, float density) {
        int column = Math.round(106 * density), spacing = Math.round(8 * density);
        int width = Math.max(column, gridWidth);
        int columns = Math.max(1, (width + spacing) / (column + spacing));
        int columnWidth = column + (width - columns * column - (columns - 1) * spacing) / columns;
        return new int[]{Math.max(1, columnWidth - 2 * Math.round(GRID_SIDE_DP * density)),
                Math.round(GRID_HEIGHT_DP * density)};
    }
}
