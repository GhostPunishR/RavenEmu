package com.ravenemu.nativepreview;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import java.time.Duration;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.GridView;
import androidx.core.widget.NestedScrollView;
import android.widget.TextView;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowDialog;
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
        text(ShadowDialog.getLatestDialog().getWindow().getDecorView(),
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
        Dialog dialog = ShadowDialog.getLatestDialog();
        View play = text(dialog.getWindow().getDecorView(), "Jouer");
        View parent = play;
        while (parent != null && !(parent instanceof NestedScrollView))
            parent = parent.getParent() instanceof View ? (View) parent.getParent() : null;
        assertNotNull("Short screens and large text must allow scrolling to Play", parent);
        play.performClick();
        assertEquals(key, shadowOf(activity).getNextStartedActivity()
                .getStringExtra(MainActivity.EXTRA_ROM_KEY));
    }


    @Test public void expandedSheetAndSelectedGameSurviveRecreationAndBackDismisses() throws Exception {
        seedLibrary();
        ActivityController<NativeHomeActivity> first = open(null);
        showFirstGame(first.get());
        GameDetailsSheet sheet = (GameDetailsSheet) ShadowDialog.getLatestDialog();
        layoutSheet(sheet);
        assertEquals(BottomSheetBehavior.STATE_EXPANDED, sheet.getBehavior().getState());
        assertTrue(sheet.getBehavior().getSkipCollapsed());
        Bundle saved = saveAndDestroy(first);
        open(saved);
        GameDetailsSheet restored = (GameDetailsSheet) ShadowDialog.getLatestDialog();
        assertNotSame(sheet, restored);
        assertTrue(restored.isShowing());
        assertNotNull(text(restored.getWindow().getDecorView(), "Alpha.gb"));
        restored.onBackPressed();
        assertFalse(restored.isShowing());
    }

    @Test public void pendingSheetSurvivesAnotherRecreationBeforeCatalogueLoads() throws Exception {
        String key = seedLibrary();
        Bundle state = new Bundle();
        state.putString("library.details", key);
        ActivityController<NativeHomeActivity> loading = Robolectric.buildActivity(NativeHomeActivity.class);
        ExecutorService io = ReflectionHelpers.getField(loading.get(), "io");
        CountDownLatch release = new CountDownLatch(1);
        io.submit(() -> { try { release.await(5, TimeUnit.SECONDS); }
                         catch (InterruptedException e) { Thread.currentThread().interrupt(); } });
        Bundle saved;
        try {
            loading.create(state).start().resume().visible();
            activities.add(loading);
            saved = saveAndDestroy(loading);
        } finally { release.countDown(); }
        open(saved);
        Dialog restored = ShadowDialog.getLatestDialog();
        assertTrue(restored.isShowing());
        assertNotNull(text(restored.getWindow().getDecorView(), "Alpha.gb"));
    }

    @Test public void firstTouchOnPlayLaunchesOnceWithoutAHalfExpandedStep() throws Exception {
        String key = seedLibrary();
        NativeHomeActivity activity = open(null).get();
        showFirstGame(activity);
        GameDetailsSheet sheet = (GameDetailsSheet) ShadowDialog.getLatestDialog();
        showFirstGame(activity);
        assertSame(sheet, ShadowDialog.getLatestDialog());
        View decor = layoutSheet(sheet);
        NestedScrollView scroll = find(decor, NestedScrollView.class);
        scroll.setSmoothScrollingEnabled(false);
        scroll.fullScroll(View.FOCUS_DOWN);
        View play = text(decor, "Jouer");
        int[] place = new int[2], origin = new int[2];
        play.getLocationOnScreen(place); decor.getLocationOnScreen(origin);
        float x = place[0] - origin[0] + play.getWidth() / 2f;
        float y = place[1] - origin[1] + play.getHeight() / 2f;
        android.graphics.Rect visible = new android.graphics.Rect();
        assertTrue("Play must be visible: " + place[0] + "," + place[1]
                + " size=" + play.getWidth() + "x" + play.getHeight()
                + " scroll=" + scroll.getScrollY(), play.getGlobalVisibleRect(visible));
        long down = SystemClock.uptimeMillis();
        touch(decor, down, down, MotionEvent.ACTION_DOWN, x, y);
        touch(decor, down, down + 40, MotionEvent.ACTION_UP, x, y);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200));
        Intent launched = shadowOf(activity).getNextStartedActivity();
        assertNotNull("One touch must reach Play", launched);
        assertEquals(key, launched.getStringExtra(MainActivity.EXTRA_ROM_KEY));
        assertNull(shadowOf(activity).getNextStartedActivity());
    }

    @Test public void downwardDragDismissesTheSheet() throws Exception {
        seedLibrary();
        NativeHomeActivity activity = open(null).get();
        showFirstGame(activity);
        GameDetailsSheet sheet = (GameDetailsSheet) ShadowDialog.getLatestDialog();
        View decor = layoutSheet(sheet);
        View panel = sheet.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        int[] place = new int[2], origin = new int[2];
        panel.getLocationOnScreen(place); decor.getLocationOnScreen(origin);
        float x = place[0] - origin[0] + panel.getWidth() / 2f;
        float y = place[1] - origin[1] + 16;
        long down = SystemClock.uptimeMillis();
        touch(decor, down, down, MotionEvent.ACTION_DOWN, x, y);
        for (int i = 1; i <= 4; i++)
            touch(decor, down, down + i * 20, MotionEvent.ACTION_MOVE, x, y + i * 70);
        touch(decor, down, down + 100, MotionEvent.ACTION_UP, x, y + 300);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
        assertFalse("A downward swipe must close the panel", sheet.isShowing());
    }

    private static View layoutSheet(GameDetailsSheet sheet) {
        View decor = sheet.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(568, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, 320, 568);
        // CoordinatorLayout requests a second pass after positioning the sheet.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500));
        decor.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(568, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, 320, 568);
        return decor;
    }

    private static void touch(View view, long down, long time, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(down, time, action, x, y, 0);
        try { view.dispatchTouchEvent(event); } finally { event.recycle(); }
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
