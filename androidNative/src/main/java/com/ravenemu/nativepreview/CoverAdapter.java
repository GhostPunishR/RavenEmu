package com.ravenemu.nativepreview;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.concurrent.Executor;

/** Recycler-friendly Android list rows backed by locally imported covers.
 * Decode and file I/O always run outside the UI thread; no remote requests. */
final class CoverAdapter extends ArrayAdapter<RomLibrary.Entry> {
    private static final int MAX_CACHED_THUMBNAILS = 32;

    private final File files;
    private final Executor executor;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<String> inFlight = new HashSet<>();
    private final Set<String> missing = new HashSet<>();
    private final LinkedHashMap<String, Bitmap> thumbnails =
            new LinkedHashMap<String, Bitmap>(40, 0.75f, true) {
                @Override protected boolean removeEldestEntry(java.util.Map.Entry<String, Bitmap> item) {
                    // Do not recycle here: a currently displayed ImageView may still
                    // reference the evicted bitmap. Android GC owns its lifecycle.
                    return size() > MAX_CACHED_THUMBNAILS;
                }
            };

    private static final class Holder {
        ImageView image;
        TextView title;
        TextView platform;
    }

    CoverAdapter(Context context, File files, Executor executor) {
        super(context, 0);
        this.files = files;
        this.executor = executor;
    }

    void invalidateCover(String key) {
        thumbnails.remove(key);
        missing.remove(key);
        notifyDataSetChanged();
    }

    private int dp(float value) {
        return (int)(value * getContext().getResources().getDisplayMetrics().density + .5f);
    }

    @Override public View getView(int position, View recycled, ViewGroup parent) {
        Holder holder;
        if (recycled == null) {
            LinearLayout row = new LinearLayout(getContext());
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(7), dp(5), dp(7), dp(5));

            holder = new Holder();
            holder.image = new ImageView(getContext());
            holder.image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            holder.image.setAdjustViewBounds(false);
            row.addView(holder.image, new LinearLayout.LayoutParams(dp(68), dp(88)));

            LinearLayout descriptions = new LinearLayout(getContext());
            descriptions.setOrientation(LinearLayout.VERTICAL);
            descriptions.setPadding(dp(12), 0, 0, 0);

            holder.title = new TextView(getContext());
            holder.title.setTextSize(16);
            holder.title.setMaxLines(2);
            holder.title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            descriptions.addView(holder.title);

            holder.platform = new TextView(getContext());
            holder.platform.setTextSize(12);
            descriptions.addView(holder.platform);

            row.addView(descriptions, new LinearLayout.LayoutParams(0, -2, 1));
            row.setTag(holder);
            recycled = row;
        } else {
            holder = (Holder)recycled.getTag();
        }

        RomLibrary.Entry entry = getItem(position);
        if (entry == null) return recycled;
        holder.title.setText(entry.title);
        holder.platform.setText(entry.system.toUpperCase(java.util.Locale.ROOT) +
                (entry.cartridgeTitle.isEmpty() ? "" : " · " + entry.cartridgeTitle));
        holder.image.setContentDescription("Jaquette de " + entry.title);
        holder.image.setBackgroundColor(0xff233044);
        holder.image.setImageDrawable(null);

        Bitmap bitmap = thumbnails.get(entry.key);
        if (bitmap != null && !bitmap.isRecycled()) {
            holder.image.setImageBitmap(bitmap);
        } else if (!missing.contains(entry.key) && inFlight.add(entry.key)) {
            final String key = entry.key;
            try {
                executor.execute(() -> {
                    Bitmap loaded = null;
                    try {
                        loaded = CoverRepository.load(files, key, 192);
                    } catch (IOException | RuntimeException ignored) {
                        // A corrupted user-provided cover must not block the library.
                    }
                    final Bitmap result = loaded;
                    main.post(() -> {
                        inFlight.remove(key);
                        if (result == null) missing.add(key);
                        else thumbnails.put(key, result);
                        notifyDataSetChanged();
                    });
                });
            } catch (RuntimeException rejected) {
                inFlight.remove(key);
                missing.add(key);
            }
        }
        return recycled;
    }
}
