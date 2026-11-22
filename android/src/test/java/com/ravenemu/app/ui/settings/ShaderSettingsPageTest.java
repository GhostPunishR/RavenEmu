package com.ravenemu.app.ui.settings;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import com.ravenemu.app.bridge.FakeAchievements;
import com.ravenemu.app.data.shaders.ShaderPacks;
import com.ravenemu.app.platform.ClearSealer;
import com.ravenemu.app.settings.PlaybackSettings;
import com.ravenemu.app.settings.ShaderSettings;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
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
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowToast;
import org.robolectric.util.ReflectionHelpers;

/** Settings → Vidéo → Shaders: a pack imported, a preset chosen through its
 * folder, its parameters without the native library, a pack deleted. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36, qualifiers = "fr-rFR-w320dp-h640dp-mdpi", shadows = {FakeAchievements.class, ClearSealer.class})
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class ShaderSettingsPageTest {
    private final Context context = RuntimeEnvironment.getApplication();
    private ActivityController<PlaybackSettingsActivity> controller;

    @Before public void prepare() {
        context.getSharedPreferences(PlaybackSettings.PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit();
        deleteTree(new File(context.getFilesDir(), ShaderPacks.FOLDER));
    }

    @After public void close() throws Exception {
        if (controller != null) controller.pause().stop().destroy();
        deleteTree(new File(context.getFilesDir(), ShaderPacks.FOLDER));
    }

    private PlaybackSettingsActivity open(String page) throws Exception {
        controller = Robolectric.buildActivity(PlaybackSettingsActivity.class,
                new Intent(context, PlaybackSettingsActivity.class).putExtra(PlaybackSettingsActivity.EXTRA_PAGE, page))
                .setup();
        settle(controller.get());
        return controller.get();
    }

    private File zip(String... namesAndContents) throws Exception {
        File file = new File(context.getCacheDir(), "glsl-shaders.zip");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file))) {
            for (int i = 0; i < namesAndContents.length; i += 2) {
                zip.putNextEntry(new ZipEntry(namesAndContents[i]));
                zip.write(namesAndContents[i + 1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return file;
    }

    @Test public void theVideoPageLeadsToTheShadersWhichStartWithNone() throws Exception {
        PlaybackSettingsActivity activity = open(PlaybackSettingsActivity.VIDEO);
        described(activity, "Ouvrir les réglages shaders").performClick();
        settle(activity);
        assertEquals("Shader : Aucun (effets intégrés)", ((TextView) tagged(activity, "shaders.current")).getText());
        assertNotNull(text(activity, "Aucun pack importé."));
        assertFalse(button(activity, "Choisir un preset…").isEnabled());
        assertTrue("Looking writes nothing", context.getSharedPreferences(PlaybackSettings.PREFERENCES,
                Context.MODE_PRIVATE).getAll().isEmpty());
    }

    @Test public void anImportedPackGivesItsPresetsFolderByFolder() throws Exception {
        PlaybackSettingsActivity activity = open(PlaybackSettingsActivity.VIDEO_SHADERS);
        File archive = zip("glsl-shaders/crt/crt-geom.glslp", "shaders = 1\nshader0 = crt-geom.glsl\n",
                "glsl-shaders/crt/crt-geom.glsl", "void main() {}",
                "glsl-shaders/handheld/lcd3x.glslp", "shaders = 1\nshader0 = lcd3x.glsl\n");
        button(activity, "Importer un .zip").performClick();
        Intent request = shadowOf((Activity) activity).getNextStartedActivity();
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, request.getAction());
        shadowOf((Activity) activity).receiveResult(request, Activity.RESULT_OK,
                new Intent().setData(Uri.fromFile(archive)));
        settle(activity);
        assertEquals("Pack importé : glsl-shaders", ShadowToast.getTextOfLatestToast());
        assertNotNull(text(activity, "glsl-shaders"));
        assertNotNull(text(activity, "2 presets"));

        button(activity, "Choisir un preset…").performClick();
        AlertDialog folders = (AlertDialog) ShadowDialog.getLatestDialog();
        ListView list = folders.getListView();
        assertEquals("glsl-shaders/crt (1)", list.getAdapter().getItem(0));
        assertEquals("glsl-shaders/handheld (1)", list.getAdapter().getItem(1));
        shadowOf(list).performItemClick(0);
        AlertDialog presets = (AlertDialog) ShadowDialog.getLatestDialog();
        assertEquals("crt-geom", presets.getListView().getAdapter().getItem(0));
        shadowOf(presets.getListView()).performItemClick(0);
        settle(activity);
        assertEquals("glsl-shaders/crt/crt-geom.glslp", ShaderSettings.load(context).preset);
        assertEquals("Shader : glsl-shaders/crt/crt-geom.glslp",
                ((TextView) tagged(activity, "shaders.current")).getText());

        // Without the native library (Robolectric), its parameters say so.
        described(activity, "Ouvrir les réglages paramètres du shader").performClick();
        settle(activity);
        assertNotNull(text(activity, "Paramètres indisponibles sur cet appareil"));
        described(activity, "Retour aux réglages shaders").performClick();
        settle(activity);

        button(activity, "Aucun shader : effets intégrés").performClick();
        settle(activity);
        assertEquals("", ShaderSettings.load(context).preset);
    }

    @Test public void deletingThePackOfTheChosenPresetBringsTheBuiltInEffectsBack() throws Exception {
        File pack = new File(context.getFilesDir(), "shaders/mine/crt");
        pack.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(pack, "a.glslp"))) {
            out.write("shaders = 1\n".getBytes(StandardCharsets.UTF_8));
        }
        ShaderSettings.choose(context, "mine/crt/a.glslp");
        PlaybackSettingsActivity activity = open(PlaybackSettingsActivity.VIDEO_SHADERS);
        described(activity, "Supprimer le pack mine").performClick();
        AlertDialog confirm = (AlertDialog) ShadowDialog.getLatestDialog();
        confirm.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        settle(activity);
        assertFalse(new File(context.getFilesDir(), "shaders/mine").exists());
        assertEquals("", ShaderSettings.load(context).preset);
        assertNotNull(text(activity, "Aucun pack importé."));
    }

    @Test public void theVideoResetDropsTheShaderToo() throws Exception {
        ShaderSettings.choose(context, "mine/crt/a.glslp");
        PlaybackSettingsActivity activity = open(PlaybackSettingsActivity.VIDEO);
        button(activity, "Rétablir l'affichage d'origine").performClick();
        assertEquals("", ShaderSettings.load(context).preset);
    }

    private static void settle(PlaybackSettingsActivity activity) throws Exception {
        for (int round = 0; round < 5; round++) {
            ExecutorService io = ReflectionHelpers.getField(activity, "io");
            io.submit(() -> {}).get(10, TimeUnit.SECONDS);
            shadowOf(android.os.Looper.getMainLooper()).idle();
        }
    }

    private static Button button(PlaybackSettingsActivity activity, String label) {
        Button view = (Button) find(activity, candidate -> candidate instanceof Button
                && label.contentEquals(((Button) candidate).getText()));
        assertNotNull(label, view);
        return view;
    }

    private static View tagged(PlaybackSettingsActivity activity, String tag) {
        View view = find(activity, candidate -> tag.equals(candidate.getTag()));
        assertNotNull(tag, view);
        return view;
    }

    private static View described(PlaybackSettingsActivity activity, String description) {
        View view = find(activity, candidate -> description.contentEquals(
                candidate.getContentDescription() == null ? "" : candidate.getContentDescription()));
        assertNotNull(description, view);
        return view;
    }

    private static View text(PlaybackSettingsActivity activity, String value) {
        View view = find(activity, candidate -> candidate instanceof TextView
                && value.contentEquals(((TextView) candidate).getText()));
        assertNotNull(value, view);
        return view;
    }

    private static View find(PlaybackSettingsActivity activity, Predicate<View> match) {
        return find(activity.getWindow().getDecorView(), match);
    }

    private static View find(View root, Predicate<View> match) {
        if (match.test(root)) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = find(group.getChildAt(i), match);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        file.delete();
    }
}
