package com.ravenemu.nativepreview;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ScrollView;
import android.widget.TextView;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowPopupMenu;
import org.robolectric.util.ReflectionHelpers;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(org.robolectric.RobolectricTestRunner.class)
@Config(sdk = 36, qualifiers = "en-rUS-w320dp-h568dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class NativeHomeActivityTest {
    private final List<ActivityController<NativeHomeActivity>> activities = new ArrayList<>();

    @After public void closeActivities() {
        for (ActivityController<NativeHomeActivity> activity : activities)
            activity.pause().stop().destroy();
    }

    @Test public void recreationKeepsQueryConsoleAndListMode() throws Exception {
        seedLibrary();
        ActivityController<NativeHomeActivity> first = open(null);
        View root = first.get().getWindow().getDecorView();
        described(root, "Game Boy Color").performClick();
        find(root, EditText.class).setText("Bêta");
        described(root, "Plus d'options").performClick();
        ShadowPopupMenu.getLatestPopupMenu().getMenu().performIdentifierAction(0, 0);
        Bundle saved = saveAndDestroy(first);

        ActivityController<NativeHomeActivity> restored = open(saved);
        root = restored.get().getWindow().getDecorView();
        assertEquals("Bêta", find(root, EditText.class).getText().toString());
        assertNotNull(text(root, "Game Boy Color"));
        GridView grid = find(root, GridView.class);
        assertEquals(1, grid.getNumColumns());
        assertEquals(1, grid.getAdapter().getCount());
        assertEquals("gbc", ((RomLibrary.Entry) grid.getAdapter().getItem(0)).system);
    }

    @Test public void coverPickerStillTargetsTheSameRomAfterRecreation() throws Exception {
        String key = seedLibrary();
        ActivityController<NativeHomeActivity> first = open(null);
        showFirstGame(first.get());
        text(ShadowAlertDialog.getLatestAlertDialog().getWindow().getDecorView(),
                "Image manuelle (facultatif)").performClick();
        assertEquals(Intent.ACTION_OPEN_DOCUMENT,
                shadowOf(first.get()).getNextStartedActivityForResult().intent.getAction());
        Bundle saved = saveAndDestroy(first);
        ActivityController<NativeHomeActivity> restored = open(saved);

        File image = new File(restored.get().getCacheDir(), "chosen.png");
        Bitmap bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888);
        try (FileOutputStream out = new FileOutputStream(image)) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out));
        } finally { bitmap.recycle(); }
        restored.get().onActivityResult(72, Activity.RESULT_OK,
                new Intent().setData(Uri.fromFile(image)));
        idleIo(restored.get());
        Bitmap stored = CoverRepository.load(restored.get().getFilesDir(), key, 64);
        assertNotNull("The selected cover must not be silently lost after recreation", stored);
        stored.recycle();
    }

    @Test public void finishingDuringAnImportDoesNotPostToTheStoppedExecutor() throws Exception {
        ActivityController<NativeHomeActivity> controller = open(null);
        NativeHomeActivity activity = controller.get();
        File rom = new File(activity.getCacheDir(), "pending.gb");
        try (FileOutputStream out = new FileOutputStream(rom)) { out.write(new byte[0x180]); }
        ExecutorService io = ReflectionHelpers.getField(activity, "io");
        CountDownLatch release = new CountDownLatch(1);
        io.submit(() -> {
            try { release.await(); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        });
        try {
            activity.onActivityResult(71, Activity.RESULT_OK,
                    new Intent().setData(Uri.fromFile(rom)));
            saveAndDestroy(controller);
        } finally { release.countDown(); }
        assertTrue(io.awaitTermination(5, TimeUnit.SECONDS));
        // Run the old Activity's completion callbacks after its executor stopped.
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, RomLibrary.list(activity.getFilesDir()).size());
    }

    @Test @Config(qualifiers = "en-rUS-w568dp-h320dp-land-mdpi")
    public void landscapeDetailsKeepThePlayActionInAScrollablePanel() throws Exception {
        String key = seedLibrary();
        NativeHomeActivity activity = open(null).get();
        showFirstGame(activity);
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        View play = text(dialog.getWindow().getDecorView(), "Jouer");
        View parent = play;
        while (parent != null && !(parent instanceof ScrollView))
            parent = parent.getParent() instanceof View ? (View) parent.getParent() : null;
        assertNotNull("Short screens and large text must allow scrolling to Play", parent);
        play.performClick();
        assertEquals(key, shadowOf(activity).getNextStartedActivity()
                .getStringExtra(MainActivity.EXTRA_ROM_KEY));
    }

    private String seedLibrary() throws Exception {
        File files = RuntimeEnvironment.getApplication().getFilesDir();
        RomLibrary.Entry first = RomLibrary.importRom(files, "Alpha.gb", new byte[0x180]);
        byte[] color = new byte[0x180]; color[0x143] = (byte) 0x80;
        RomLibrary.importRom(files, "Bêta.gbc", color);
        return first.key;
    }

    private ActivityController<NativeHomeActivity> open(Bundle saved) throws Exception {
        ActivityController<NativeHomeActivity> c = Robolectric.buildActivity(NativeHomeActivity.class)
                .create(saved).start().resume().visible();
        activities.add(c);
        idleIo(c.get());
        return c;
    }

    private Bundle saveAndDestroy(ActivityController<NativeHomeActivity> c) {
        Bundle saved = new Bundle();
        c.saveInstanceState(saved).pause().stop().destroy();
        activities.remove(c);
        return saved;
    }

    private static void idleIo(NativeHomeActivity activity) throws Exception {
        ExecutorService io = ReflectionHelpers.getField(activity, "io");
        io.submit(() -> {}).get(5, TimeUnit.SECONDS);
        shadowOf(Looper.getMainLooper()).idle();
    }

    private static void showFirstGame(NativeHomeActivity activity) {
        GridView grid = find(activity.getWindow().getDecorView(), GridView.class);
        grid.performItemClick(grid.getAdapter().getView(0, null, grid), 0, 0);
    }

    private static View described(View root, String label) {
        View result = matching(root, v -> label.contentEquals(v.getContentDescription() == null ? "" : v.getContentDescription()));
        assertNotNull(label, result);
        return result;
    }

    private static View text(View root, String label) {
        return matching(root, v -> v instanceof TextView && label.contentEquals(((TextView) v).getText()));
    }

    private static <T extends View> T find(View root, Class<T> type) {
        return type.cast(matching(root, type::isInstance));
    }

    private static View matching(View root, java.util.function.Predicate<View> predicate) {
        if (predicate.test(root)) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = matching(group.getChildAt(i), predicate);
                if (found != null) return found;
            }
        }
        return null;
    }
}
