package com.ravenemu.app.ui.player;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.opengl.GLSurfaceView;
import android.os.Looper;
import com.ravenemu.app.bridge.FakeAchievements;
import com.ravenemu.app.data.library.RomLibrary;
import com.ravenemu.app.data.saves.SaveStorage;
import com.ravenemu.app.platform.ClearSealer;
import com.ravenemu.app.platform.FakeHttp;
import com.ravenemu.app.settings.PlaybackSettings;
import com.ravenemu.app.settings.ShaderSettings;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowToast;
import org.robolectric.util.ReflectionHelpers;

/** Settings → Vidéo → Shaders in the player, with the native driver stood in
 * for: what it is asked, on which thread, and what the player is told. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36, qualifiers = "en-rUS-w320dp-h568dp-mdpi",
        shadows = {PlayerActivityTest.QueuedGl.class, PlayerActivityTest.FakeNative.class,
                FakeAchievements.class, ClearSealer.class, FakeHttp.class})
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class PlayerShadersTest {
    private static final String PRESET = "pack/crt/crt-test.glslp";
    private final Context context = RuntimeEnvironment.getApplication();
    private ActivityController<PlayerActivity> controller;
    private PlayerActivity activity;
    private final Driver driver = new Driver();
    private PlayerShaders shaders;

    /** The native driver: every call, with the GL thread's marked. */
    static final class Driver implements PlayerShaders.Driver {
        final List<String> calls = Collections.synchronizedList(new ArrayList<>());
        volatile String prepareError, applyError;
        volatile List<String> luts = Collections.emptyList();

        @Override public String prepare(String folder, String preset) {
            calls.add("prepare " + new File(folder).getName() + " " + preset + (PlayerActivityTest.QueuedGl.executing ? " (GL)" : ""));
            return prepareError;
        }
        @Override public List<String> lutPaths() { return luts; }
        @Override public boolean setLut(int index, int width, int height, int[] argb) {
            calls.add("lut " + index + " " + width + "x" + height + " " + Integer.toHexString(argb[0]));
            return true;
        }
        @Override public void setParameter(String name, float value) { calls.add("parameter " + name + " " + value); }
        @Override public String apply() {
            calls.add("apply" + (PlayerActivityTest.QueuedGl.executing ? " (GL)" : ""));
            return applyError;
        }
        @Override public void clear() { calls.add("clear" + (PlayerActivityTest.QueuedGl.executing ? " (GL)" : "")); }
    }

    @Before public void open() throws Exception {
        PlayerActivityTest.FakeNative.reset();
        FakeAchievements.reset();
        FakeHttp.reset();
        context.getSharedPreferences(PlaybackSettings.PREFERENCES, 0).edit().clear().commit();
        String key = RomLibrary.importRom(context.getFilesDir(), "Libre.gb", new byte[0x180]).key;
        controller = Robolectric.buildActivity(PlayerActivity.class, new Intent(context, PlayerActivity.class)
                .putExtra(PlayerActivity.EXTRA_ROM_KEY, key)).create().start().resume().visible();
        activity = controller.get();
        pump();
        shaders = new PlayerShaders(activity, driver);
        // Whatever an earlier test left shown: back to the built-in effects.
        shaders.apply(ShaderSettings.DEFAULTS);
        shaders.apply(ShaderSettings.choose(context, "pack/other.glslp"));
        shaders.apply(ShaderSettings.choose(context, ""));
        pump();
        driver.calls.clear();
    }

    @After public void close() throws Exception {
        // The driver's choice is the process's: the next test starts with none.
        shaders.apply(ShaderSettings.choose(context, ""));
        pump();
        controller.pause().stop().destroy();
        pump();
    }

    private void pump() throws Exception {
        ExecutorService worker = ReflectionHelpers.getStaticField(PlayerShaders.class, "WORKER");
        GLSurfaceView surface = ReflectionHelpers.getField(activity, "surface");
        PlayerActivityTest.QueuedGl gl = Shadow.extract(surface);
        for (int i = 0; i < 4; i++) {
            worker.submit(() -> {}).get(5, TimeUnit.SECONDS);
            SaveStorage.IO.submit(() -> {}).get(5, TimeUnit.SECONDS);
            gl.drain();
            shadowOf(Looper.getMainLooper()).idle();
        }
    }

    private static void png(File file, int... argb) throws Exception {
        file.getParentFile().mkdirs();
        Bitmap image = Bitmap.createBitmap(argb, argb.length, 1, Bitmap.Config.ARGB_8888);
        try (FileOutputStream out = new FileOutputStream(file)) { image.compress(Bitmap.CompressFormat.PNG, 100, out); }
    }

    @Test public void aChosenPresetIsReadAsideThenCompiledOnTheGlThread() throws Exception {
        png(new File(context.getFilesDir(), "shaders/pack/crt/mask.png"), 0xffff0000, 0xff0000ff);
        driver.luts = Collections.singletonList("pack/crt/mask.png");
        shaders.apply(ShaderSettings.choose(context, PRESET).withParameter(context, "GLOW", 0.5f));
        pump();
        assertEquals(java.util.Arrays.asList("prepare shaders " + PRESET, "lut 0 2x1 ffff0000",
                "parameter GLOW 0.5", "apply (GL)"), driver.calls);
        // Back from the settings with another value: that value only.
        driver.calls.clear();
        shaders.apply(ShaderSettings.load(context).withParameter(context, "GLOW", 1f));
        pump();
        assertEquals(Collections.singletonList("parameter GLOW 1.0"), driver.calls);
        // Unchanged: nothing.
        driver.calls.clear();
        shaders.apply(ShaderSettings.load(context));
        pump();
        assertTrue(driver.calls.isEmpty());
        // None: the built-in effects, on the GL thread.
        shaders.apply(ShaderSettings.choose(context, ""));
        pump();
        assertEquals(Collections.singletonList("clear (GL)"), driver.calls);
    }

    @Test public void aPresetThatFailsIsSaidOnceAndNotCompiled() throws Exception {
        driver.prepareError = "Shader introuvable : pack/crt/x.glsl";
        shaders.apply(ShaderSettings.choose(context, PRESET));
        pump();
        assertEquals("Shader non chargé : Shader introuvable : pack/crt/x.glsl", ShadowToast.getTextOfLatestToast());
        assertFalse(driver.calls.contains("apply (GL)"));
        // Resumed again with the same choice: not tried, not said again.
        ShadowToast.reset();
        driver.calls.clear();
        shaders.apply(ShaderSettings.load(context));
        pump();
        assertTrue(driver.calls.isEmpty());
        assertNull(ShadowToast.getTextOfLatestToast());
    }

    @Test public void aShaderThatDoesNotCompileIsSaid() throws Exception {
        driver.applyError = "Passe 1 (crt.glsl) : le fragment shader ne compile pas";
        shaders.apply(ShaderSettings.choose(context, PRESET));
        pump();
        assertEquals("Shader incompatible : Passe 1 (crt.glsl) : le fragment shader ne compile pas",
                ShadowToast.getTextOfLatestToast());
    }

    @Test public void anUnreadableTextureStopsThePresetBeforeTheDriverCompilesIt() throws Exception {
        driver.luts = Collections.singletonList("pack/crt/missing.png");
        shaders.apply(ShaderSettings.choose(context, PRESET));
        pump();
        assertTrue(ShadowToast.getTextOfLatestToast().startsWith("Shader non chargé : texture illisible"));
        assertFalse(driver.calls.contains("apply (GL)"));
    }

    @Test public void aPlayerOpenedLaterStartsFromWhatTheDriverShows() throws Exception {
        shaders.apply(ShaderSettings.choose(context, PRESET));
        pump();
        driver.calls.clear();
        PlayerShaders next = new PlayerShaders(activity, driver);
        next.apply(ShaderSettings.load(context));
        pump();
        assertTrue("Already shown", driver.calls.isEmpty());
        next.apply(ShaderSettings.choose(context, ""));
        pump();
        assertEquals(Collections.singletonList("clear (GL)"), driver.calls);
    }
}
