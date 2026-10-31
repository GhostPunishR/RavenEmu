package com.ravenemu.app.ui.library;
import com.ravenemu.app.ui.common.RavenIcon;
import com.ravenemu.app.data.library.CoverRepository;
import com.ravenemu.app.data.library.RomLibrary;

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
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executor;

/** Java Android Views version of the original RavenEmu cover grid and list.
 * Title, palette, spacing, cover proportions and empty artwork all reference
 * shared/library/LibraryComponents.kt. It never downloads game artwork. */
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
    private boolean listMode;

    RavenLibraryAdapter(Context context, File privateFiles, Executor executor) {
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
                    HomeActivity.SURFACE, 10,
                    context.getResources().getDisplayMetrics().density);
            shape.setStroke(dp(1), HomeActivity.OUTLINE);
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
            h.system = label("", 13, HomeActivity.SECONDARY);
            h.system.setTypeface(null, Typeface.BOLD);
            h.placeholder.addView(h.system);
            artwork.addView(h.placeholder, new FrameLayout.LayoutParams(-1, -1));

            if (listMode) {
                column.setBackground(HomeActivity.shape(0xff151722, 10,
                        context.getResources().getDisplayMetrics().density));
                column.addView(artwork, new LinearLayout.LayoutParams(dp(44), dp(52)));
            } else {
                column.addView(artwork, new LinearLayout.LayoutParams(-1, dp(124)));
            }

            LinearLayout labels = new LinearLayout(context);
            labels.setOrientation(LinearLayout.VERTICAL);
            if (listMode) labels.setPadding(dp(12), 0, 0, 0);
            else labels.setPadding(0, dp(6), 0, 0);
            h.title = label("", listMode ? 15 : 11,
                    listMode ? HomeActivity.TEXT : HomeActivity.SECONDARY);
            h.title.setMaxLines(listMode ? 1 : 3);
            h.title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            h.title.setGravity(listMode ? Gravity.LEFT : Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            labels.addView(h.title, new LinearLayout.LayoutParams(-1, -2));
            h.secondary = label("", 12, HomeActivity.MUTED);
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
        h.secondary.setText(entry.system.toUpperCase(java.util.Locale.ROOT));
        h.system.setText(entry.system.toUpperCase(java.util.Locale.ROOT));
        h.console.setImageDrawable(new RavenIcon("gba".equals(entry.system) ? RavenIcon.Glyph.GBA
                : "gbc".equals(entry.system) ? RavenIcon.Glyph.GBC : RavenIcon.Glyph.GB,
                HomeActivity.ACCENT, dp(listMode ? 20 : 28)));
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
