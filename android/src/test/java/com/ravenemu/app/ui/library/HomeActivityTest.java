package com.ravenemu.app.ui.library;
import com.ravenemu.app.R;
import com.ravenemu.app.data.library.CoverRepository;
import com.ravenemu.app.data.library.FolderImport;
import com.ravenemu.app.data.library.RomLibrary;
import com.ravenemu.app.ui.common.GameDetailsSheet;
import com.ravenemu.app.ui.common.RavenIcon;
import com.ravenemu.app.ui.player.PlayerActivity;
import com.ravenemu.app.ui.settings.PlaybackSettingsActivity;
import com.ravenemu.app.data.saves.SaveRepository;
import com.ravenemu.app.data.saves.SaveStorage;
import com.ravenemu.app.data.saves.SaveTransfer;
import android.app.AlertDialog;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.json.JSONObject;
import org.robolectric.shadows.ShadowToast;

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
public final class HomeActivityTest {
    private final List<ActivityController<HomeActivity>> activities = new ArrayList<>();

    @After public void closeActivities() {
        for (ActivityController<HomeActivity> activity : activities)
            activity.pause().stop().destroy();
    }

    @Test public void settingsOpenTheRealPageOnItsCategoriesWithoutAFloatingMenu() throws Exception {
        HomeActivity activity = open(null).get();
        View settings = described(activity.getWindow().getDecorView(), "Paramètres");
        settings.performClick();
        shadowOf(Looper.getMainLooper()).idle();
        Intent page = shadowOf(activity).getNextStartedActivity();
        assertEquals(PlaybackSettingsActivity.class.getName(), page.getComponent().getClassName());
        assertNull("The categories, not one page", page.getStringExtra(PlaybackSettingsActivity.EXTRA_PAGE));
        Dialog shown = ShadowDialog.getLatestDialog();
        assertTrue("No floating settings menu", shown == null || !shown.isShowing());
    }

    @Test public void recreationKeepsQueryConsoleAndListMode() throws Exception {
        seedLibrary();
        ActivityController<HomeActivity> first = open(null);
        View root = first.get().getWindow().getDecorView();
        described(root, "Game Boy Color").performClick();
        find(root, EditText.class).setText("Bêta");
        described(root, "Plus d'options").performClick();
        ShadowPopupMenu.getLatestPopupMenu().getMenu().performIdentifierAction(0, 0);
        Bundle saved = saveAndDestroy(first);

        ActivityController<HomeActivity> restored = open(saved);
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
        ActivityController<HomeActivity> first = open(null);
        showFirstGame(first.get());
        text(ShadowDialog.getLatestDialog().getWindow().getDecorView(),
                "Image manuelle (facultatif)").performClick();
        assertEquals(Intent.ACTION_OPEN_DOCUMENT,
                shadowOf(first.get()).getNextStartedActivityForResult().intent.getAction());
        Bundle saved = saveAndDestroy(first);
        ActivityController<HomeActivity> restored = open(saved);

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
        ActivityController<HomeActivity> controller = open(null);
        HomeActivity activity = controller.get();
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
        HomeActivity activity = open(null).get();
        showFirstGame(activity);
        Dialog dialog = ShadowDialog.getLatestDialog();
        View play = text(dialog.getWindow().getDecorView(), "Jouer");
        View parent = play;
        while (parent != null && !(parent instanceof NestedScrollView))
            parent = parent.getParent() instanceof View ? (View) parent.getParent() : null;
        assertNotNull("Short screens and large text must allow scrolling to Play", parent);
        play.performClick();
        assertEquals(key, shadowOf(activity).getNextStartedActivity()
                .getStringExtra(PlayerActivity.EXTRA_ROM_KEY));
    }


