package com.ravenemu.app.ui.player;

import com.ravenemu.app.platform.AchievementsHttp;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** RetroAchievements' badges, icons and avatars for the player: fetched by
 * one background thread, decoded within bounds, kept for the set (a few
 * hundred 64-pixel badges stay small). UI thread, except the fetch. */
final class BadgeCache {
    private static final int MAX_IMAGES = 320;
    private static final ExecutorService IMAGES = Executors.newSingleThreadExecutor(work -> {
        Thread thread = new Thread(work, "RavenEmu-RetroAchievements-images");
        thread.setDaemon(true);
        return thread;
    });

    interface Target { void show(Bitmap image); }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Bitmap> images = new LinkedHashMap<String, Bitmap>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
            return size() > MAX_IMAGES;
        }
    };
    /** Set with the game's account: without one, nothing is fetched. */
    private AchievementsHttp http;
    private boolean released;

    void setHttp(AchievementsHttp http) { this.http = http; }

    Bitmap cached(String url) { return url == null || url.isEmpty() ? null : images.get(url); }

    /** The image now if known, else once fetched, if `target` still wants it. */
    void load(String url, Target target, java.util.function.BooleanSupplier still) {
        Bitmap known = cached(url);
        if (known != null) {
            target.show(known);
            return;
        }
        final AchievementsHttp network = http;
        if (network == null || url == null || url.isEmpty() || !AchievementsHttp.allowed(url)) return;
        IMAGES.execute(() -> {
            Bitmap decoded = network.badge(url);
            if (decoded == null) return;
            main.post(() -> {
                if (released) return;
                images.put(url, decoded);
                if (still.getAsBoolean()) target.show(decoded);
            });
        });
    }

    /** An ImageView shows the badge; tagged, so a recycled row keeps its own. */
    void into(ImageView view, String url) {
        view.setTag(url);
        Bitmap known = cached(url);
        view.setImageBitmap(known);
        if (known == null) load(url, view::setImageBitmap, () -> url.equals(view.getTag()));
    }

    void release() {
        released = true;
        images.clear();
        main.removeCallbacksAndMessages(null);
    }
}
