package com.ravenemu.app.ui.player;
import com.ravenemu.app.data.saves.StateSlotRepository;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.ImageView;
import androidx.core.widget.NestedScrollView;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowDialog;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36, qualifiers = "en-rUS-w320dp-h568dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class PlayerMenuSheetTest {
    private ActivityController<Activity> activity;
    private PlayerMenuSheet sheet;
    private final List<String> actions = new ArrayList<>();
    private int dismissed;

    @After public void close() {
        if (sheet != null) sheet.dismiss();
        if (activity != null) activity.pause().stop().destroy();
    }

    @Test public void loadingAndEmptySlotsDisableUnsafeActionsAndSaveOnlyOnce() {
        open(2);
        assertEquals(2, sheet.getSelectedSlot());
        assertFalse(button("Sauver").isEnabled());
        assertFalse(button("Charger").isEnabled());
        assertFalse(button("Supprimer").isEnabled());
        sheet.render(empty(), false);
        assertEquals("Vide", text("Vide").getText().toString());
        assertTrue(button("Sauver").isEnabled());
        assertFalse(button("Charger").isEnabled());
        button("Sauver").performClick();
        button("Sauver").performClick();
        assertEquals(List.of("save:2"), actions);
        assertFalse(button("Sauver").isEnabled());
        assertFalse(choice(0).isEnabled());
    }

    @Test public void overwriteRequiresConfirmationAndCanBeCancelled() {
        open(0);
        StateSlotRepository.Slot[] entries = empty();
        entries[0] = occupied(0, true, false);
        sheet.render(entries, false);
        button("Sauver").performClick();
        AlertDialog first = confirmation();
        assertTrue(first.isShowing());
        assertTrue(actions.isEmpty());
        first.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        idle();
        assertTrue(actions.isEmpty());
        button("Sauver").performClick();
        confirmation().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        idle();
        assertEquals(List.of("save:0"), actions);
        assertTrue("Saving leaves the paused menu open", sheet.isShowing());
    }

    @Test public void switchingSlotsLoadsTheSelectedStateAndDeletionConfirms() {
        open(0);
        StateSlotRepository.Slot[] entries = empty();
        entries[1] = occupied(1, true, false);
        sheet.render(entries, false);
        choice(1).performClick();
        assertEquals(1, sheet.getSelectedSlot());
        assertTrue(choice(1).isSelected());
        assertNotNull(text("Emplacement 2"));
        assertTrue(button("Charger").isEnabled());
        button("Charger").performClick();
        assertEquals(List.of("load:1"), actions);
        sheet.render(entries, false);
        button("Supprimer").performClick();
        assertEquals(1, actions.size());
        confirmation().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        idle();
        assertEquals(List.of("load:1", "delete:1"), actions);
    }

    @Test public void incompatibleOrCorruptStatesCannotLoadButCanBeDeleted() {
        open(0);
        StateSlotRepository.Slot[] entries = empty();
        entries[0] = new StateSlotRepository.Slot(0, true, false, false, 1, "Damaged file");
        sheet.render(entries, false);
        assertFalse(button("Charger").isEnabled());
        button("Charger").performClick();
        assertTrue(actions.isEmpty());
        assertNotNull(text("État incompatible ou endommagé · fichier conservé"));
        assertTrue(button("Supprimer").isEnabled());
        button("Supprimer").performClick();
        confirmation().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        idle();
        assertEquals(List.of("delete:0"), actions);
    }

    @Test public void legacyStatesNeedExplicitConfirmationBeforeLoading() {
        open(0);
        StateSlotRepository.Slot[] entries = empty();
        entries[0] = occupied(0, false, true);
        sheet.render(entries, false);
        assertTrue(button("Charger").isEnabled());
        button("Charger").performClick();
        assertTrue(actions.isEmpty());
        confirmation().getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        idle();
        assertTrue(actions.isEmpty());
        button("Charger").performClick();
        confirmation().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        idle();
        assertEquals(List.of("load:0"), actions);
    }

    @Test public void staleConfirmationCannotActAfterRefreshBusyOrDismiss() {
        open(0);
        StateSlotRepository.Slot[] entries = empty();
        entries[0] = occupied(0, true, false);
        sheet.render(entries, false);
        button("Sauver").performClick();
        AlertDialog stale = confirmation();
        sheet.render(empty(), false);
        assertFalse(stale.isShowing());
        stale.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        idle();
        assertTrue(actions.isEmpty());
        sheet.render(entries, false);
        button("Sauver").performClick();
        stale = confirmation();
        sheet.render(entries, true);
        stale.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        idle();
        assertTrue(actions.isEmpty());
        sheet.render(entries, false);
        button("Supprimer").performClick();
        stale = confirmation();
        sheet.dismiss();
        idle();
        assertFalse(stale.isShowing());
        stale.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        idle();
        assertTrue(actions.isEmpty());
        assertEquals(1, dismissed);
    }

    @Test public void resumeAndBackDismissOnceWithoutInvokingStateActions() {
        open(0);
        button("Reprendre le jeu").performClick();
        assertFalse(sheet.isShowing());
        sheet.dismiss();
        idle();
        assertEquals(1, dismissed);
        assertTrue(actions.isEmpty());
        sheet.show();
        ShadowDialog.getLatestDialog().onBackPressed();
        idle();
        assertFalse(sheet.isShowing());
        assertEquals(2, dismissed);
    }

    @Test public void controllerAActivatesTheFocusedSlotAndBCloses() {
        open(0);
        Dialog dialog = ShadowDialog.getLatestDialog();
        layout(dialog, 320, 568);
        sheet.render(empty(), false);
        assertTrue(choice(2).requestFocusFromTouch());
        dialog.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A));
        dialog.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_A));
        assertEquals(2, sheet.getSelectedSlot());
        dialog.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_B));
        dialog.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_B));
        idle();
        assertFalse(sheet.isShowing());
        assertEquals(1, dismissed);
    }

    @Test public void previewsFollowSelectionWithoutFilteringAndNeverOutliveARefresh() {
        open(0);
        StateSlotRepository.Slot[] entries = empty();
        entries[0] = occupied(0, true, false);
        entries[1] = occupied(1, true, false);
        entries[3] = new StateSlotRepository.Slot(3, true, false, false, 1, "Damaged state");
        Bitmap first = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
        Bitmap second = Bitmap.createBitmap(2, 1, Bitmap.Config.ARGB_8888);
        sheet.render(entries, false);
        sheet.setPreviews(new Bitmap[]{first, second, first, first});
        ImageView image = image();
        assertEquals(ImageView.ScaleType.FIT_CENTER, image.getScaleType());
        assertEquals(View.VISIBLE, image.getVisibility());
        BitmapDrawable shown = (BitmapDrawable) image.getDrawable();
        assertSame(first, shown.getBitmap());
        assertFalse(shown.getPaint().isFilterBitmap());
        choice(1).performClick();
        assertSame(second, ((BitmapDrawable) image.getDrawable()).getBitmap());
        assertEquals("Aperçu de l'emplacement 2", ((View)image.getParent()).getContentDescription());
        choice(2).performClick();
        assertEquals("An empty slot must not show a supplied stale bitmap", View.GONE, image.getVisibility());
        choice(3).performClick();
        assertEquals("A corrupt state must not show a supplied stale bitmap", View.GONE, image.getVisibility());
        choice(0).performClick();
        sheet.render(entries, false);
        assertNull(image.getDrawable());
        assertEquals(View.GONE, image.getVisibility());
        assertEquals("Aperçu de sauvegarde indisponible", ((View)image.getParent()).getContentDescription());
        assertFalse("Sheet does not own/recycle caller bitmaps", first.isRecycled());
        first.recycle(); second.recycle();
    }

    @Test public void undoOnlyAppearsWhenAvailableAndRunsOnceUntilOperationFinishes() {
        open(0);
        sheet.render(empty(), false);
        assertEquals(View.GONE, button("Annuler le chargement").getVisibility());
        sheet.setUndoAvailable(true);
        assertEquals(View.VISIBLE, button("Annuler le chargement").getVisibility());
        sheet.render(empty(), true);
        assertFalse(button("Annuler le chargement").isEnabled());
        button("Annuler le chargement").performClick();
        assertTrue(actions.isEmpty());
        sheet.render(empty(), false);
        Dialog dialog = ShadowDialog.getLatestDialog();
        layout(dialog, 320, 568);
        assertTrue(button("Annuler le chargement").requestFocusFromTouch());
        dialog.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A));
        dialog.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_A));
        button("Annuler le chargement").performClick();
        assertEquals(List.of("undo"), actions);
        assertFalse(button("Annuler le chargement").isEnabled());
        sheet.render(empty(), false);
        sheet.setUndoAvailable(false);
        assertEquals(View.GONE, button("Annuler le chargement").getVisibility());
        button("Annuler le chargement").performClick();
        assertEquals(List.of("undo"), actions);
    }

    @Test @Config(qualifiers = "en-rUS-w568dp-h320dp-land-mdpi")
    public void resumeRemainsScrollableAndRespondsToOneTouchOnAShortScreen() {
        open(0);
        Dialog dialog = ShadowDialog.getLatestDialog();
        sheet.render(empty(), false);
        View decor = layout(dialog, 568, 320);
        NestedScrollView scroll = (NestedScrollView) find(decor, view -> view instanceof NestedScrollView);
        scroll.setSmoothScrollingEnabled(false);
        scroll.fullScroll(View.FOCUS_DOWN);
        View resume = button("Reprendre le jeu");
        int[] position = new int[2], origin = new int[2];
        resume.getLocationOnScreen(position);
        decor.getLocationOnScreen(origin);
        float x = position[0] - origin[0] + resume.getWidth() / 2f;
        float y = position[1] - origin[1] + resume.getHeight() / 2f;
        assertTrue(y > 0 && y < 320);
        long down = SystemClock.uptimeMillis();
        touch(decor, down, down, MotionEvent.ACTION_DOWN, x, y);
        touch(decor, down, down + 40, MotionEvent.ACTION_UP, x, y);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200));
        assertFalse(sheet.isShowing());
        assertEquals(1, dismissed);
    }

    @Test public void settingsKeepThePanelOpenAndAreBlockedDuringSlotOperations() {
        open(0);
        sheet.render(empty(), false);
        text("Paramètres").performClick();
        assertEquals(java.util.Collections.singletonList("settings"), actions);
        assertTrue(sheet.isShowing());
        sheet.render(empty(), true);
        assertFalse(text("Paramètres").isEnabled());
        text("Paramètres").performClick();
        assertEquals(1, actions.size());
    }

    private void open(int selected) {
        activity = Robolectric.buildActivity(Activity.class).setup().visible();
        sheet = new PlayerMenuSheet(activity.get(), "Jeu de test.gba", selected, new PlayerMenuSheet.Listener() {
            @Override public void onSave(int slot) { actions.add("save:" + slot); }
            @Override public void onLoad(int slot) { actions.add("load:" + slot); }
            @Override public void onDelete(int slot) { actions.add("delete:" + slot); }
            @Override public void onDismiss() { dismissed++; }
            @Override public void onUndo() { actions.add("undo"); }
            @Override public void onSettings() { actions.add("settings"); }
        });
        sheet.show();
    }

    private StateSlotRepository.Slot[] empty() {
        StateSlotRepository.Slot[] slots = new StateSlotRepository.Slot[StateSlotRepository.SLOT_COUNT];
        for (int i = 0; i < slots.length; i++) slots[i] = new StateSlotRepository.Slot(i, false, true, false, 0, null);
        return slots;
    }

    private StateSlotRepository.Slot occupied(int index, boolean compatible, boolean legacy) {
        return new StateSlotRepository.Slot(index, true, compatible, legacy, 1_700_000_000_000L, null);
    }

    private void idle() { shadowOf(Looper.getMainLooper()).idle(); }

    private AlertDialog confirmation() { return (AlertDialog) ShadowDialog.getLatestDialog(); }
    private TextView text(String label) {
        View found = find(ShadowDialog.getLatestDialog().getWindow().getDecorView(),
                view -> view instanceof TextView && label.contentEquals(((TextView)view).getText()));
        assertNotNull("Missing " + label, found);
        return (TextView)found;
    }
    private View button(String label) {
        // The last shown dialog may be a dismissed confirmation. Search the
        // still-showing menu instead, never a similarly named confirm button.
        for (Dialog dialog : ShadowDialog.getShownDialogs()) {
            if (!dialog.isShowing() || dialog instanceof AlertDialog) continue;
            View result = find(dialog.getWindow().getDecorView(),
                    view -> view instanceof TextView && label.contentEquals(((TextView)view).getText()));
            if (result != null) return result;
        }
        throw new AssertionError("Missing button " + label);
    }
    private View choice(int index) {
        View result = find(ShadowDialog.getShownDialogs().stream()
                .filter(Dialog::isShowing).filter(d -> !(d instanceof AlertDialog)).findFirst().get().getWindow().getDecorView(),
                view -> view.getContentDescription() != null && view.getContentDescription().toString()
                        .startsWith("Emplacement " + (index + 1) + ","));
        assertNotNull(result);
        return result;
    }
    private ImageView image() {
        Dialog dialog = ShadowDialog.getShownDialogs().stream().filter(Dialog::isShowing)
                .filter(d -> !(d instanceof AlertDialog)).findFirst().get();
        // The save preview, not the account card's avatar above it.
        return (ImageView)find(dialog.getWindow().getDecorView(), view -> view instanceof ImageView
                && view.getParent() instanceof View
                && String.valueOf(((View)view.getParent()).getContentDescription()).startsWith("Aperçu"));
    }
    private static View find(View view, java.util.function.Predicate<View> predicate) {
        if (predicate.test(view)) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup)view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View result = find(group.getChildAt(i), predicate);
                if (result != null) return result;
            }
        }
        return null;
    }
    private static View layout(Dialog dialog, int width, int height) {
        View decor = dialog.getWindow().getDecorView();
        for (int i = 0; i < 2; i++) {
            decor.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            decor.layout(0, 0, width, height);
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500));
        }
        return decor;
    }
    private static void touch(View target, long down, long time, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(down, time, action, x, y, 0);
        try { target.dispatchTouchEvent(event); } finally { event.recycle(); }
    }
}