    @Test public void playingTwoLinkedGamesOffersOnlyTheGamesThatLink() throws Exception {
        String key = seedLibrary();
        File files = RuntimeEnvironment.getApplication().getFilesDir();
        byte[] advance = new byte[0x200];
        advance[0xb2] = (byte) 0x96;
        RomLibrary.importRom(files, "Gamma.gba", advance);
        String color = RomLibrary.list(files).get(1).key;
        HomeActivity activity = open(null).get();
        showFirstGame(activity);
        Dialog sheet = ShadowDialog.getLatestDialog();
        View linked = text(sheet.getWindow().getDecorView(), "Jouer à deux (câble link)");
        assertNotNull(linked);
        linked.performClick();
        shadowOf(Looper.getMainLooper()).idle();
        Dialog partners = ShadowDialog.getLatestDialog();
        assertNotSame(sheet, partners);
        View decor = partners.getWindow().getDecorView();
        assertNotNull(text(decor, "Bêta.gbc · Game Boy Color"));
        assertNull("A GBA game does not link to a Game Boy one", text(decor, "Gamma.gba · Game Boy Advance"));
        assertNull("A game is never linked to itself", text(decor, "Alpha.gb · Game Boy"));
        text(decor, "Bêta.gbc · Game Boy Color").performClick();
        Intent launched = shadowOf(activity).getNextStartedActivity();
        assertEquals(key, launched.getStringExtra(PlayerActivity.EXTRA_ROM_KEY));
        assertEquals(color, launched.getStringExtra(PlayerActivity.EXTRA_LINK_ROM_KEY));
        assertFalse(partners.isShowing());
    }

    @Test public void expandedSheetAndSelectedGameSurviveRecreationAndBackDismisses() throws Exception {
        seedLibrary();
        ActivityController<HomeActivity> first = open(null);
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
        ActivityController<HomeActivity> loading = Robolectric.buildActivity(HomeActivity.class);
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
        HomeActivity activity = open(null).get();
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
        assertEquals(key, launched.getStringExtra(PlayerActivity.EXTRA_ROM_KEY));
        assertNull(shadowOf(activity).getNextStartedActivity());
    }

    @Test public void downwardDragDismissesTheSheet() throws Exception {
        seedLibrary();
        HomeActivity activity = open(null).get();
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

    @Test public void theSheetExportsTheSaveAsSrmUnderTheRomsName() throws Exception {
        String key = seedLibrary();
        File files = RuntimeEnvironment.getApplication().getFilesDir();
        SaveRepository.write(files, key, "srm", new byte[]{7, 8, 9});
        HomeActivity activity = open(null).get();
        showFirstGame(activity);
        text(sheet(), "Exporter").performClick();
        ShadowPopupMenu.getLatestPopupMenu().getMenu().performIdentifierAction(2, 0);
        idleSaves();
        Intent create = shadowOf(activity).getNextStartedActivityForResult().intent;
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, create.getAction());
        assertEquals("Alpha.srm", create.getStringExtra(Intent.EXTRA_TITLE));
        Uri target = Uri.parse("content://saves/Alpha.srm");
        ByteArrayOutputStream written = new ByteArrayOutputStream();
        shadowOf(activity.getContentResolver()).registerOutputStream(target, written);
        activity.onActivityResult(73, Activity.RESULT_OK, new Intent().setData(target));
        idleSaves();
        assertArrayEquals(new byte[]{7, 8, 9}, written.toByteArray());
        assertEquals("Fichier .srm exporté", ShadowToast.getTextOfLatestToast());
    }

    @Test public void anExportWithoutASaveSaysSoWithoutAPicker() throws Exception {
        seedLibrary();
        HomeActivity activity = open(null).get();
        showFirstGame(activity);
        text(sheet(), "Exporter").performClick();
        ShadowPopupMenu.getLatestPopupMenu().getMenu().performIdentifierAction(1, 0);
        idleSaves();
        assertNull(shadowOf(activity).getNextStartedActivityForResult());
        assertEquals("Ce jeu n'a pas encore de sauvegarde.", ShadowToast.getTextOfLatestToast());
    }

    @Test public void anImportIsReviewedAndReplacesTheSaveOnlyOnceConfirmed() throws Exception {
        String key = seedLibrary();
        File files = RuntimeEnvironment.getApplication().getFilesDir();
        SaveRepository.write(files, key, "srm", new byte[]{1, 2});
        HomeActivity activity = open(null).get();
        AlertDialog review = reviewOf(activity, "Alpha.sav", new byte[]{7, 7, 7});
        View body = review.getWindow().getDecorView();
        assertNotNull(text(body, "Jeu : Alpha.gb"));
        assertNotNull(text(body, "Contenu : sauvegarde du jeu (3 octets)"));
        assertNotNull(text(body, "Un fichier .sav ou .srm seul n'identifie pas sa ROM. "
                + "Vérifiez que vous avez choisi le bon jeu."));
        assertNotNull(text(body, "Des données existantes seront remplacées : sauvegarde du jeu."));
        assertNotNull(text(body, "Le jeu utilise 2 octets : RavenEmu lira le début du fichier, comme le jeu le ferait."));
        assertArrayEquals("Nothing is written before the confirmation",
                new byte[]{1, 2}, SaveRepository.read(files, key, "srm"));
        review.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        idleSaves();
        assertArrayEquals(new byte[]{7, 7, 7}, SaveRepository.read(files, key, "srm"));
        assertEquals("Sauvegardes importées avec succès", ShadowToast.getTextOfLatestToast());
    }

