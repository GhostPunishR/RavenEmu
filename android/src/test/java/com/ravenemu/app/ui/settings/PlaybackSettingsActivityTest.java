package com.ravenemu.app.ui.settings;
import com.ravenemu.app.data.saves.GbaBiosRepository;
import com.ravenemu.app.settings.CartridgeSettings;
import com.ravenemu.app.settings.ConsoleSettings;
import com.ravenemu.app.settings.ControlLayout;
import com.ravenemu.app.settings.ControlSettings;
import com.ravenemu.app.settings.LatencySettings;
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
        described(activity, "Ouvrir les réglages son du jeu").performClick();
        assertTrue(toggle(activity, "Activer le son").isChecked());
        assertEquals(100, slider(activity, "Volume").getProgress());
        assertTrue(preferences.getAll().isEmpty());
    }

    @Test public void toolbarAndAndroidBackClimbOneLevelAtATime() {
        PlaybackSettingsActivity activity = open(null, null).get();
        described(activity, "Ouvrir les réglages audio").performClick();
        described(activity, "Ouvrir les réglages son du jeu").performClick();
        assertNotNull(toggle(activity, "Activer le son"));
        described(activity, "Retour aux réglages audio").performClick();
        assertNotNull(described(activity, "Ouvrir les réglages qualité du son"));
        described(activity, "Retour aux paramètres").performClick();
        assertFalse(activity.isFinishing());
        described(activity, "Ouvrir les réglages vidéo").performClick();
        described(activity, "Ouvrir les réglages taille et réglages").performClick();
        assertNotNull(toggle(activity, "Pixels entiers"));
        activity.onBackPressed();
        assertFalse(activity.isFinishing());
        assertNotNull(described(activity, "Ouvrir les réglages couleurs"));
        activity.onBackPressed();
        assertNotNull(described(activity, "Ouvrir les réglages audio"));
        activity.onBackPressed();
        assertTrue(activity.isFinishing());
    }

    @Test public void everyCategoryListsItsSubPagesInOrderAndEachComesBackToIt() {
        String[][] tree = {
                {"général", "émulation", "latence"},
                {"audio", "son du jeu", "qualité du son"},
                {"vidéo", "couleurs", "rendu", "taille et réglages"},
                {"console", "game boy et gbc", "game boy advance"},
                {"interface", "commandes tactiles", "cartouche"},
        };
        PlaybackSettingsActivity activity = open(null, null).get();
        for (String[] branch : tree) {
            described(activity, "Ouvrir les réglages " + branch[0]).performClick();
            layout(activity);
            int previous = -1;
            for (int i = 1; i < branch.length; i++) {
                View row = described(activity, "Ouvrir les réglages " + branch[i]);
                assertTrue(branch[i] + " comes after the one before", row.getTop() > previous);
                previous = row.getTop();
            }
            for (int i = 1; i < branch.length; i++) {
                described(activity, "Ouvrir les réglages " + branch[i]).performClick();
                assertNotNull(branch[i], described(activity, "Retour aux réglages " + branch[0]));
                activity.onBackPressed();
                assertNotNull(described(activity, "Ouvrir les réglages " + branch[1]));
            }
            described(activity, "Retour aux paramètres").performClick();
            assertNotNull(described(activity, "Ouvrir les réglages audio"));
        }
        assertTrue("Browsing writes nothing", preferences.getAll().isEmpty());
    }

    @Test public void oneTapMuteKeepsVolumeAndReenablingRestoresItsControl() {
        new PlaybackSettings(true, 37, true, 12, 120).save(RuntimeEnvironment.getApplication());
        PlaybackSettingsActivity activity = open(PlaybackSettingsActivity.AUDIO_SOUND, null).get();
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

    @Test public void audioPageChoosesTheResamplerAndKeepsTheVolume() {
        new PlaybackSettings(true, 37, true, 12, 120).save(RuntimeEnvironment.getApplication());
        PlaybackSettingsActivity activity = open(PlaybackSettingsActivity.AUDIO_QUALITY, null).get();
        assertNotNull(text(activity, "Rééchantillonnage"));
        assertNotNull(choice(activity, "Sinc (défaut), choisi"));
        choice(activity, "Linéaire").performClick();
        assertEquals(PlaybackSettings.RESAMPLER_LINEAR, PlaybackSettings.load(activity).resampler);
        assertEquals(37, PlaybackSettings.load(activity).audioVolume);
        assertNotNull(choice(activity, "Linéaire, choisi"));
        choice(activity, "Sinc (défaut)").performClick();
        assertEquals(PlaybackSettings.RESAMPLER_SINC, PlaybackSettings.load(activity).resampler);
    }

    @Test public void videoAdjustmentsPersistAndResetPreservesAudioPreferences() {
        new PlaybackSettings(false, 23, false, 0, 100).save(RuntimeEnvironment.getApplication());
        PlaybackSettingsActivity activity = open(PlaybackSettingsActivity.VIDEO_PICTURE, null).get();
        toggle(activity, "Pixels entiers").performClick();
        setAsUser(slider(activity, "Luminosité"), 72); // -50 + 72 = 22
        setAsUser(slider(activity, "Contraste"), 82); // 50 + 82 = 132
        PlaybackSettings changed = PlaybackSettings.load(activity);
        assertTrue(changed.integerScale);
        assertEquals(22, changed.brightness);
        assertEquals(132, changed.contrast);
        // The reset sits on the Vidéo page, for every one of its sub-pages.
        activity.onBackPressed();
        text(activity, "Rétablir l'affichage d'origine").performClick();
        PlaybackSettings restored = PlaybackSettings.load(activity);
        assertFalse(restored.integerScale);
        assertEquals(0, restored.brightness);
        assertEquals(100, restored.contrast);
        assertFalse(restored.audioEnabled);
        assertEquals(23, restored.audioVolume);
        described(activity, "Ouvrir les réglages taille et réglages").performClick();
        assertFalse(toggle(activity, "Pixels entiers").isChecked());
        assertEquals(50, slider(activity, "Luminosité").getProgress());
        assertEquals(50, slider(activity, "Contraste").getProgress());
    }

    @Test public void imageOptionsFollowTheBetaChoicesAndResetWithThePicture() {
        new PlaybackSettings(false, 23, false, 0, 100).save(RuntimeEnvironment.getApplication());
        PlaybackSettingsActivity activity = open(PlaybackSettingsActivity.VIDEO_COLORS, null).get();
        assertNotNull(text(activity, "Profil de couleurs"));
        assertNotNull(choice(activity, "Original, choisi"));
        choice(activity, "Pocket gris").performClick();
        assertEquals(PlaybackSettings.PALETTE_POCKET, PlaybackSettings.load(activity).gbPalette);
        assertNotNull("The page shows the new choice", choice(activity, "Pocket gris, choisi"));
        toggle(activity, "Game Boy Color").performClick();
        toggle(activity, "Game Boy Advance").performClick();
        activity.onBackPressed();
        described(activity, "Ouvrir les réglages rendu").performClick();
        choice(activity, "Rémanence").performClick();
        assertNotNull(choice(activity, "Aucun (défaut), choisi"));
        choice(activity, "Lissage net").performClick();
        assertEquals(PlaybackSettings.EFFECT_SHARP, PlaybackSettings.load(activity).screenEffect);
        choice(activity, "Grille LCD").performClick();
        PlaybackSettings changed = PlaybackSettings.load(activity);
        assertEquals(PlaybackSettings.EFFECT_LCD, changed.screenEffect);
        assertTrue(changed.lcdCorrectionGbc);
        assertTrue(changed.lcdCorrectionGba);
        assertEquals(PlaybackSettings.BLENDING_GHOSTING, changed.frameBlending);
        assertEquals(PlaybackSettings.PALETTE_POCKET, changed.gbPalette);
        assertEquals(23, changed.audioVolume);
        activity.onBackPressed();
        text(activity, "Rétablir l'affichage d'origine").performClick();
        PlaybackSettings restored = PlaybackSettings.load(activity);
        assertEquals(PlaybackSettings.PALETTE_ORIGINAL, restored.gbPalette);
        assertFalse(restored.lcdCorrectionGbc);
        assertFalse(restored.lcdCorrectionGba);
        assertEquals(PlaybackSettings.BLENDING_OFF, restored.frameBlending);
        assertEquals(PlaybackSettings.EFFECT_NONE, restored.screenEffect);
        assertFalse(restored.audioEnabled);
        assertEquals(23, restored.audioVolume);
        described(activity, "Ouvrir les réglages rendu").performClick();
        assertNotNull(choice(activity, "Désactivé (défaut), choisi"));
        assertNotNull(choice(activity, "Aucun (défaut), choisi"));
        activity.onBackPressed();
        described(activity, "Ouvrir les réglages couleurs").performClick();
        assertNotNull(choice(activity, "Original, choisi"));
    }

    @Test public void consolePageFollowsTheBetaAndKeepsTheOtherSettings() {
        new PlaybackSettings(false, 23, false, 0, 100).save(RuntimeEnvironment.getApplication());
        PlaybackSettingsActivity home = open(null, null).get();
        described(home, "Ouvrir les réglages console").performClick();
        described(home, "Ouvrir les réglages game boy et gbc").performClick();
        assertNotNull(toggle(home, "Jeux Game Boy en couleurs"));
        PlaybackSettingsActivity activity = open(PlaybackSettingsActivity.CONSOLE_GB, null).get();
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
        activity.onBackPressed();
        described(activity, "Ouvrir les réglages game boy advance").performClick();
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
        PlaybackSettingsActivity activity = open(PlaybackSettingsActivity.CONSOLE_GBA, null).get();
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

    @Test public void theCategoriesAreTheBetasInItsOrderAndEachOpensItsPage() {
        PlaybackSettingsActivity activity = open(null, null).get();
        String[] categories = {"général", "audio", "vidéo", "console", "interface", "à propos"};
        int previous = -1;
        for (String category : categories) {
            View row = described(activity, "Ouvrir les réglages " + category);
            int top = row.getTop();
            assertTrue(category + " comes after the one before", top > previous);
            previous = top;
        }
        assertNull("No placeholder text", find(activity, view -> view instanceof TextView
                && ((TextView)view).getText().toString().contains("seront activés")));
        described(activity, "Ouvrir les réglages général").performClick();
        assertNotNull(text(activity, "Général"));
        assertNotNull(text(activity, "Pause automatique"));
        activity.onBackPressed();
        described(activity, "Ouvrir les réglages à propos").performClick();
        assertNotNull(text(activity, "À propos"));
    }

    @Test public void aboutOpensTheLicencesInTheAppAndTheSourcesOnline() {
        PlaybackSettingsActivity activity = open(PlaybackSettingsActivity.ABOUT, null).get();
        find(activity, view -> view.getContentDescription() != null
                && view.getContentDescription().toString().startsWith("Licences et crédits.")).performClick();
        Intent licences = shadowOf(activity).getNextStartedActivity();
        assertEquals(LicensesActivity.class.getName(), licences.getComponent().getClassName());
        find(activity, view -> view.getContentDescription() != null
                && view.getContentDescription().toString().startsWith("RavenCore.")).performClick();
        Intent source = shadowOf(activity).getNextStartedActivity();
        assertEquals(Intent.ACTION_VIEW, source.getAction());
        assertEquals("https://github.com/GhostPunishR/RavenCore", source.getDataString());
        find(activity, view -> view.getContentDescription() != null
                && view.getContentDescription().toString().startsWith("Politique de confidentialité.")).performClick();
        assertEquals("https://github.com/GhostPunishR/RavenEmu/blob/main/PRIVACY.md",
                shadowOf(activity).getNextStartedActivity().getDataString());
    }

    @Test public void interfacePageSetsTheTouchControlsAndResetsThem() {
        new PlaybackSettings(false, 23, false, 0, 100).save(RuntimeEnvironment.getApplication());
        PlaybackSettingsActivity home = open(null, null).get();
        described(home, "Ouvrir les réglages interface").performClick();
        described(home, "Ouvrir les réglages commandes tactiles").performClick();
        assertNotNull(toggle(home, "Retour haptique"));
        PlaybackSettingsActivity activity = open(PlaybackSettingsActivity.INTERFACE_CONTROLS, null).get();
        assertTrue("Opening the page writes nothing",
                preferences.getAll().keySet().stream().noneMatch(key -> key.startsWith("controls.")));
        assertEquals(20, slider(activity, "Taille des commandes").getProgress());
        assertEquals(26, slider(activity, "Espacement vertical").getProgress());
        assertNotNull(choice(activity, "Classique (défaut), choisi"));

        toggle(activity, "Retour haptique").performClick();
        setAsUser(slider(activity, "Taille des commandes"), 32); // 80 + 32 = 112 %
        setAsUser(slider(activity, "Espacement vertical"), 10); // 20 + 10 = 30 dp
        assertNotNull(text(activity, "30 dp"));
        choice(activity, "Contraste élevé").performClick();
        ControlSettings changed = ControlSettings.load(activity);
        assertTrue(changed.haptics);
        assertEquals(112, changed.scale);
        assertEquals(30, changed.gap);
        assertTrue(changed.highContrast());
        assertNotNull(choice(activity, "Contraste élevé, choisi"));
        assertEquals(23, PlaybackSettings.load(activity).audioVolume);

        ControlLayout.DEFAULT.with(1, 40, 0).save(activity, false);
        ControlLayout.DEFAULT.with(0, 0, -60).save(activity, true);
        assertNotNull(find(activity, view -> view instanceof TextView
                && ((TextView)view).getText().toString().contains("« Déplacer les boutons »")));
        text(activity, "Réinitialiser les commandes").performClick();
        assertEquals("The buttons go back too", ControlLayout.DEFAULT, ControlLayout.load(activity, false));
        assertEquals(ControlLayout.DEFAULT, ControlLayout.load(activity, true));
        ControlSettings restored = ControlSettings.load(activity);
        assertFalse(restored.haptics);
        assertEquals(100, restored.scale);
        assertEquals(46, restored.gap);
        assertFalse(restored.highContrast());
        assertFalse(toggle(activity, "Retour haptique").isChecked());
        assertEquals(20, slider(activity, "Taille des commandes").getProgress());
        assertNotNull(choice(activity, "Classique (défaut), choisi"));
        assertFalse(PlaybackSettings.load(activity).audioEnabled);
    }

    @Test public void generalPageSetsThePreemptiveFrames() {
        PlaybackSettingsActivity activity = open(PlaybackSettingsActivity.GENERAL_LATENCY, null).get();
        assertNotNull(text(activity, "Images préventives"));
        assertNotNull(choice(activity, "Désactivées (défaut), choisi"));
        assertTrue("Opening the page writes nothing",
                preferences.getAll().keySet().stream().noneMatch(key -> key.startsWith("latency.")));
        choice(activity, "2 images").performClick();
        assertEquals(2, LatencySettings.load(activity).preemptiveFrames);
        assertNotNull(choice(activity, "2 images, choisi"));
        PlaybackSettingsActivity reopened = open(PlaybackSettingsActivity.GENERAL_LATENCY, null).get();
        assertNotNull(choice(reopened, "2 images, choisi"));
        choice(reopened, "Désactivées (défaut)").performClick();
        assertEquals(0, LatencySettings.load(reopened).preemptiveFrames);
    }

    @Test public void interfacePageSetsTheCartridgeMotorTheSensorsAndTheSun() {
        PlaybackSettingsActivity activity = open(PlaybackSettingsActivity.INTERFACE_CARTRIDGE, null).get();
        assertTrue("Opening the page writes nothing",
                preferences.getAll().keySet().stream().noneMatch(key -> key.startsWith("cartridge.")));
        assertTrue(toggle(activity, "Vibration de la cartouche").isChecked());
        assertTrue(toggle(activity, "Capteurs du téléphone").isChecked());
        Switch solarAuto = toggle(activity, "Soleil automatique (Boktai)");
        SeekBar sun = slider(activity, "Soleil (Boktai)");
        assertTrue(solarAuto.isChecked());
        assertTrue(solarAuto.isEnabled());
        assertFalse("The light sensor sets the sun", sun.isEnabled());
        assertEquals("By tens: 50 %", 5, sun.getProgress());
        assertEquals(10, sun.getMax());

        toggle(activity, "Vibration de la cartouche").performClick();
        assertFalse(CartridgeSettings.load(activity).rumble);
        toggle(activity, "Capteurs du téléphone").performClick();
        assertFalse("Without the sensors, no automatic sun", solarAuto.isEnabled());
        assertTrue(sun.isEnabled());
        ((View)solarAuto.getParent()).performClick();
        assertTrue("A greyed-out row stays as it is", CartridgeSettings.load(activity).solarAuto);
        setAsUser(sun, 8);
        assertNotNull(text(activity, "80 %"));
        CartridgeSettings saved = CartridgeSettings.load(activity);
        assertFalse(saved.rumble);
        assertFalse(saved.sensors);
        assertTrue(saved.solarAuto);
        assertEquals(80, saved.solarLevel);

        toggle(activity, "Capteurs du téléphone").performClick();
        toggle(activity, "Soleil automatique (Boktai)").performClick();
        assertTrue("The chosen sun, with the sensors on", sun.isEnabled());
        // As in the beta, resetting the controls leaves the cartridge alone.
        activity.onBackPressed();
        described(activity, "Ouvrir les réglages commandes tactiles").performClick();
        text(activity, "Réinitialiser les commandes").performClick();
        activity.onBackPressed();
        described(activity, "Ouvrir les réglages cartouche").performClick();
        CartridgeSettings kept = CartridgeSettings.load(activity);
        assertFalse(kept.rumble);
        assertTrue(kept.sensors);
        assertFalse(kept.solarAuto);
        assertEquals(80, kept.solarLevel);
        assertEquals(8, slider(activity, "Soleil (Boktai)").getProgress());
        assertTrue(slider(activity, "Soleil (Boktai)").isEnabled());
    }

    @Test public void recreationKeepsVideoPageAndScrollWithoutOverwritingStoredValues() {
        new PlaybackSettings(false, 29, true, 11, 117).save(RuntimeEnvironment.getApplication());
        Map<String, ?> before = preferences.getAll();
        ActivityController<PlaybackSettingsActivity> first = open(PlaybackSettingsActivity.AUDIO, null);
        described(first.get(), "Retour aux paramètres").performClick();
        described(first.get(), "Ouvrir les réglages vidéo").performClick();
        described(first.get(), "Ouvrir les réglages couleurs").performClick();
        layout(first.get());
        ScrollView previous = scroll(first.get());
        previous.scrollTo(0, 60);
        int savedScroll = previous.getScrollY();
        assertTrue("Video settings must remain scrollable on a low landscape-sized screen", savedScroll > 0);
        Bundle saved = new Bundle();
        first.saveInstanceState(saved).pause().stop().destroy();
        activities.remove(first);
        PlaybackSettingsActivity recreated = open(PlaybackSettingsActivity.AUDIO, saved).get();
        assertNotNull(choice(recreated, "Original, choisi"));
        assertEquals(savedScroll, scroll(recreated).getScrollY());
        assertEquals(before, preferences.getAll());
        // Back from the restored sub-page: its category, then the picture's settings.
        described(recreated, "Retour aux réglages vidéo").performClick();
        described(recreated, "Ouvrir les réglages taille et réglages").performClick();
        assertTrue(toggle(recreated, "Pixels entiers").isChecked());
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
