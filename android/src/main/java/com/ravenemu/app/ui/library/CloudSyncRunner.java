package com.ravenemu.app.ui.library;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.Toast;
import com.ravenemu.app.BuildConfig;
import com.ravenemu.app.data.library.RomLibrary;
import com.ravenemu.app.data.saves.CloudSaves;
import com.ravenemu.app.data.saves.CloudSync;
import com.ravenemu.app.data.saves.CloudSyncState;
import com.ravenemu.app.platform.WebDavHttp;
import com.ravenemu.app.settings.CloudAccount;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Runs the cloud sync of the cartridge saves off the UI thread, one at a
 * time for the whole app: by itself when the library shows (at launch, and
 * when a game closes), or when the player asks in the settings. Listeners
 * hear on the UI thread when a sync starts and ends. */
public final class CloudSyncRunner {
    /** Called on the UI thread when a sync starts or ends. */
    public interface Listener { void cloudSyncChanged(); }

    /** By itself, at most this often, unless a game just closed. */
    static final long AUTO_INTERVAL_MILLIS = 2 * 60_000L;
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(work -> {
        Thread thread = new Thread(work, "RavenEmu cloud");
        thread.setDaemon(true);
        return thread;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    /** The player's answers to conflicts, taken by the next sync. */
    private static final Map<String, CloudSync.Resolution> ANSWERS = new ConcurrentHashMap<>();
    /** UI thread only. */
    private static int active;
    private static long lastAutomatic;

    private CloudSyncRunner() {}

    public static void addListener(Listener listener) { LISTENERS.add(listener); }

    public static void removeListener(Listener listener) { LISTENERS.remove(listener); }

    /** UI thread: a sync is queued or running. */
    public static boolean running() { return active > 0; }

    /** UI thread, the library shown: a sync by itself, if the account wants one. */
    public static void libraryShown(Context context) {
        boolean played = CloudSaves.clearInPlay();
        long now = SystemClock.elapsedRealtime();
        if (!played && lastAutomatic != 0 && now - lastAutomatic < AUTO_INTERVAL_MILLIS) return;
        lastAutomatic = now;
        start(context, true);
    }

    /** UI thread: "Synchroniser maintenant". */
    public static void now(Context context) { start(context, false); }

    /** UI thread: the player settled a conflict; the next sync applies it if
     * the two saves are still those the player saw. */
    public static void resolve(Context context, CloudSyncState.Conflict conflict, boolean keepPhone) {
        ANSWERS.put(conflict.key, new CloudSync.Resolution(keepPhone, conflict.phoneSha256, conflict.cloudSha256));
        start(context, false);
    }

    private static void start(Context context, boolean automatic) {
        final Context app = context.getApplicationContext();
        active++;
        notifyListeners();
        WORKER.execute(() -> {
            try {
                sync(app, automatic);
            } finally {
                MAIN.post(() -> {
                    active--;
                    notifyListeners();
                });
            }
        });
    }

    private static void notifyListeners() {
        for (Listener listener : LISTENERS) listener.cloudSyncChanged();
    }

    /** Worker thread. */
    private static void sync(Context app, boolean automatic) {
        CloudAccount account = CloudAccount.load(app);
        if (account == null || automatic && !account.automatic) return;
        File files = app.getFilesDir();
        CloudSyncState before = CloudSyncState.load(files, account.fingerprint());
        List<String> keys = new ArrayList<>();
        try {
            for (RomLibrary.Entry entry : RomLibrary.list(files)) keys.add(entry.key);
        } catch (IOException unreadable) {
            return;
        }
        Map<String, CloudSync.Resolution> answers = new HashMap<>(ANSWERS);
        CloudSyncState after;
        try {
            WebDavHttp store = new WebDavHttp(account.folder, account.user, account.password, userAgent());
            after = new CloudSync(store, new CloudSaves(files), deviceName()).run(before, keys, answers,
                    System.currentTimeMillis());
        } catch (IllegalArgumentException invalidFolder) {
            return;
        }
        for (Map.Entry<String, CloudSync.Resolution> used : answers.entrySet())
            ANSWERS.remove(used.getKey(), used.getValue());
        try {
            after.save(files);
        } catch (IOException unsaved) {
            // The next sync decides again from the saves themselves.
        }
        if (automatic && newConflicts(before, after) > 0) MAIN.post(() -> Toast.makeText(app,
                "Sauvegardes cloud : conflit à régler dans Paramètres → Sauvegardes cloud", Toast.LENGTH_LONG).show());
    }

    private static int newConflicts(CloudSyncState before, CloudSyncState after) {
        int count = 0;
        for (CloudSyncState.Conflict conflict : after.conflicts) {
            boolean known = false;
            for (CloudSyncState.Conflict old : before.conflicts)
                known |= old.key.equals(conflict.key) && old.phoneSha256.equals(conflict.phoneSha256)
                        && old.cloudSha256.equals(conflict.cloudSha256);
            if (!known) count++;
        }
        return count;
    }

    public static String userAgent() { return "RavenEmu/" + BuildConfig.VERSION_NAME + " (Android)"; }

    /** The name the cloud's list gives this phone, as Android names it. */
    static String deviceName() {
        String maker = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.trim();
        String model = Build.MODEL == null ? "" : Build.MODEL.trim();
        if (!maker.isEmpty() && !model.toLowerCase(Locale.ROOT).startsWith(maker.toLowerCase(Locale.ROOT)))
            model = Character.toUpperCase(maker.charAt(0)) + maker.substring(1) + " " + model;
        return model.isEmpty() ? "Android" : model;
    }
}