    @Test public void aRefusedFileOrACancelledReviewLeavesTheSaveAlone() throws Exception {
        String key = seedLibrary();
        File files = RuntimeEnvironment.getApplication().getFilesDir();
        SaveRepository.write(files, key, "srm", new byte[]{1, 2});
        HomeActivity activity = open(null).get();
        showFirstGame(activity);
        text(sheet(), "Importer").performClick();
        shadowOf(activity).getNextStartedActivityForResult();
        Uri text = Uri.parse("content://saves/Alpha.txt");
        shadowOf(activity.getContentResolver()).registerInputStream(text, new ByteArrayInputStream(new byte[]{5}));
        activity.onActivityResult(74, Activity.RESULT_OK, new Intent().setData(text));
        idleSaves();
        assertEquals("Import refusé : Sélectionnez une sauvegarde .sav ou .srm, ou une archive .zip RavenEmu.",
                ShadowToast.getTextOfLatestToast());

        AlertDialog review = reviewOf(activity, "Alpha.srm", new byte[]{9, 9});
        review.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        idleSaves();
        assertFalse(review.isShowing());
        assertArrayEquals(new byte[]{1, 2}, SaveRepository.read(files, key, "srm"));
    }

    @Test public void anArchiveIsMatchedToTheRomBytesAndNamesTheStatesLeftOut() throws Exception {
        String key = seedLibrary();
        File files = RuntimeEnvironment.getApplication().getFilesDir();
        byte[] battery = {4, 5, 6};
        byte[] state = {1};
        JSONObject entries = new JSONObject().put("battery.sav", SaveTransfer.sha256(battery))
                .put("slot0.state", SaveTransfer.sha256(state));
        JSONObject manifest = new JSONObject().put("format", "RavenEmu-GB-Saves").put("version", 1)
                .put("romSha256", SaveTransfer.sha256(new byte[0x180])).put("entries", entries);
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(archive)) {
            put(zip, "manifest.json", manifest.toString().getBytes(StandardCharsets.UTF_8));
            put(zip, "battery.sav", battery);
            put(zip, "slot0.state", state);
        }
        HomeActivity activity = open(null).get();
        AlertDialog review = reviewOf(activity, "Alpha.zip", archive.toByteArray());
        View body = review.getWindow().getDecorView();
        assertNotNull(text(body, "États 1 de l'archive non importés : ce sont des états de la bêta, "
                + "liés à son propre core."));
        assertNotNull(text(body, "Les autres sauvegardes resteront inchangées."));
        assertNull("An archive names its ROM", text(body, "Un fichier .sav ou .srm seul n'identifie pas sa ROM. "
                + "Vérifiez que vous avez choisi le bon jeu."));
        review.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        idleSaves();
        assertArrayEquals(battery, SaveRepository.read(files, key, "srm"));
    }

    @Test public void theImportStillTargetsTheSameGameAfterRecreation() throws Exception {
        seedLibrary();
        ActivityController<HomeActivity> first = open(null);
        showFirstGame(first.get());
        text(sheet(), "Importer").performClick();
        assertEquals(Intent.ACTION_OPEN_DOCUMENT,
                shadowOf(first.get()).getNextStartedActivityForResult().intent.getAction());
        HomeActivity restored = open(saveAndDestroy(first)).get();
        Uri source = Uri.parse("content://saves/Alpha.sav");
        shadowOf(restored.getContentResolver()).registerInputStream(source, new ByteArrayInputStream(new byte[]{3}));
        restored.onActivityResult(74, Activity.RESULT_OK, new Intent().setData(source));
        idleSaves();
        AlertDialog review = (AlertDialog) ShadowDialog.getLatestDialog();
        assertTrue(review.isShowing());
        assertNotNull(text(review.getWindow().getDecorView(), "Jeu : Alpha.gb"));
    }

    @Test public void theMenuExportsEverySaveInTheBetasLibraryArchive() throws Exception {
        String key = seedLibrary();
        File files = RuntimeEnvironment.getApplication().getFilesDir();
        SaveRepository.write(files, key, "srm", new byte[]{7, 8});
        HomeActivity activity = open(null).get();
        described(activity.getWindow().getDecorView(), "Plus d'options").performClick();
        ShadowPopupMenu.getLatestPopupMenu().getMenu().performIdentifierAction(75, 0);
        idleSaves();
        Intent create = shadowOf(activity).getNextStartedActivityForResult().intent;
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, create.getAction());
        assertEquals("application/zip", create.getType());
        String title = create.getStringExtra(Intent.EXTRA_TITLE);
        assertTrue(title, title.matches("RavenEmu-sauvegardes-\\d{4}-\\d{2}-\\d{2}\\.zip"));
        Uri target = Uri.parse("content://saves/backup.zip");
        ByteArrayOutputStream written = new ByteArrayOutputStream();
        shadowOf(activity.getContentResolver()).registerOutputStream(target, written);
        activity.onActivityResult(75, Activity.RESULT_OK, new Intent().setData(target));
        idleSaves();
        assertEquals("Sauvegardes d'un jeu exportées", ShadowToast.getTextOfLatestToast());
        // The beta reads it back: the game Alpha.gb only, bound to its ROM bytes.
        String sha = SaveTransfer.sha256(new byte[0x180]);
        SaveTransfer.Payload back = SaveTransfer.read(new ByteArrayInputStream(written.toByteArray()), "x.zip", sha);
        assertArrayEquals(new byte[]{7, 8}, back.battery);
    }

    @Test public void theMenuSaysSoWhenNoGameHasASave() throws Exception {
        seedLibrary();
        HomeActivity activity = open(null).get();
        described(activity.getWindow().getDecorView(), "Plus d'options").performClick();
        ShadowPopupMenu.getLatestPopupMenu().getMenu().performIdentifierAction(75, 0);
        idleSaves();
        assertNull(shadowOf(activity).getNextStartedActivityForResult());
        assertEquals("Aucun jeu de la bibliothèque n'a de sauvegarde à exporter.", ShadowToast.getTextOfLatestToast());
    }

    @Test public void theMenuImportsAFolderAndSummarizesItAsTheBeta() throws Exception {
        HomeActivity activity = open(null).get();
        byte[] rom = new byte[0x150];
        FolderImportUi folders = ReflectionHelpers.getField(activity, "folders");
        folders.trees = uri -> new FolderImport.Tree() {
            @Override public String root() { return "root"; }
            @Override public java.util.List<FolderImport.Child> children(String id) {
                return java.util.Arrays.asList(new FolderImport.Child("rom", "Jeu libre.gb", false, rom.length),
                        new FolderImport.Child("txt", "lisez-moi.txt", false, 3));
            }
            @Override public java.io.InputStream open(String id) { return new ByteArrayInputStream(rom); }
        };
        described(activity.getWindow().getDecorView(), "Plus d'options").performClick();
        ShadowPopupMenu.getLatestPopupMenu().getMenu().performIdentifierAction(76, 0);
        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE,
                shadowOf(activity).getNextStartedActivityForResult().intent.getAction());
        activity.onActivityResult(76, Activity.RESULT_OK, new Intent().setData(Uri.parse("content://tree/root")));
        idleIo(activity);
        idleIo(activity);
        AlertDialog summary = (AlertDialog) ShadowDialog.getLatestDialog();
        assertTrue(summary.isShowing());
        assertEquals("Importation du dossier terminée", shadowOf(summary).getTitle().toString());
        View body = summary.getWindow().getDecorView();
        assertNotNull(text(body, "Jeux ajoutés : 1"));
        assertNotNull(text(body, "Doublons ignorés : 0"));
        assertNotNull(text(body, "Autres fichiers ignorés : 1"));
        assertNotNull(text(body, "Dossiers parcourus : 1"));
        assertNull("Nothing unreadable", text(body, "Fichiers ou dossiers illisibles : 0"));
        assertNotNull(text(body, "Les jeux existants, leurs sauvegardes et la partie en cours n'ont pas été modifiés."));
        assertEquals(1, RomLibrary.list(activity.getFilesDir()).size());
        assertEquals(1, find(activity.getWindow().getDecorView(), GridView.class).getAdapter().getCount());
    }

    @Test public void aFolderThatCannotBeReadIsSaidSo() throws Exception {
        HomeActivity activity = open(null).get();
        FolderImportUi folders = ReflectionHelpers.getField(activity, "folders");
        folders.trees = uri -> { throw new java.io.IOException("Dossier Android invalide."); };
        activity.onActivityResult(76, Activity.RESULT_OK, new Intent().setData(Uri.parse("content://tree/x")));
        idleIo(activity);
        idleIo(activity);
        assertEquals("Importation du dossier impossible : Dossier Android invalide.",
                ShadowToast.getTextOfLatestToast());
    }

    @Test public void theToolbarSearchAndPagesUseDrawnIconsNotSymbols() throws Exception {
        seedLibrary();
        HomeActivity activity = open(null).get();
        View root = activity.getWindow().getDecorView();
        for (String symbol : new String[]{"⚙", "+", "⋮", "●", "⌕  Rechercher un jeu"})
            assertNull("No symbol for an icon: " + symbol, text(root, symbol));
        assertEquals(RavenIcon.Glyph.SETTINGS, glyph(described(root, "Paramètres")));
        assertEquals(RavenIcon.Glyph.ADD, glyph(described(root, "Ajouter une ROM")));
        assertEquals(RavenIcon.Glyph.MORE, glyph(described(root, "Plus d'options")));
        assertEquals(RavenIcon.Glyph.ALL, glyph(described(root, "Tous les jeux")));
        assertEquals(RavenIcon.Glyph.GB, glyph(described(root, "Game Boy")));
        assertEquals(RavenIcon.Glyph.GBC, glyph(described(root, "Game Boy Color")));
        EditText search = find(root, EditText.class);
        assertEquals("Rechercher un jeu", search.getHint().toString());
        assertTrue(search.getCompoundDrawablesRelative()[0] instanceof RavenIcon);
        // Every icon in the one accent colour, the owner's choice.
        for (String label : new String[]{"Paramètres", "Ajouter une ROM", "Plus d'options", "Tous les jeux",
                "Game Boy", "Game Boy Color"})
            assertEquals(label, HomeActivity.ACCENT,
                    ((RavenIcon) ((android.widget.ImageView) described(root, label)).getDrawable()).tint());
        assertEquals(HomeActivity.ACCENT, ((RavenIcon) search.getCompoundDrawablesRelative()[0]).tint());
    }

    private static RavenIcon.Glyph glyph(View view) {
        return ((RavenIcon) ((android.widget.ImageView) view).getDrawable()).glyph();
    }

    /** Opens the first game's import with `bytes` as `name`, up to the review. */
    private AlertDialog reviewOf(HomeActivity activity, String name, byte[] bytes) throws Exception {
        showFirstGame(activity);
        text(sheet(), "Importer").performClick();
        Intent pick = shadowOf(activity).getNextStartedActivityForResult().intent;
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, pick.getAction());
        Uri source = Uri.parse("content://saves/" + name);
        shadowOf(activity.getContentResolver()).registerInputStream(source, new ByteArrayInputStream(bytes));
        activity.onActivityResult(74, Activity.RESULT_OK, new Intent().setData(source));
        idleSaves();
        AlertDialog review = (AlertDialog) ShadowDialog.getLatestDialog();
        assertTrue(review.isShowing());
        assertEquals("Importer les sauvegardes ?", shadowOf(review).getTitle().toString());
        return review;
    }

    private static void put(ZipOutputStream zip, String name, byte[] bytes) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }

    private static View sheet() {
        return ShadowDialog.getLatestDialog().getWindow().getDecorView();
    }

    /** Save work runs on the shared executor, then posts to the UI. */
    private static void idleSaves() throws Exception {
        for (int i = 0; i < 3; i++) {
            SaveStorage.IO.submit(() -> {}).get(5, TimeUnit.SECONDS);
            shadowOf(Looper.getMainLooper()).idle();
        }
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

    private ActivityController<HomeActivity> open(Bundle saved) throws Exception {
        ActivityController<HomeActivity> c = Robolectric.buildActivity(HomeActivity.class)
                .create(saved).start().resume().visible();
        activities.add(c);
        idleIo(c.get());
        return c;
    }

    private Bundle saveAndDestroy(ActivityController<HomeActivity> c) {
        Bundle saved = new Bundle();
        c.saveInstanceState(saved).pause().stop().destroy();
        activities.remove(c);
        return saved;
    }

    private static void idleIo(HomeActivity activity) throws Exception {
        ExecutorService io = ReflectionHelpers.getField(activity, "io");
        io.submit(() -> {}).get(5, TimeUnit.SECONDS);
        shadowOf(Looper.getMainLooper()).idle();
    }

    private static void showFirstGame(HomeActivity activity) {
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
