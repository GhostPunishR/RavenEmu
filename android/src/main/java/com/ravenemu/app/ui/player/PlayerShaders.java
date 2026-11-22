package com.ravenemu.app.ui.player;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.widget.Toast;
import com.ravenemu.app.bridge.NativeShaders;
import com.ravenemu.app.data.shaders.ShaderPacks;
import com.ravenemu.app.settings.ShaderSettings;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Settings → Vidéo → Shaders in the player: the chosen RetroArch preset read
 * off the UI thread (its shaders by the native driver, its images here),
 * then compiled on the GL thread; its parameters follow the settings. A
 * preset that fails is said once, and the built-in effects stay. */
final class PlayerShaders {
    /** The native driver, apart for the tests. */
    interface Driver {
        String prepare(String folder, String preset);
        List<String> lutPaths();
        boolean setLut(int index, int width, int height, int[] argb);
        void setParameter(String name, float value);
        /** GL thread. */
        String apply();
        /** GL thread. */
        void clear();
    }

    static final Driver NATIVE = new Driver() {
        @Override public String prepare(String folder, String preset) { return NativeShaders.prepare(folder, preset); }
        @Override public List<String> lutPaths() { return NativeShaders.lutPaths(); }
        @Override public boolean setLut(int index, int width, int height, int[] argb) {
            return NativeShaders.setLut(index, width, height, argb);
        }
        @Override public void setParameter(String name, float value) { NativeShaders.setParameter(name, value); }
        @Override public String apply() { return NativeShaders.apply(); }
        @Override public void clear() { NativeShaders.clear(); }
    };

    /** The largest lookup texture read: the native driver takes no more. */
    static final int MAX_LUT_SIDE = 2048;
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(work -> {
        Thread thread = new Thread(work, "RavenEmu shaders");
        thread.setDaemon(true);
        return thread;
    });

    private final PlayerActivity activity;
    private final Driver driver;
    /** UI thread: the preset last asked of the native driver, and its values
     * sent. The driver keeps its preset for the whole process, whichever
     * player shows it: a player opened later starts from what it shows. */
    private static String preset = "";
    private static final Map<String, Float> sent = new HashMap<>();
    private static int request;

    PlayerShaders(PlayerActivity activity) { this(activity, NATIVE); }

    PlayerShaders(PlayerActivity activity, Driver driver) {
        this.activity = activity;
        this.driver = driver;
    }

    /** UI thread, as the player resumes: another preset, or new values. */
    void apply(ShaderSettings settings) {
        if (settings.preset.equals(preset)) {
            for (Map.Entry<String, Float> value : settings.parameters.entrySet()) {
                if (value.getValue().equals(sent.get(value.getKey()))) continue;
                sent.put(value.getKey(), value.getValue());
                driver.setParameter(value.getKey(), value.getValue());
            }
            return;
        }
        preset = settings.preset;
        sent.clear();
        final int id = ++request;
        if (preset.isEmpty()) {
            queueGl(driver::clear);
            return;
        }
        final File folder = new ShaderPacks(activity.getFilesDir()).root();
        final String chosen = preset;
        final Map<String, Float> values = new HashMap<>(settings.parameters);
        sent.putAll(values);
        WORKER.execute(() -> {
            String failure = driver.prepare(folder.getAbsolutePath(), chosen);
            if (failure == null) failure = readLuts(folder);
            if (failure == null) for (Map.Entry<String, Float> value : values.entrySet())
                driver.setParameter(value.getKey(), value.getValue());
            final String error = failure;
            activity.runOnUiThread(() -> {
                if (activity.disposed.get() || id != request) return;
                if (error != null) {
                    say("Shader non chargé : " + error);
                    return;
                }
                queueGl(() -> {
                    String compile = driver.apply();
                    if (compile != null) activity.runOnUiThread(() -> {
                        if (!activity.disposed.get() && id == request) say("Shader incompatible : " + compile);
                    });
                });
            });
        });
    }

    /** Worker thread: each lookup texture as ARGB pixels; null, or why not. */
    private String readLuts(File folder) {
        List<String> paths = driver.lutPaths();
        for (int index = 0; index < paths.size(); index++) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            options.inScaled = false;
            options.inJustDecodeBounds = true;
            File file = new File(folder, paths.get(index));
            BitmapFactory.decodeFile(file.getPath(), options);
            if (options.outWidth <= 0 || options.outHeight <= 0 || options.outWidth > MAX_LUT_SIDE
                    || options.outHeight > MAX_LUT_SIDE) return "texture illisible ou trop grande : " + paths.get(index);
            options.inJustDecodeBounds = false;
            Bitmap image = BitmapFactory.decodeFile(file.getPath(), options);
            if (image == null) return "texture illisible : " + paths.get(index);
            int[] pixels = new int[image.getWidth() * image.getHeight()];
            image.getPixels(pixels, 0, image.getWidth(), 0, 0, image.getWidth(), image.getHeight());
            boolean stored = driver.setLut(index, image.getWidth(), image.getHeight(), pixels);
            image.recycle();
            if (!stored) return "texture refusée : " + paths.get(index);
        }
        return null;
    }

    private void queueGl(Runnable work) {
        if (activity.surface == null) return;
        activity.surface.queueEvent(() -> {
            if (!activity.disposed.get()) work.run();
        });
    }

    private void say(String message) {
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
    }
}
