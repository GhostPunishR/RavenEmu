package com.ravenemu.app.ui.settings;
import com.ravenemu.app.data.saves.GbaBiosRepository;
import com.ravenemu.app.settings.ConsoleSettings;
import com.ravenemu.app.settings.PlaybackSettings;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
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
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowToast;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Exercises the real Java controls and persistence, without JNI. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36, qualifiers = "fr-rFR-w320dp-h320dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class PlaybackSettingsActivityTest {
    private final List<ActivityController<PlaybackSettingsActivity>> activities = new ArrayList<>();
    private SharedPreferences preferences;

    @Before public void prepare() {
        preferences = RuntimeEnvironment.getApplication().getSharedPreferences(
                PlaybackSettings.PREFERENCES, Context.MODE_PRIVATE);
        preferences.edit().clear().commit();
        GbaBiosRepository.remove(RuntimeEnvironment.getApplication().getFilesDir());
    }

    @After public void close() {
        for (ActivityController<PlaybackSettingsActivity> controller : activities)
            controller.pause().stop().destroy();
    }

    @Test public void openingAnInvalidPageUsesCategoriesWithoutWritingDefaults() {
        PlaybackSettingsActivity activity = open("unknown", null).get();
        assertNotNull(described(activity, "Ouvrir les réglages audio"));
        assertNotNull(described(activity, "Ouvrir les réglages vidéo"));
        assertNotNull(text(activity, "Paramètres"));
        assertTrue(preferences.getAll().isEmpty());
        described(activity, "Ouvrir les réglages audio").performClick();
        assertTrue(toggle(activity, "Activer le son").isChecked());
        assertEquals(100, slider(activity, "Volume").getProgress());
        assertTrue(preferences.getAll().isEmpty());
    }

    @Test public void toolbarAndAndroidBackFollowTheBetaCategoryNavigation() {
        PlaybackSettingsActivity activity = open(null, null).get();
        described(activity, "Ouvrir les réglages audio").performClick();
        assertNotNull(toggle(activity, "Activer le son"));
        described(activity, "Retour aux paramètres").performClick();
        assertFalse(activity.isFinishing());
        described(activity, "Ouvrir les réglages vidéo").performClick();
        assertNotNull(toggle(activity, "Pixels entiers"));
        activity.onBackPressed();
        assertFalse(activity.isFinishing());
        assertNotNull(described(activity, "Ouvrir les réglages audio"));
        activity.onBackPressed();
        assertTrue(activity.isFinishing());
    }

    @Test public void oneTapMuteKeepsVolumeAndReenablingRestoresItsControl() {
        new PlaybackSettings(true, 37, true, 12, 120).save(RuntimeEnvironment.getApplication());
        PlaybackSettingsActivity activity = open(PlaybackSettingsActivity.AUDIO, null).get();
        Switch sound = toggle(activity, "Activer le son");
        SeekBar volume = slider(activity, "Volume");
        assertEquals(37, volume.getProgress());
        sound.performClick();
        assertFalse(PlaybackSettings.load(activity).audioEnabled);
        assertFalse(volume.isEnabled());
        assertEquals(37, PlaybackSettings.load(activity).audioVolume);
        sound.performClick();
        assertTrue(PlaybackSettings.load(activity).audioEnabled);
        assertTrue(volume.isEnabled());
        setAsUser(volume, 64);
        assertEquals(64, PlaybackSettings.load(activity).audioVolume);
        assertNotNull(find(activity, view -> view instanceof TextView &&
                "64 %".contentEquals(((TextView)view).getText())));
    }

    @Test public void videoAdjustmentsPersistAndResetPreservesAudioPreferences() {
        new PlaybackSettings(false, 23, false, 0, 100).save(RuntimeEnvironment.getApplication());
        PlaybackSettingsActivity activity = open(PlaybackSettingsActivity.VIDEO, null).get();
        toggle(activity, "Pixels entiers").performClick();
        setAsUser(slider(activity, "Luminosité"), 72); // -50 + 72 = 22
        setAsUser(slider(activity, "Contraste"), 82); // 50 + 82 = 132
        PlaybackSettings changed = PlaybackSettings.load(activity);
        assertTrue(changed.integerScale);
        assertEquals(22, changed.brightness);
        assertEquals(132, changed.contrast);
        text(activity, "Rétablir l'affichage d'origine").performClick();
        PlaybackSettings restored = PlaybackSettings.load(activity);
        assertFalse(restored.integerScale);
        assertEquals(0, restored.brightness);
        assertEquals(100, restored.contrast);
        assertFalse(restored.audioEnabled);
        assertEquals(23, restored.audioVolume);
        assertEquals(50, slider(activity, "Luminosité").getProgress());
        assertEquals(50, slider(activity, "Contraste").getProgress());
    }

    @Test public void imageOptionsFollowTheBetaChoicesAndResetWithThePicture() {
        new PlaybackSettings(false, 23, false, 0, 100).save(RuntimeEnvironment.getApplication());
        PlaybackSettingsActivity activity = open(PlaybackSettingsActivity.VIDEO, null).get();
        assertNotNull(text(activity, "Profil de couleurs"));
        assertNotNull(choice(activity, "Original, choisi"));
        choice(activity, "Pocket gris").performClick();
        assertEquals(PlaybackSettings.PALETTE_POCKET, PlaybackSettings.load(activity).gbPalette);
        assertNotNull("The page shows the new choice", choice(activity, "Pocket gris, choisi"));
        toggle(activity, "Game Boy Color").performClick();
        toggle(activity, "Game Boy Advance").performClick();
        choice(activity, "Rémanence").performClick();
        PlaybackSettings changed = PlaybackSettings.load(activity);
        assertTrue(changed.lcdCorrectionGbc);
        assertTrue(changed.lcdCorrectionGba);
        assertEquals(PlaybackSettings.BLENDING_GHOSTING, changed.frameBlending);
        assertEquals(PlaybackSettings.PALETTE_POCKET, changed.gbPalette);
        assertEquals(23, changed.audioVolume);
        text(activity, "Rétablir l'affichage d'origine").performClick();
        PlaybackSettings restored = PlaybackSettings.load(activity);
        assertEquals(PlaybackSettings.PALETTE_ORIGINAL, restored.gbPalette);
        assertFalse(restored.lcdCorrectionGbc);
        assertFalse(restored.lcdCorrectionGba);
        assertEquals(PlaybackSettings.BLENDING_OFF, restored.frameBlending);
        assertFalse(restored.audioEnabled);
        assertEquals(23, restored.audioVolume);
        assertNotNull(choice(activity, "Désactivé (défaut), choisi"));
    }

    @Test public void consolePageFollowsTheBetaAndKeepsTheOtherSettings() {
        new PlaybackSettings(false, 23, false, 0, 100).save(RuntimeEnvironment.getApplication());
        PlaybackSettingsActivity home = open(null, null).get();
        described(home, "Ouvrir les réglages console").performClick();
        assertNotNull(toggle(home, "Jeux Game Boy en couleurs"));
        PlaybackSettingsActivity activity = open(PlaybackSettingsActivity.CONSOLE, null).get();
        assertTrue("Opening the page writes nothing",
                preferences.getAll().keySet().stream().noneMatch(key -> key.startsWith("console.")));
        View bootColors = choice(activity, "Celles de la boot ROM, jeu par jeu, choisi");
        assertFalse("The colors wait for Game Boy games in color", bootColors.isEnabled());
        choice(activity, "Gris").performClick();
        assertEquals(0, ConsoleSettings.load(activity).gbColors);

        toggle(activity, "Jeux Game Boy en couleurs").performClick();
        assertTrue(ConsoleSettings.load(activity).gbInColor);
        assertTrue(choice(activity, "Gris").isEnabled());
        choice(activity, "A + Haut : rouge").performClick();
        assertEquals(3, ConsoleSettings.load(activity).gbColors);
        assertNotNull("The page shows the new choice", choice(activity, "A + Haut : rouge, choisi"));
        toggle(activity, "Game Boy Color d'une Game Boy Advance").performClick();
        choice(activity, "Vrai BIOS, sans animation").performClick();
        ConsoleSettings changed = ConsoleSettings.load(activity);
        assertTrue(changed.gbInColor);
        assertEquals(3, changed.gbColors);
        assertTrue(changed.gbcOnGba);
        assertEquals(ConsoleSettings.BIOS_REAL_SKIP_INTRO, changed.gbaBios);
        assertEquals(23, PlaybackSettings.load(activity).audioVolume);
        assertFalse(PlaybackSettings.load(activity).audioEnabled);
        assertNotNull(text(activity, "Fichier du BIOS : absent"));
        assertNull(find(activity, view -> view instanceof TextView
                && "Retirer le fichier du BIOS".contentEquals(((TextView)view).getText())));
    }

    @Test public void theBiosFileIsChosenCheckedAndRemoved() throws Exception {
        PlaybackSettingsActivity activity = open(PlaybackSettingsActivity.CONSOLE, null).get();
        text(activity, "Choisir le fichier du BIOS").performClick();
        ShadowActivity.IntentForResult pick = shadowOf(activity).getNextStartedActivityForResult();
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, pick.intent.getAction());
        assertTrue(pick.intent.hasCategory(Intent.CATEGORY_OPENABLE));

        // A synthetic 16 KiB file stands for the player's BIOS.
        byte[] bios = new byte[GbaBiosRepository.SIZE];
        Arrays.fill(bios, (byte)0x2c);
        activity.onActivityResult(pick.requestCode, Activity.RESULT_OK,
                new Intent().setData(Uri.fromFile(file(activity, "gba_bios_dump.bin", bios))));
        idleIo(activity);
        assertEquals("BIOS GBA installé", ShadowToast.getTextOfLatestToast());
        assertArrayEquals(bios, GbaBiosRepository.read(activity.getFilesDir()));
        assertNotNull(text(activity, "Fichier du BIOS : installé"));
        assertNotNull(text(activity, "Remplacer le fichier du BIOS"));

        activity.onActivityResult(pick.requestCode, Activity.RESULT_OK,
                new Intent().setData(Uri.fromFile(file(activity, "rom.gba", new byte[4096]))));
        idleIo(activity);
        assertTrue(ShadowToast.getTextOfLatestToast().contains("16 Kio"));
        assertArrayEquals("A refused file keeps the installed BIOS", bios,
                GbaBiosRepository.read(activity.getFilesDir()));

        activity.onActivityResult(pick.requestCode, Activity.RESULT_CANCELED, null);
        text(activity, "Retirer le fichier du BIOS").performClick();
        idleIo(activity);
        assertNull(GbaBiosRepository.read(activity.getFilesDir()));
        assertNotNull(text(activity, "Fichier du BIOS : absent"));
        assertNotNull(text(activity, "Choisir le fichier du BIOS"));
    }

    @Test public void recreationKeepsVideoPageAndScrollWithoutOverwritingStoredValues() {
        new PlaybackSettings(false, 29, true, 11, 117).save(RuntimeEnvironment.getApplication());
        Map<String, ?> before = preferences.getAll();
        ActivityController<PlaybackSettingsActivity> first = open(PlaybackSettingsActivity.AUDIO, null);
        described(first.get(), "Retour aux paramètres").performClick();
        described(first.get(), "Ouvrir les réglages vidéo").performClick();
        layout(first.get());
        ScrollView previous = scroll(first.get());
        previous.scrollTo(0, 60);
        int savedScroll = previous.getScrollY();
        assertTrue("Video settings must remain scrollable on a low landscape-sized screen", savedScroll > 0);
        Bundle saved = new Bundle();
        first.saveInstanceState(saved).pause().stop().destroy();
        activities.remove(first);
        PlaybackSettingsActivity recreated = open(PlaybackSettingsActivity.AUDIO, saved).get();
        assertNotNull(toggle(recreated, "Pixels entiers"));
        assertEquals(savedScroll, scroll(recreated).getScrollY());
        assertEquals(before, preferences.getAll());
        assertEquals(61, slider(recreated, "Luminosité").getProgress());
        assertEquals(67, slider(recreated, "Contraste").getProgress());
    }

    @Test public void insetsProtectTheToolbarAndBottomAndBackOnlyClosesThisActivity() {
        PlaybackSettingsActivity activity = open(PlaybackSettingsActivity.VIDEO, null).get();
        View root = find(activity, view -> "playback.settings.root".equals(view.getTag()));
        assertNotNull(root);
        WindowInsets insets = new WindowInsets.Builder().setInsets(
                WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout(),
                Insets.of(13, 32, 7, 41)).build();
        root.dispatchApplyWindowInsets(insets);
        root.dispatchApplyWindowInsets(insets);
        assertEquals(13, root.getPaddingLeft());
        assertEquals(32, root.getPaddingTop());
        assertEquals(7, root.getPaddingRight());
        assertEquals(41, root.getPaddingBottom());
        described(activity, "Retour aux paramètres").performClick();
        assertFalse(activity.isFinishing());
        described(activity, "Fermer les paramètres").performClick();
        assertTrue(activity.isFinishing());
    }

    private ActivityController<PlaybackSettingsActivity> open(String page, Bundle saved) {
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), PlaybackSettingsActivity.class)
                .putExtra(PlaybackSettingsActivity.EXTRA_PAGE, page);
        ActivityController<PlaybackSettingsActivity> controller = Robolectric.buildActivity(
                PlaybackSettingsActivity.class, intent).create(saved).start().resume().visible();
        activities.add(controller);
        layout(controller.get());
        return controller;
    }

    private static void layout(PlaybackSettingsActivity activity) {
        View decor = activity.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, 320, 320);
        shadowOf(Looper.getMainLooper()).idle();
    }

    private static File file(PlaybackSettingsActivity activity, String name, byte[] bytes) throws IOException {
        File file = new File(activity.getCacheDir(), name);
        try (FileOutputStream out = new FileOutputStream(file)) { out.write(bytes); }
        return file;
    }

    private static void idleIo(PlaybackSettingsActivity activity) throws Exception {
        ExecutorService io = ReflectionHelpers.getField(activity, "io");
        io.submit(() -> {}).get(5, TimeUnit.SECONDS);
        shadowOf(Looper.getMainLooper()).idle();
        layout(activity);
    }

    private static void setAsUser(SeekBar slider, int progress) {
        slider.setProgress(progress);
        shadowOf(slider).getOnSeekBarChangeListener().onProgressChanged(slider, progress, true);
    }

    private static ScrollView scroll(PlaybackSettingsActivity activity) {
        return (ScrollView)find(activity, view -> "playback.settings.scroll".equals(view.getTag()));
    }

    private static Switch toggle(PlaybackSettingsActivity activity, String label) {
        View view = find(activity, candidate -> candidate instanceof Switch &&
                candidate.getContentDescription() != null &&
                candidate.getContentDescription().toString().startsWith(label + "."));
        assertNotNull(label, view);
        return (Switch)view;
    }

    private static SeekBar slider(PlaybackSettingsActivity activity, String label) {
        View view = find(activity, candidate -> candidate instanceof SeekBar &&
                candidate.getContentDescription() != null &&
                candidate.getContentDescription().toString().startsWith(label + ","));
        assertNotNull(label, view);
        return (SeekBar)view;
    }

    private static View choice(PlaybackSettingsActivity activity, String description) {
        View view = find(activity, candidate -> candidate.isClickable() &&
                description.contentEquals(candidate.getContentDescription() == null ? ""
                        : candidate.getContentDescription()));
        assertNotNull(description, view);
        return view;
    }

    private static View described(PlaybackSettingsActivity activity, String description) {
        View view = find(activity, candidate -> description.contentEquals(
                candidate.getContentDescription() == null ? "" : candidate.getContentDescription()));
        assertNotNull(description, view);
        return view;
    }

    private static View text(PlaybackSettingsActivity activity, String value) {
        View view = find(activity, candidate -> candidate instanceof TextView &&
                value.contentEquals(((TextView)candidate).getText()));
        assertNotNull(value, view);
        return view;
    }

    private static View find(PlaybackSettingsActivity activity, java.util.function.Predicate<View> match) {
        return find(activity.getWindow().getDecorView(), match);
    }

    private static View find(View root, java.util.function.Predicate<View> match) {
        if (match.test(root)) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup)root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = find(group.getChildAt(i), match);
                if (found != null) return found;
            }
        }
        return null;
    }
}
