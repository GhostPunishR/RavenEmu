package com.ravenemu.app.ui.library;
import com.ravenemu.app.ui.common.RavenIcon;
import com.ravenemu.app.data.library.CoverRepository;
import com.ravenemu.app.data.library.RomLibrary;
import com.ravenemu.app.ui.common.RavenTheme;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executor;

/** Java Android Views version of the original RavenEmu cover grid and list.
 * Title, palette, spacing, cover proportions and empty artwork all reference
 * shared/library/LibraryComponents.kt. It never downloads game artwork.
 * The tiles of a grid row all take its tallest title's height, as the
 * beta's lazy grid sizes a row by its tallest tile: GridView places each row
 * from one of its tiles, so tiles of different heights shifted while
 * scrolling. */
final class RavenLibraryAdapter extends BaseAdapter {
    private final Context context;
    private final File privateFiles;
    private final Executor executor;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<RomLibrary.Entry> games = new ArrayList<>();
    private final Map<String, Bitmap> cache = new LinkedHashMap<String, Bitmap>(32, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
            // Do not recycle a bitmap still held by a visible ImageView.
            return size() > 32;
        }
    };
    private final Set<String> loading = new HashSet<>();
    private final Set<String> unavailable = new HashSet<>();
    /** A grid title's height in pixels (1 to 3 lines) at `titleWidth`, by title. */
    private final Map<String, Integer> titleHeights = new HashMap<>();
    private int titleWidth;
    /** Laid out as the grid's titles, to measure them. */
    private TextView sizer;
    private boolean listMode;

    /** The colours of the Activity it belongs to (Settings → Interface → Thème). */
    private final RavenTheme theme;

    RavenLibraryAdapter(Context context, File privateFiles, Executor executor) {
        this.theme = RavenTheme.of(context);
        this.context = context;
        this.privateFiles = privateFiles;
        this.executor = executor;
    }

    void setListMode(boolean value) { listMode = value; notifyDataSetChanged(); }
    void replace(List<RomLibrary.Entry> entries) {
        games.clear();
        games.addAll(entries);
        notifyDataSetChanged();
    }
    void invalidateCover(String key) {
        cache.remove(key);
        unavailable.remove(key);
        notifyDataSetChanged();
    }

    @Override public int getCount() { return games.size(); }
    @Override public RomLibrary.Entry getItem(int position) { return games.get(position); }
    @Override public long getItemId(int position) { return position; }

    private int dp(int size) {
        return Math.round(size * context.getResources().getDisplayMetrics().density);
    }

    private TextView label(String title, int size, int color) {
        TextView text = new TextView(context);
        text.setText(title);
        text.setTextSize(size);
        text.setTextColor(color);
        text.setGravity(Gravity.CENTER);
        return text;
    }

    private static final class Holder {
        ImageView image;
        LinearLayout placeholder;
        ImageView console;
        TextView system;
        TextView title;
        TextView secondary;
        String key;
        boolean mode;
    }

    @Override public View getView(int index, View old, ViewGroup parent) {
        final RomLibrary.Entry entry = games.get(index);
        final Holder h;
        if (old == null || ((Holder)old.getTag()).mode != listMode) {
            h = new Holder();
            h.mode = listMode;
            LinearLayout column = new LinearLayout(context);
            column.setOrientation(listMode ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
            column.setGravity(listMode ? Gravity.CENTER_VERTICAL : Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            column.setPadding(dp(listMode ? 10 : 4), dp(listMode ? 9 : 6),
                    dp(listMode ? 10 : 4), dp(10));

            FrameLayout artwork = new FrameLayout(context);
            GradientDrawable shape = HomeActivity.shape(
                    theme.surface, 10,
                    context.getResources().getDisplayMetrics().density);
            shape.setStroke(dp(1), theme.outline);
            artwork.setBackground(shape);

            h.image = new ImageView(context);
            h.image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            artwork.addView(h.image, new FrameLayout.LayoutParams(-1, -1));

            h.placeholder = new LinearLayout(context);
            h.placeholder.setGravity(Gravity.CENTER);
            h.placeholder.setOrientation(LinearLayout.VERTICAL);
            // No cover yet: the console's pictogram over its name.
            h.console = new ImageView(context);
            h.console.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            h.console.setScaleType(ImageView.ScaleType.CENTER);
            LinearLayout.LayoutParams consoleSize = new LinearLayout.LayoutParams(dp(listMode ? 22 : 30), dp(listMode ? 22 : 30));
            consoleSize.bottomMargin = dp(listMode ? 1 : 4);
            h.placeholder.addView(h.console, consoleSize);
            h.system = label("", 13, theme.secondary);
            h.system.setTypeface(null, Typeface.BOLD);
            h.placeholder.addView(h.system);
            artwork.addView(h.placeholder, new FrameLayout.LayoutParams(-1, -1));

            if (listMode) {
                column.setBackground(HomeActivity.shape(theme.column, 10,
                        context.getResources().getDisplayMetrics().density));
                column.addView(artwork, new LinearLayout.LayoutParams(dp(44), dp(52)));
            } else {
                column.addView(artwork, new LinearLayout.LayoutParams(-1, dp(124)));
            }

            LinearLayout labels = new LinearLayout(context);
            labels.setOrientation(LinearLayout.VERTICAL);
            if (listMode) labels.setPadding(dp(12), 0, 0, 0);
            else labels.setPadding(0, dp(6), 0, 0);
            h.title = title(listMode);
            labels.addView(h.title, new LinearLayout.LayoutParams(-1, -2));
            h.secondary = label("", 12, theme.muted);
            h.secondary.setGravity(Gravity.LEFT);
            if (listMode) labels.addView(h.secondary);
            column.addView(labels, listMode
                    ? new LinearLayout.LayoutParams(0, -2, 1)
                    : new LinearLayout.LayoutParams(-1, -2));

            old = column;
            old.setTag(h);
        } else h = (Holder)old.getTag();

        h.key = entry.key;
        h.title.setText(entry.title);
        h.title.setMinHeight(listMode ? 0 : rowTitleHeight(index, parent));
        h.secondary.setText(entry.system.toUpperCase(java.util.Locale.ROOT));
        h.system.setText(entry.system.toUpperCase(java.util.Locale.ROOT));
        h.console.setImageDrawable(new RavenIcon("gba".equals(entry.system) ? RavenIcon.Glyph.GBA
                : "gbc".equals(entry.system) ? RavenIcon.Glyph.GBC : RavenIcon.Glyph.GB,
                theme.accent, dp(listMode ? 20 : 28)));
        h.image.setContentDescription("Jaquette de " + entry.title);

        Bitmap cached = cache.get(entry.key);
        if (cached != null && !cached.isRecycled()) {
            h.image.setImageBitmap(cached);
            h.placeholder.setVisibility(View.GONE);
        } else {
            h.image.setImageDrawable(null);
            h.placeholder.setVisibility(View.VISIBLE);
            if (!loading.contains(entry.key) && !unavailable.contains(entry.key)) {
                loadCover(entry.key);
            }
        }
        return old;
    }

    /** A tile's title: one line in the list, up to three in the grid. */
    private TextView title(boolean list) {
        TextView title = label("", list ? 15 : 11, list ? theme.text : theme.secondary);
        title.setMaxLines(list ? 1 : 3);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setGravity(list ? Gravity.LEFT : Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        return title;
    }

    /** The tallest title of this tile's row of the grid, in pixels; 0
     * before the grid knows its columns. */
    private int rowTitleHeight(int index, ViewGroup parent) {
        if (!(parent instanceof GridView)) return 0;
        GridView grid = (GridView) parent;
        int columns = Math.max(1, grid.getNumColumns());
        // The tile's padding is 4 dp on each side; the title fills the rest.
        int width = grid.getColumnWidth() - 2 * dp(4);
        if (width <= 0) return 0;
        int first = index - index % columns, end = Math.min(games.size(), first + columns);
        int tallest = 0;
        for (int i = first; i < end; i++) tallest = Math.max(tallest, titleHeight(games.get(i).title, width));
        return tallest;
    }

    private int titleHeight(String text, int width) {
        if (width != titleWidth) {
            titleHeights.clear();
            titleWidth = width;
        }
        Integer known = titleHeights.get(text);
        if (known != null) return known;
        if (sizer == null) sizer = title(false);
        sizer.setText(text);
        sizer.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int height = sizer.getMeasuredHeight();
        titleHeights.put(text, height);
        return height;
    }

    private void loadCover(String key) {
        loading.add(key);
        try {
            executor.execute(() -> {
                Bitmap bitmap = null;
                try {
                    bitmap = CoverRepository.load(privateFiles, key, 256);
                } catch (IOException | RuntimeException ignored) {
                    // Missing/invalid covers never block or crash library rendering.
                }
                Bitmap result = bitmap;
                main.post(() -> {
                    loading.remove(key);
                    if (result == null) unavailable.add(key);
                    else cache.put(key, result);
                    notifyDataSetChanged();
                });
            });
        } catch (RuntimeException ignored) {
            loading.remove(key);
            unavailable.add(key);
        }
    }
}
