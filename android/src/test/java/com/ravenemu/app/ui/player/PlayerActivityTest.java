package com.ravenemu.app.ui.player;
import com.ravenemu.app.bridge.FakeAchievements;
import com.ravenemu.app.bridge.NativeAchievements;
import com.ravenemu.app.bridge.NativeBridge;
import com.ravenemu.app.data.library.RomLibrary;
import com.ravenemu.app.data.saves.GbaBiosRepository;
import com.ravenemu.app.data.saves.SaveRepository;
import com.ravenemu.app.data.saves.StatePreview;
import com.ravenemu.app.data.saves.StateSlotRepository;
import com.ravenemu.app.platform.AchievementsHttp;
import com.ravenemu.app.platform.ClearSealer;
import com.ravenemu.app.platform.FakeHttp;
import com.ravenemu.app.settings.AchievementAccount;
import com.ravenemu.app.settings.AchievementSettings;
import com.ravenemu.app.settings.AwardJournal;
import com.ravenemu.app.settings.CartridgeSettings;
import com.ravenemu.app.settings.ConsoleSettings;
import com.ravenemu.app.settings.ControlLayout;
import com.ravenemu.app.settings.LatencySettings;
import com.ravenemu.app.settings.PlaybackSettings;
import com.ravenemu.app.ui.settings.PlaybackSettingsActivity;

import android.app.Dialog;
import android.app.AlertDialog;
import android.content.Intent;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.RandomAccessFile;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
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
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowGLSurfaceView;
import org.robolectric.shadows.ShadowToast;
import org.robolectric.shadows.ShadowVibrator;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Exercises real Activity/UI/storage orchestration with controlled GL/JNI
 * boundaries. No physical rendering, native core or audio hardware is emulated. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36, qualifiers = "en-rUS-w320dp-h568dp-mdpi",
        shadows = {PlayerActivityTest.QueuedGl.class, PlayerActivityTest.FakeNative.class,
                FakeAchievements.class, ClearSealer.class, FakeHttp.class})
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class PlayerActivityTest {
    private static final String COMPATIBILITY = "RavenCore-test-pinned|gb|1";
    private final List<ActivityController<PlayerActivity>> activities = new ArrayList<>();
    private File files;
    private String key;

    @Before public void setUp() throws Exception {
        FakeNative.reset();
        FakeAchievements.reset();
        FakeHttp.reset();
        AchievementAccount.clear(RuntimeEnvironment.getApplication());
        AwardJournal.clear(RuntimeEnvironment.getApplication());
        RuntimeEnvironment.getApplication().getSharedPreferences(PlaybackSettings.PREFERENCES, 0)
                .edit().clear().commit();
        files = RuntimeEnvironment.getApplication().getFilesDir();
        GbaBiosRepository.remove(files);
        key = RomLibrary.importRom(files, "Libre.gb", new byte[0x180]).key;
    }

    @After public void close() throws Exception {
        for (ActivityController<PlayerActivity> controller : new ArrayList<>(activities)) {
            controller.pause().stop().destroy();
            io(controller.get()).submit(() -> {}).get(5, TimeUnit.SECONDS);
        }
        activities.clear();
        idle();
    }

    @Test public void initialRomReadFinishingInBackgroundWaitsForResumeBeforeOpeningTheCore() throws Exception {
        ActivityController<PlayerActivity> controller = open(null);
        PlayerActivity activity = controller.get();
        for (int i = 0; i < 3; i++) io(activity).submit(() -> {}).get(5, TimeUnit.SECONDS);
        controller.pause();
        gl(activity).drain();
        idle();
        assertEquals("A late IO callback must not open a paused session during cleanup", 0, FakeNative.opens);
        controller.resume();
        activity.onWindowFocusChanged(true);
        pump(activity);
        assertEquals(1, FakeNative.opens);
    }

    @Test public void playbackPreferencesApplyOnGlAndReturnFromSettingsKeepsTheMenuPaused() throws Exception {
        new PlaybackSettings(false, 27, true, -12, 130).withEffect(PlaybackSettings.EFFECT_LCD)
                .save(RuntimeEnvironment.getApplication());
        ActivityController<PlayerActivity> controller = open(null);
        PlayerActivity activity = controller.get();
        activity.onWindowFocusChanged(true); pump(activity);
        assertEquals("The LCD grid reaches the GLES2 driver", 2, FakeNative.appliedEffect);
        assertEquals("The sinc filter by default", 1, FakeNative.appliedResampler);
        assertFalse(FakeNative.appliedSettings.audioEnabled);
        assertEquals(27, FakeNative.appliedSettings.audioVolume);
        assertTrue(FakeNative.appliedSettings.integerScale);
        assertEquals(-12, FakeNative.appliedSettings.brightness);
        assertEquals(130, FakeNative.appliedSettings.contrast);
        openMenu(activity);
        menuButton("Paramètres").performClick();
        menuButton("Paramètres").performClick();
        Intent intent = shadowOf(activity).getNextStartedActivity();
        assertEquals(PlaybackSettingsActivity.class.getName(), intent.getComponent().getClassName());
        assertNull("A repeated tap must not stack settings activities", shadowOf(activity).getNextStartedActivity());
        assertTrue(menu(activity).isShowing());
        controller.pause();
        new PlaybackSettings(true, 61, false, 7, 80).save(activity);
        controller.resume(); activity.onWindowFocusChanged(true); pump(activity);
        assertEquals(61, FakeNative.appliedSettings.audioVolume);
        assertEquals(7, FakeNative.appliedSettings.brightness);
        assertEquals(80, FakeNative.appliedSettings.contrast);
        assertTrue(FakeNative.appliedSettings.audioEnabled);
        assertTrue("Settings return must preserve the menu's pause reason", FakeNative.paused);
        assertTrue(menu(activity).isShowing());
        menuButton("Reprendre le jeu").performClick(); pump(activity);
        assertFalse(FakeNative.paused);
    }

    @Test public void imageOptionsReachTheCoreAsTheGameLoadsAndAfterReturningFromSettings() throws Exception {
        new PlaybackSettings(true, 100, false, 0, 100, PlaybackSettings.PALETTE_POCKET, true, false,
                PlaybackSettings.BLENDING_MIX).save(RuntimeEnvironment.getApplication());
        ActivityController<PlayerActivity> controller = open(null);
        PlayerActivity activity = controller.get();
        activity.onWindowFocusChanged(true); pump(activity);
        assertEquals(1, FakeNative.opens);
        assertArrayEquals(new String[]{"ravenemu_gb_palette", "pocket", "ravenemu_gb_frame_blending", "mix",
                "ravenemu_gb_device_sensors", "enabled"}, FakeNative.openOptions);
        controller.pause();
        PlaybackSettings.load(activity).withImage(PlaybackSettings.PALETTE_GRAY, false, false,
                PlaybackSettings.BLENDING_GHOSTING).save(activity);
        controller.resume(); activity.onWindowFocusChanged(true); pump(activity);
        assertArrayEquals(new String[]{"ravenemu_gb_palette", "gray", "ravenemu_gb_frame_blending", "ghosting",
                "ravenemu_gb_device_sensors", "enabled"}, FakeNative.liveOptions);
        assertEquals("Changing an image option must not reopen the game", 1, FakeNative.opens);
    }

    @Test public void theGameWaitsWhileTheButtonsAreMovedAndTheirPlacesAreKept() throws Exception {
        PlayerActivity activity = loaded();
        openMenu(activity);
        menuButton("Déplacer les boutons").performClick(); pump(activity);
        assertFalse(menu(activity).isShowing());
        assertTrue("The game waits while the buttons move", FakeNative.paused);
        View done = find(activity.getWindow().getDecorView(), view ->
                view instanceof TextView && "Terminé".contentEquals(((TextView)view).getText()));
        assertNotNull(done);
        assertTrue(done.isShown());
        GameControlsView controls = ReflectionHelpers.getField(activity, "gameControls");
        assertTrue(controls.isEditing());
        View a = find(controls, view -> "A".contentEquals(
                view.getContentDescription() == null ? "" : view.getContentDescription()));
        assertTrue(a.performAccessibilityAction(0x02000012, null)); // eight dp to the right
        done.performClick(); pump(activity);
        assertFalse(controls.isEditing());
        assertFalse(done.isShown());
        assertFalse("The game goes on", FakeNative.paused);
        assertEquals(8f, ControlLayout.load(activity, false).dx(1), 0f);
        assertEquals(ControlLayout.DEFAULT, ControlLayout.load(activity, true));

        // Back while editing keeps the places too, and they come back on resume.
        openMenu(activity);
        menuButton("Déplacer les boutons").performClick(); pump(activity);
        assertTrue(a.performAccessibilityAction(0x02000014, null)); // eight dp down
        assertTrue(activity.onKeyDown(android.view.KeyEvent.KEYCODE_BACK,
                new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_BACK)));
        assertFalse(controls.isEditing());
        assertFalse(activity.isFinishing());
        assertEquals(8f, ControlLayout.load(activity, false).dy(1), 0f);
        ControlLayout.DEFAULT.save(activity, false);
        ActivityController<PlayerActivity> controller = activities.get(activities.size() - 1);
        controller.pause(); controller.resume();
        assertEquals(ControlLayout.DEFAULT, controls.controlLayout());
    }

    @Test public void theCoresMessagesShowBrieflyOverTheGame() throws Exception {
        PlayerActivity activity = loaded();
        ShadowToast.reset();
        FakeNative.message = "RavenGBA : pas de capteur de lumière, soleil à 50 %";
        activity.onDrawFrame(null); idle();
        assertEquals("RavenGBA : pas de capteur de lumière, soleil à 50 %", ShadowToast.getTextOfLatestToast());
        ShadowToast.reset();
        activity.onDrawFrame(null); idle();
        assertNull("Each message once", ShadowToast.getLatestToast());
    }

    @Test public void aControllerDrivesTheGameThroughKeysHatAndStick() throws Exception {
        PlayerActivity activity = loaded();
        assertTrue(key(activity, android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_BUTTON_A));
        assertEquals(NativeBridge.A, FakeNative.buttons);
        assertTrue(key(activity, android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_BUTTON_R2));
        assertEquals(NativeBridge.A | NativeBridge.R, FakeNative.buttons);
        // The D-pad as hat axes, then the left stick, as Bluetooth controllers send them.
        assertTrue(activity.dispatchGenericMotionEvent(joystick(1f, 0f, 0f, 0f)));
        assertEquals(NativeBridge.A | NativeBridge.R | NativeBridge.RIGHT, FakeNative.buttons);
        assertTrue(activity.dispatchGenericMotionEvent(joystick(0f, 0f, -0.2f, 0.8f)));
        assertEquals(NativeBridge.A | NativeBridge.R | NativeBridge.DOWN, FakeNative.buttons);
        assertTrue(key(activity, android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_BUTTON_A));
        assertEquals(NativeBridge.R | NativeBridge.DOWN, FakeNative.buttons);
        // The Home key opens the game menu, which pauses and lets go of everything.
        assertTrue(activity.onKeyDown(android.view.KeyEvent.KEYCODE_BUTTON_MODE,
                new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_BUTTON_MODE)));
        pump(activity);
        assertTrue(menu(activity).isShowing());
        assertTrue(FakeNative.paused);
        assertFalse("Paused, the stick is left to the system",
                activity.dispatchGenericMotionEvent(joystick(0f, 0f, 1f, 0f)));
    }

    private static boolean key(PlayerActivity activity, int action, int code) {
        android.view.KeyEvent event = new android.view.KeyEvent(action, code);
        return action == android.view.KeyEvent.ACTION_DOWN ? activity.onKeyDown(code, event) : activity.onKeyUp(code, event);
    }

    private static android.view.MotionEvent joystick(float hatX, float hatY, float x, float y) {
        android.view.MotionEvent.PointerProperties[] properties = {new android.view.MotionEvent.PointerProperties()};
        properties[0].toolType = android.view.MotionEvent.TOOL_TYPE_UNKNOWN;
        android.view.MotionEvent.PointerCoords[] coords = {new android.view.MotionEvent.PointerCoords()};
        coords[0].setAxisValue(android.view.MotionEvent.AXIS_HAT_X, hatX);
        coords[0].setAxisValue(android.view.MotionEvent.AXIS_HAT_Y, hatY);
        coords[0].setAxisValue(android.view.MotionEvent.AXIS_X, x);
        coords[0].setAxisValue(android.view.MotionEvent.AXIS_Y, y);
        return android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_MOVE, 1, properties, coords,
                0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_JOYSTICK, 0);
    }

    @Test public void withoutAnAccountRetroAchievementsNeverStarts() throws Exception {
        PlayerActivity activity = loaded();
        for (int i = 0; i < 3; i++) activity.onDrawFrame(null);
        assertTrue("No session, no request, no network", FakeAchievements.log.isEmpty());
    }

    @Test public void aSignedInAccountFollowsTheGameAndItsUnlockShowsOverThePicture() throws Exception {
        new AchievementAccount("Raven", "TOKEN-1", "Raven Rouge", "", 34, 12).save(RuntimeEnvironment.getApplication());
        AchievementSettings.DEFAULTS.withSound(false).save(RuntimeEnvironment.getApplication());
        FakeAchievements.onStart = () -> assertTrue("RetroAchievements starts on the GL thread", QueuedGl.executing);
        FakeAchievements.afterAnswer.add(new String[]{"login", "Raven", "Raven Rouge", "TOKEN-1", "", ""});
        FakeAchievements.afterAnswer.add(new String[]{"achievement", "Premier pas", "Finir le niveau", "5", "", "77"});
        PlayerActivity activity = loaded();
        assertEquals("Raven", FakeAchievements.startUser);
        assertEquals("TOKEN-1", FakeAchievements.startToken);
        assertEquals("The ROM's console, whatever core runs it", NativeAchievements.GAME_BOY, FakeAchievements.startConsole);
        assertEquals(0x180, FakeAchievements.startRomLength);
        // The login goes out on a network thread; its answer comes back with a frame.
        for (int i = 0; i < 1000 && FakeAchievements.answers.isEmpty(); i++) {
            activity.onDrawFrame(null);
            Thread.sleep(2);
        }
        assertEquals("A refused address is no answer, never a connection", AchievementsHttp.NO_ANSWER,
                FakeAchievements.answers.get(0)[2]);
        idle();
        AchievementOverlay overlay = ReflectionHelpers.getField(activity, "achievementOverlay");
        assertEquals("Succès débloqué · 5 points · Premier pas", String.valueOf(overlay.bannerText()));
        ActivityController<PlayerActivity> controller = activities.remove(activities.size() - 1);
        controller.pause().stop().destroy();
        io(activity).submit(() -> {}).get(5, TimeUnit.SECONDS);
        assertTrue("Closing the game closes its session", FakeAchievements.log.stream().anyMatch(l -> l.startsWith("stop ")));
        assertTrue(FakeNative.closed);
    }

    @Test public void unlockBannersFollowTheSettingsButTheGameStillIntroducesItself() throws Exception {
        new AchievementAccount("Raven", "TOKEN-1", "", "", 0, 0).save(RuntimeEnvironment.getApplication());
        AchievementSettings.DEFAULTS.withUnlockPopups(false).withSound(false).save(RuntimeEnvironment.getApplication());
        FakeAchievements.afterAnswer.add(new String[]{"achievement", "Premier pas", "", "5", "", "77"});
        FakeAchievements.afterAnswer.add(new String[]{"game", "Libre", "12", "0", "", "1234"});
        PlayerActivity activity = loaded();
        for (int i = 0; i < 1000 && FakeAchievements.answers.isEmpty(); i++) {
            activity.onDrawFrame(null);
            Thread.sleep(2);
        }
        idle();
        AchievementOverlay overlay = ReflectionHelpers.getField(activity, "achievementOverlay");
        assertEquals("12 succès disponibles · Libre", String.valueOf(overlay.bannerText()));
    }

    private static final String AWARD_URL = "https://retroachievements.org/dorequest.php";
    private static final String AWARD = "r=awardachievement&u=Raven&t=TOKEN-1&a=5501&h=0&m=abc&o=3&v=sig";

    /** Frames until the condition holds; the network threads are real. */
    private static void framesUntil(PlayerActivity activity, java.util.function.BooleanSupplier condition)
            throws Exception {
        for (int i = 0; i < 2500 && !condition.getAsBoolean(); i++) {
            activity.onDrawFrame(null);
            idle();
            Thread.sleep(2);
        }
        assertTrue("Condition reached within five seconds", condition.getAsBoolean());
    }

    @Test public void anUnlockWaitsInTheJournalWhenThePlayerClosesThenReachesTheService() throws Exception {
        android.content.Context app = RuntimeEnvironment.getApplication();
        new AchievementAccount("Raven", "TOKEN-1", "", "", 0, 0).save(app);
        AchievementSettings.DEFAULTS.withSound(false).save(app);
        FakeAchievements.afterAnswer.add(new String[]{"login", "Raven", "Raven", "TOKEN-1", "", ""});
        final boolean[] journaledFirst = {false};
        FakeHttp.onSend = (url, post) -> {
            if (post.startsWith("r=awardachievement"))
                journaledFirst[0] = AwardJournal.open(app).forAccount("Raven").size() == 1;
        };
        PlayerActivity activity = loaded();
        framesUntil(activity, () -> FakeAchievements.answers.size() >= 1);
        // The award the module asks for: the network never answers.
        FakeAchievements.request(2, AWARD_URL, AWARD);
        framesUntil(activity, () -> FakeAchievements.answers.size() >= 2);
        assertTrue("Journaled before it is sent", journaledFirst[0]);
        assertEquals(AchievementsHttp.NO_ANSWER, FakeAchievements.answers.get(1)[2]);
        assertEquals(1, AwardJournal.open(app).forAccount("Raven").size());
        // The player closes, as a rotation does.
        ActivityController<PlayerActivity> controller = activities.remove(activities.size() - 1);
        controller.pause().stop().destroy();
        io(activity).submit(() -> {}).get(5, TimeUnit.SECONDS);
        assertEquals("Kept for the next session", 1, AwardJournal.open(app).forAccount("Raven").size());

        // Next session, a new token: the unlock is sent again with it, and accepted.
        FakeHttp.reset();
        FakeAchievements.reset();
        new AchievementAccount("Raven", "TOKEN-2", "", "", 0, 0).save(app);
        FakeAchievements.afterAnswer.add(new String[]{"login", "Raven", "Raven", "TOKEN-2", "", ""});
        FakeHttp.answer(200, "{\"Success\":true,\"User\":\"Raven\"}");
        FakeHttp.answer(200, "{\"Success\":true,\"Score\":5,\"AchievementID\":5501}");
        PlayerActivity next = loaded();
        framesUntil(next, () -> AwardJournal.open(app).forAccount("Raven").isEmpty());
        assertTrue(FakeHttp.sent.contains(AWARD_URL + " " + AWARD.replace("t=TOKEN-1", "t=TOKEN-2")));
    }

    @Test public void theGameMenuShowsTheAccountItsAchievementsAndLeaderboards() throws Exception {
        android.content.Context app = RuntimeEnvironment.getApplication();
        new AchievementAccount("Raven", "TOKEN-1", "Raven Rouge", "", 34, 12).save(app);
        AchievementSettings.DEFAULTS.withSound(false).save(app);
        FakeAchievements.summary = new String[]{"Raven", "Raven Rouge", "Libre", "12", "34", "1234", "1", "2", "5", "15"};
        FakeAchievements.entries.add(new String[]{"5501", "Premier pas", "Finir le niveau", "5", "2", "", "1700000000",
                "5", "", "100"});
        FakeAchievements.entries.add(new String[]{"5502", "Collectionneur", "Tout ramasser", "10", "1", "3/10", "0",
                "1", "", "30"});
        FakeAchievements.boards.add(new String[]{"4401", "Course", "Le plus haut", "", "1", "0"});
        PlayerActivity activity = loaded();
        openMenu(activity);
        pump(activity);
        android.view.View decor = menuDialog().getWindow().getDecorView();
        View card = find(decor, view -> view.getContentDescription() != null
                && view.getContentDescription().toString().startsWith("RetroAchievements. Raven Rouge"));
        assertNotNull("The account right under the heading", card);
        assertTrue(card.getContentDescription().toString().contains("1/2 succès  ·  5/15 pts"));
        card.performClick();
        pump(activity);
        assertNotNull(find(decor, view -> view.isShown() && view.getContentDescription() != null
                && view.getContentDescription().toString().equals("Premier pas, 5 points, Débloqué")));
        assertNotNull(find(decor, view -> view.isShown() && view.getContentDescription() != null
                && view.getContentDescription().toString().equals("Collectionneur, 10 points, 3/10")));
        assertNull("The menu waits behind the panel", find(decor, view -> view.isShown() && view instanceof TextView
                && "Reprendre le jeu".contentEquals(((TextView)view).getText())));
        // A leaderboard: the query goes through the GL thread, the scores come back with a frame.
        find(decor, view -> "Classement Course".contentEquals(view.getContentDescription() == null ? ""
                : view.getContentDescription())).performClick();
        pump(activity);
        assertEquals(java.util.Collections.singletonList("4401 false"), FakeAchievements.fetched);
        FakeAchievements.scores = new String[]{"4401", "2", "0", "1", "Corbeau", "000120", "1615654895", "0",
                "2", "Raven", "000060", "1615634566", "1"};
        FakeAchievements.raise("scores", "", "2", "top", "", "4401");
        activity.onDrawFrame(null);
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(300));
        pump(activity);
        assertNotNull(find(decor, view -> view.isShown() && view instanceof TextView
                && "#2 · Raven (vous)\n000060".contentEquals(((TextView)view).getText())));
        // Signing out from the panel: the token and the waiting unlocks go, the session stops.
        find(decor, view -> "Se déconnecter de RetroAchievements".contentEquals(view.getContentDescription() == null
                ? "" : view.getContentDescription())).performClick();
        pump(activity);
        assertNull(AchievementAccount.load(app));
        assertTrue(FakeAchievements.log.stream().anyMatch(l -> l.startsWith("stop ")));
        assertNotNull(find(decor, view -> view.isShown() && view.getContentDescription() != null
                && view.getContentDescription().toString().startsWith("RetroAchievements. Non connecté")));
    }

    @Test public void signedOutTheCardLeadsToTheAccountSettings() throws Exception {
        PlayerActivity activity = loaded();
        openMenu(activity);
        pump(activity);
        View card = find(menuDialog().getWindow().getDecorView(), view -> view.getContentDescription() != null
                && view.getContentDescription().toString().startsWith("RetroAchievements. Non connecté"));
        assertNotNull(card);
        card.performClick();
        Intent next = shadowOf(activity).getNextStartedActivity();
        assertEquals(PlaybackSettingsActivity.class.getName(), next.getComponent().getClassName());
        assertEquals(PlaybackSettingsActivity.RETRO, next.getStringExtra(PlaybackSettingsActivity.EXTRA_PAGE));
        assertTrue("No account, no session", FakeAchievements.log.isEmpty());
    }

    @Test public void theDisplayRateReachesThePacerAndTheScreenStaysOnWhilePlaying() throws Exception {
        PlayerActivity activity = loaded();
        // Robolectric changes the refresh rate on this Display instance, not globally.
        GLSurfaceView playerSurface = ReflectionHelpers.getField(activity, "surface");
        android.view.Display display = playerSurface.getDisplay();
        assertNotNull("The player surface must be attached to a display", display);
        assertEquals("The native pacer knows the display", display.getRefreshRate(), FakeNative.displayRate, 0.01f);
        assertEquals("A refresh rate the game divides is kept while it is on screen",
                display.getMode().getModeId(), activity.getWindow().getAttributes().preferredDisplayModeId);
        assertTrue((activity.getWindow().getAttributes().flags
                & android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0);
        // The system switches the display: the pacer hears of it.
        shadowOf(display).setRefreshRate(120f);
        android.hardware.display.DisplayManager.DisplayListener listener =
                ReflectionHelpers.getField(activity, "displayListener");
        listener.onDisplayChanged(android.view.Display.DEFAULT_DISPLAY);
        pump(activity);
        assertEquals(120f, FakeNative.displayRate, 0.01f);
        openMenu(activity);
        assertEquals("The menu lets the screen sleep", 0, activity.getWindow().getAttributes().flags
                & android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Test public void gameBoyGamesInColorRunOnTheGbcCoreWithTheirColorsAndTheirOwnStates() throws Exception {
        new ConsoleSettings(true, 3, true, ConsoleSettings.BIOS_RAVENCORE).save(RuntimeEnvironment.getApplication());
        ActivityController<PlayerActivity> controller = open(null);
        PlayerActivity activity = controller.get();
        activity.onWindowFocusChanged(true); pump(activity);
        assertEquals("gbc", FakeNative.openCore);
        assertArrayEquals(new String[]{"ravenemu_gbc_color_correction", "disabled",
                "ravenemu_gbc_frame_blending", "disabled", "ravenemu_gbc_dmg_colors", "a_up",
                "ravenemu_gbc_device_sensors", "enabled", "ravenemu_gbc_hardware", "gba"}, FakeNative.openOptions);
        assertEquals("A state taken on the GBC of a GBA only reloads there",
                COMPATIBILITY + "|gbc-on-gba", ReflectionHelpers.getField(activity, "stateCompatibility"));
        controller.pause();
        ConsoleSettings.load(activity).withGbColors(5).withGbcOnGba(false).save(activity);
        controller.resume(); activity.onWindowFocusChanged(true); pump(activity);
        assertArrayEquals("The colors change at once; the console waits for the next launch",
                new String[]{"ravenemu_gbc_color_correction", "disabled", "ravenemu_gbc_frame_blending", "disabled",
                        "ravenemu_gbc_dmg_colors", "left", "ravenemu_gbc_device_sensors", "enabled"},
                FakeNative.liveOptions);
        assertEquals(COMPATIBILITY + "|gbc-on-gba", ReflectionHelpers.getField(activity, "stateCompatibility"));
        assertEquals(1, FakeNative.opens);
    }

    @Test public void theRealGbaBiosRunsOnlyOnceInstalledAndNamesItsStates() throws Exception {
        key = RomLibrary.importRom(files, "Libre.gba", new byte[0x200]).key;
        new ConsoleSettings(false, 0, false, ConsoleSettings.BIOS_REAL).save(RuntimeEnvironment.getApplication());
        PlayerActivity missing = loaded();
        assertEquals("gba", FakeNative.openCore);
        assertEquals("disabled", option(FakeNative.openOptions, "ravenemu_gba_bios"));
        assertEquals(COMPATIBILITY, ReflectionHelpers.getField(missing, "stateCompatibility"));
        assertTrue(ShadowToast.getTextOfLatestToast().startsWith("Vrai BIOS GBA absent"));

        // A synthetic 16 KiB file stands for the player's BIOS.
        byte[] bios = new byte[GbaBiosRepository.SIZE];
        Arrays.fill(bios, (byte)0x5a);
        GbaBiosRepository.install(files, new ByteArrayInputStream(bios));
        ShadowToast.reset();
        PlayerActivity installed = loaded();
        assertEquals("enabled", option(FakeNative.openOptions, "ravenemu_gba_bios"));
        assertEquals(COMPATIBILITY + "|gba-bios-" + GbaBiosRepository.digest(bios),
                ReflectionHelpers.getField(installed, "stateCompatibility"));
        assertNull(ShadowToast.getLatestToast());
    }

    @Test public void theCartridgeMotorVibratesOnlyWhileTheGameRunsAndTheSettingAllowsIt() throws Exception {
        ActivityController<PlayerActivity> controller = open(null);
        PlayerActivity activity = controller.get();
        activity.onWindowFocusChanged(true); pump(activity);
        ShadowVibrator vibrator = shadowOf(activity.getSystemService(android.os.VibratorManager.class)
                .getDefaultVibrator());
        ShadowVibrator.reset();
        activity.onDrawFrame(null);
        assertFalse("A motor at rest leaves the phone alone", vibrator.isVibrating());
        FakeNative.rumbling = true;
        activity.onDrawFrame(null);
        assertTrue(vibrator.isVibrating());
        // Leaving stops the vibrator at once.
        controller.pause();
        assertTrue(vibrator.isCancelled());
        // Settings → Interface turns it off: the game's motor is ignored.
        new CartridgeSettings(false, true, true, 50).save(activity);
        ShadowVibrator.reset();
        controller.resume(); activity.onWindowFocusChanged(true); pump(activity);
        activity.onDrawFrame(null);
        assertFalse(vibrator.isVibrating());
        assertEquals("The sensors reach the core all the same", "enabled",
                option(FakeNative.liveOptions, "ravenemu_gb_device_sensors"));
        // On again, but the menu pauses the game: no vibration either.
        new CartridgeSettings(true, false, true, 50).save(activity);
        controller.pause(); controller.resume(); activity.onWindowFocusChanged(true); pump(activity);
        assertEquals("disabled", option(FakeNative.liveOptions, "ravenemu_gb_device_sensors"));
        openMenu(activity);
        ShadowVibrator.reset();
        activity.onDrawFrame(null);
        assertFalse(vibrator.isVibrating());
    }

    @Test public void preemptiveFramesReachTheNativePlayerOffByDefault() throws Exception {
        ActivityController<PlayerActivity> controller = open(null);
        PlayerActivity activity = controller.get();
        activity.onWindowFocusChanged(true); pump(activity);
        assertEquals(0, FakeNative.preemptive);
        controller.pause();
        new LatencySettings(2).save(activity);
        controller.resume(); activity.onWindowFocusChanged(true); pump(activity);
        assertEquals("Changed in the settings, taken without reopening the game", 2, FakeNative.preemptive);
        assertEquals(1, FakeNative.opens);
    }

    @Test public void theTiltFollowsTheScreenAsItTurns() throws Exception {
        PlayerActivity activity = loaded();
        android.view.Display display = defaultDisplay(activity);
        assertEquals(display.getRotation(), FakeNative.rotation);
        // A half turn keeps the activity: only the display says so.
        shadowOf(display).setRotation(android.view.Surface.ROTATION_270);
        android.hardware.display.DisplayManager.DisplayListener listener =
                ReflectionHelpers.getField(activity, "displayListener");
        listener.onDisplayChanged(android.view.Display.DEFAULT_DISPLAY);
        pump(activity);
        assertEquals(android.view.Surface.ROTATION_270, FakeNative.rotation);
    }

    private static android.view.Display defaultDisplay(PlayerActivity activity) {
        return activity.getSystemService(android.hardware.display.DisplayManager.class)
                .getDisplay(android.view.Display.DEFAULT_DISPLAY);
    }

    /** The value given to `key`, or null without it; keys and values alternate. */
    private static String option(String[] options, String key) {
        for (int i = 0; i + 1 < options.length; i += 2) if (options[i].equals(key)) return options[i + 1];
        return null;
    }

    @Test public void delayedPlaybackPreferenceCommandCannotRunAfterPauseOrDestruction() throws Exception {
        ActivityController<PlayerActivity> controller = open(null);
        controller.pause();
        gl(controller.get()).drain();
        assertEquals(0, FakeNative.settingsApplies);
        controller.resume();
        controller.pause().stop().destroy(); activities.remove(controller);
        gl(controller.get()).drain();
        assertEquals(0, FakeNative.settingsApplies);
    }

    @Test public void nativePacingOwnsEveryDrawAndAutosaveWaitsForAnExecutedCoreFrame() throws Exception {
        PlayerActivity activity = loaded();
        int reads = FakeNative.batteryReads;
        FakeNative.framesToAdvance = 0;
        activity.onDrawFrame(null);
        activity.onDrawFrame(null);
        assertTrue("Java must leave the cadence decision to C on each draw", FakeNative.lastAdvance);
        assertEquals(2, FakeNative.draws);
        assertEquals("A repeated video frame must not trigger SRAM IO", reads, FakeNative.batteryReads);
        FakeNative.framesToAdvance = 2;
        activity.onDrawFrame(null);
        assertEquals("One save per draw, even when native pacing catches up two core frames",
                reads + 1, FakeNative.batteryReads);
        pump(activity);
    }

    @Test public void focusAndMenuReasonsPreventFramesAndBackgroundDismissCannotResume() throws Exception {
        ActivityController<PlayerActivity> controller = open(null);
        PlayerActivity activity = controller.get();
        activity.onWindowFocusChanged(false);
        pump(activity);
        activity.onDrawFrame(null);
        assertFalse(FakeNative.lastAdvance);
        activity.onWindowFocusChanged(true);
        pump(activity);
        activity.onDrawFrame(null);
        assertTrue(FakeNative.lastAdvance);
        assertFalse(FakeNative.paused);
        openMenu(activity);
        activity.onDrawFrame(null);
        assertTrue(FakeNative.paused);
        assertFalse(FakeNative.lastAdvance);
        controller.pause();
        menuButton("Reprendre le jeu").performClick();
        idle();
        pump(activity);
        assertTrue(FakeNative.paused);
        controller.resume();
        activity.onWindowFocusChanged(false);
        pump(activity);
        assertTrue(FakeNative.paused);
        activity.onWindowFocusChanged(true);
        pump(activity);
        assertFalse(FakeNative.paused);
    }

    @Test public void realMenuSavesFourIndependentSlotsLoadsAndDeletesOnlyTheSelection() throws Exception {
        PlayerActivity activity = loaded();
        openMenu(activity);
        for (int slot = 0; slot < 4; slot++) {
            choice(slot).performClick();
            FakeNative.state = new byte[]{(byte)(10 + slot), 42};
            menuButton("Sauver").performClick();
            pump(activity);
            assertArrayEquals(new byte[]{(byte)(10 + slot), 42},
                    StateSlotRepository.read(files, key, slot, COMPATIBILITY));
        }
        assertTrue(Arrays.stream(StateSlotRepository.list(files, key, COMPATIBILITY)).allMatch(s -> s.occupied));
        FakeNative.state = new byte[]{99};
        choice(1).performClick();
        menuButton("Charger").performClick();
        pump(activity);
        assertArrayEquals(new byte[]{11, 42}, FakeNative.state);
        assertFalse(menu(activity).isShowing());
        openMenu(activity);
        choice(2).performClick();
        menuButton("Supprimer").performClick();
        ((AlertDialog)ShadowDialog.getLatestDialog()).getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        idle();
        pump(activity);
        StateSlotRepository.Slot[] remaining = StateSlotRepository.list(files, key, COMPATIBILITY);
        assertFalse(remaining[2].occupied);
        assertTrue(remaining[0].occupied && remaining[1].occupied && remaining[3].occupied);
    }

    @Test public void changingCompatibilityOrDamagingFileAfterListingNeverCallsRestore() throws Exception {
        PlayerActivity activity = loaded();
        StateSlotRepository.write(files, key, 0, COMPATIBILITY, new byte[]{20});
        openMenu(activity);
        // The menu saw a valid slot. Disk validation must still happen again
        // at the operation boundary before any call into the core.
        StateSlotRepository.write(files, key, 0, "different-core-build", new byte[]{21});
        menuButton("Charger").performClick();
        pump(activity);
        assertTrue(FakeNative.restoreAttempts.isEmpty());
        assertTrue(menu(activity).isShowing());
        StateSlotRepository.write(files, key, 0, COMPATIBILITY, new byte[]{22});
        try (RandomAccessFile damaged = new RandomAccessFile(new File(files,
                "native-saves/" + key + "-slot0.state"), "rw")) {
            // V2 ends in an optional preview hash. Damage the actual core
            // payload so this still tests rejection before deserialization.
            damaged.seek(8 + 4 + 1 + 64 + 2 + COMPATIBILITY.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 8 + 4 + 32);
            damaged.write(23);
        }
        menuButton("Charger").performClick();
        pump(activity);
        assertTrue(FakeNative.restoreAttempts.isEmpty());
        assertTrue(FakeNative.paused);
        assertTrue(menu(activity).isShowing());
    }

    @Test public void backgroundingAfterDiskReadBeforeGlRestoreCancelsTheLoad() throws Exception {
        ActivityController<PlayerActivity> controller = open(null);
        PlayerActivity activity = controller.get();
        activity.onWindowFocusChanged(true);
        pump(activity);
        StateSlotRepository.write(files, key, 0, COMPATIBILITY, new byte[]{30});
        openMenu(activity);
        byte[] before = FakeNative.state.clone();
        menuButton("Charger").performClick();
        io(activity).submit(() -> {}).get(5, TimeUnit.SECONDS);
        assertTrue(gl(activity).pending() > 0);
        controller.pause();
        pump(activity);
        assertTrue(FakeNative.restoreAttempts.isEmpty());
        assertArrayEquals(before, FakeNative.state);
        assertTrue(menu(activity).isShowing());
        assertTrue(FakeNative.paused);
        controller.resume();
        activity.onWindowFocusChanged(true);
        pump(activity);
        assertTrue("An open menu still holds pause after returning", FakeNative.paused);
        menuButton("Reprendre le jeu").performClick();
        idle();
        activity.onWindowFocusChanged(true);
        pump(activity);
        assertFalse(FakeNative.paused);
    }

    @Test public void failedRestoreRollsBackCurrentStateAndKeepsTheMenuPaused() throws Exception {
        PlayerActivity activity = loaded();
        StateSlotRepository.write(files, key, 0, COMPATIBILITY, new byte[]{40});
        openMenu(activity);
        FakeNative.state = new byte[]{41, 42};
        FakeNative.failRestores = 1;
        menuButton("Charger").performClick();
        pump(activity);
        assertEquals(2, FakeNative.restoreAttempts.size());
        assertArrayEquals(new byte[]{40}, FakeNative.restoreAttempts.get(0));
        assertArrayEquals(new byte[]{41, 42}, FakeNative.restoreAttempts.get(1));
        assertArrayEquals(new byte[]{41, 42}, FakeNative.state);
        assertArrayEquals(new byte[]{40}, StateSlotRepository.read(files, key, 0, COMPATIBILITY));
        assertTrue(menu(activity).isShowing());
        assertTrue(FakeNative.paused);
        assertFalse(activity.isFinishing());
    }

    @Test public void failedLoadAndRollbackStopTheGameWithoutDeletingSramOrSlot() throws Exception {
        PlayerActivity activity = loaded();
        SaveRepository.write(files, key, "srm", new byte[]{50, 51});
        StateSlotRepository.write(files, key, 0, COMPATIBILITY, new byte[]{52});
        openMenu(activity);
        FakeNative.failRestores = 2;
        menuButton("Charger").performClick();
        pump(activity);
        assertTrue(activity.isFinishing());
        assertTrue(FakeNative.closed);
        assertArrayEquals(new byte[]{50, 51}, SaveRepository.read(files, key, "srm"));
        assertArrayEquals(new byte[]{52}, StateSlotRepository.read(files, key, 0, COMPATIBILITY));
    }

    @Test public void recreatedActivityReadsSramAfterThePreviousQueuedPauseWrite() throws Exception {
        ActivityController<PlayerActivity> first = open(null);
        PlayerActivity old = first.get();
        old.onWindowFocusChanged(true);
        pump(old);
        ExecutorService storage = io(old);
        CountDownLatch blocked = new CountDownLatch(1), release = new CountDownLatch(1);
        storage.execute(() -> {
            blocked.countDown();
            try { release.await(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        assertTrue(blocked.await(5, TimeUnit.SECONDS));
        byte[] latest = {60, 61, 62};
        try {
            FakeNative.battery = latest.clone();
            first.pause().stop().destroy();
            activities.remove(first);
            ActivityController<PlayerActivity> second = open(null);
            assertSame("All preview activities must preserve one SRAM disk ordering", storage, io(second.get()));
            assertEquals(1, FakeNative.opens);
            release.countDown();
            second.get().onWindowFocusChanged(true);
            pump(second.get());
            assertEquals(2, FakeNative.opens);
            assertArrayEquals(latest, FakeNative.restoredBatteries.get(FakeNative.restoredBatteries.size() - 1));
            assertArrayEquals(latest, SaveRepository.read(files, key, "srm"));
        } finally { release.countDown(); }
    }

    @Test public void completedLoadCannotDismissANewPauseMenuOpenedWhileReading() throws Exception {
        PlayerActivity activity = loaded();
        StateSlotRepository.write(files, key, 0, COMPATIBILITY, new byte[]{70});
        openMenu(activity);
        PlayerMenuSheet oldMenu = menu(activity);
        menuButton("Charger").performClick();
        io(activity).submit(() -> {}).get(5, TimeUnit.SECONDS);
        oldMenu.dismiss();
        idle();
        View menuTrigger = find(activity.getWindow().getDecorView(), view ->
                "Menu du jeu".contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription()));
        assertNotNull(menuTrigger);
        menuTrigger.performClick();
        PlayerMenuSheet newMenu = menu(activity);
        assertNotSame(oldMenu, newMenu);
        pump(activity);
        assertArrayEquals(new byte[]{70}, FakeNative.state);
        assertTrue("A new pause request must survive completion of an older load", newMenu.isShowing());
        assertTrue(FakeNative.paused);
    }

    @Test public void saveCapturesAPreviewAndSavingWithoutAFrameStillKeepsTheState() throws Exception {
        PlayerActivity activity = loaded();
        openMenu(activity);
        menuButton("Sauver").performClick();
        pump(activity);
        StateSlotRepository.Slot saved = StateSlotRepository.list(files, key, COMPATIBILITY)[0];
        android.graphics.Bitmap preview = StatePreview.decode(saved.thumbnail);
        assertNotNull(preview);
        assertEquals(0xffff0000, preview.getPixel(0, 0));
        choice(1).performClick();
        FakeNative.preview = null;
        FakeNative.state = new byte[]{80};
        menuButton("Sauver").performClick();
        pump(activity);
        assertNull(StateSlotRepository.list(files, key, COMPATIBILITY)[1].thumbnail);
        assertArrayEquals(new byte[]{80}, StateSlotRepository.read(files, key, 1, COMPATIBILITY));
    }

    @Test public void undoRestoresTheStateBeforeTheMostRecentSuccessfulLoadOnlyOnce() throws Exception {
        PlayerActivity activity = loaded();
        FakeNative.state = new byte[]{81};
        StateSlotRepository.write(files, key, 0, COMPATIBILITY, new byte[]{82});
        openMenu(activity);
        menuButton("Charger").performClick(); pump(activity);
        FakeNative.state = new byte[]{83};
        StateSlotRepository.write(files, key, 1, COMPATIBILITY, new byte[]{84});
        openMenu(activity);
        choice(1).performClick(); menuButton("Charger").performClick(); pump(activity);
        assertArrayEquals(new byte[]{84}, FakeNative.state);
        openMenu(activity);
        menuButton("Annuler le chargement").performClick(); pump(activity);
        assertArrayEquals(new byte[]{83}, FakeNative.state);
        assertFalse(menu(activity).isShowing());
        openMenu(activity);
        View undo = menuButton("Annuler le chargement");
        assertEquals(View.GONE, undo.getVisibility());
        assertArrayEquals(new byte[]{84}, StateSlotRepository.read(files, key, 1, COMPATIBILITY));
    }

    @Test public void failedUndoRollsBackCurrentProgressAndRemainsRetryable() throws Exception {
        PlayerActivity activity = loaded();
        FakeNative.state = new byte[]{85};
        StateSlotRepository.write(files, key, 0, COMPATIBILITY, new byte[]{86});
        openMenu(activity); menuButton("Charger").performClick(); pump(activity);
        FakeNative.state = new byte[]{87};
        openMenu(activity);
        FakeNative.failRestores = 1;
        menuButton("Annuler le chargement").performClick(); pump(activity);
        assertArrayEquals(new byte[]{87}, FakeNative.state);
        assertTrue(FakeNative.paused);
        assertTrue(menu(activity).isShowing());
        assertTrue(menuButton("Annuler le chargement").isEnabled());
        menuButton("Annuler le chargement").performClick(); pump(activity);
        assertArrayEquals(new byte[]{85}, FakeNative.state);
    }

    @Test public void backgroundUndoDoesNotConsumeTheUndoPointAndRecreationClearsIt() throws Exception {
        ActivityController<PlayerActivity> controller = open(null);
        PlayerActivity activity = controller.get();
        activity.onWindowFocusChanged(true); pump(activity);
        FakeNative.state = new byte[]{88};
        StateSlotRepository.write(files, key, 0, COMPATIBILITY, new byte[]{89});
        openMenu(activity); menuButton("Charger").performClick(); pump(activity);
        openMenu(activity); menuButton("Annuler le chargement").performClick();
        controller.pause(); pump(activity);
        assertArrayEquals(new byte[]{89}, FakeNative.state);
        assertEquals(1, FakeNative.restoreAttempts.size());
        controller.resume(); activity.onWindowFocusChanged(true); pump(activity);
        assertTrue(menuButton("Annuler le chargement").isEnabled());
        Bundle saved = new Bundle();
        controller.saveInstanceState(saved).pause().stop().destroy(); activities.remove(controller);
        PlayerActivity recreated = open(saved).get();
        recreated.onWindowFocusChanged(true); pump(recreated);
        assertEquals(View.GONE, menuButton("Annuler le chargement").getVisibility());
    }

    @Test public void recreatedActivityKeepsTheOpenMenuAndSelectedSlot() throws Exception {
        ActivityController<PlayerActivity> first = open(null);
        first.get().onWindowFocusChanged(true);
        pump(first.get());
        openMenu(first.get());
        choice(3).performClick();
        Bundle saved = new Bundle();
        first.saveInstanceState(saved).pause().stop().destroy();
        activities.remove(first);
        ActivityController<PlayerActivity> restored = open(saved);
        restored.get().onWindowFocusChanged(true);
        pump(restored.get());
        assertTrue(menu(restored.get()).isShowing());
        assertEquals(3, menu(restored.get()).getSelectedSlot());
        assertTrue(FakeNative.paused);
    }

    private PlayerActivity loaded() throws Exception {
        PlayerActivity activity = open(null).get();
        activity.onWindowFocusChanged(true);
        pump(activity);
        return activity;
    }

    private ActivityController<PlayerActivity> open(Bundle saved) {
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), PlayerActivity.class)
                .putExtra(PlayerActivity.EXTRA_ROM_KEY, key);
        ActivityController<PlayerActivity> controller = Robolectric.buildActivity(PlayerActivity.class, intent)
                .create(saved).start().resume().visible();
        activities.add(controller);
        return controller;
    }

    private void openMenu(PlayerActivity activity) throws Exception {
        View button = find(activity.getWindow().getDecorView(), view ->
                "Menu du jeu".contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription()));
        assertNotNull(button);
        button.performClick();
        pump(activity);
        assertTrue(menu(activity).isShowing());
        assertTrue(FakeNative.paused);
    }

    private static PlayerMenuSheet menu(PlayerActivity activity) { return ReflectionHelpers.getField(activity, "playerMenu"); }
    private static ExecutorService io(PlayerActivity activity) { return ReflectionHelpers.getField(activity, "io"); }
    private static QueuedGl gl(PlayerActivity activity) {
        return Shadow.extract((GLSurfaceView)ReflectionHelpers.getField(activity, "surface"));
    }

    private void pump(PlayerActivity activity) throws Exception {
        // Both load and save contain an IO -> GL -> IO handoff. Repeated
        // executor barriers drain those bounded stages without sleeping.
        for (int i = 0; i < 5; i++) {
            io(activity).submit(() -> {}).get(5, TimeUnit.SECONDS);
            gl(activity).drain();
            idle();
        }
    }

    private static void idle() { shadowOf(Looper.getMainLooper()).idle(); }
    private View menuButton(String label) {
        View result = find(menuDialog().getWindow().getDecorView(), view ->
                view instanceof TextView && label.contentEquals(((TextView)view).getText()));
        assertNotNull(label, result);
        return result;
    }
    private View choice(int index) {
        View result = find(menuDialog().getWindow().getDecorView(), view -> view.getContentDescription() != null
                && view.getContentDescription().toString().startsWith("Emplacement " + (index + 1) + ","));
        assertNotNull(result);
        return result;
    }
    private Dialog menuDialog() {
        return ShadowDialog.getShownDialogs().stream().filter(Dialog::isShowing)
                .filter(d -> !(d instanceof AlertDialog)).findFirst().orElseThrow();
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

    @Implements(value = GLSurfaceView.class)
    public static class QueuedGl extends ShadowGLSurfaceView {
        private final ArrayDeque<Runnable> events = new ArrayDeque<>();
        static boolean executing;
        @Implementation protected void setRenderer(GLSurfaceView.Renderer renderer) {}
        @Implementation protected void setRenderMode(int mode) {}
        @Implementation protected void queueEvent(Runnable event) { synchronized(events) { events.add(event); } }
        @Implementation protected void onPause() {}
        @Implementation protected void onResume() {}
        int pending() { synchronized(events) { return events.size(); } }
        void drain() {
            while (true) {
                Runnable event;
                synchronized(events) { event = events.poll(); }
                if (event == null) return;
                executing = true;
                try { event.run(); } finally { executing = false; }
            }
        }
    }

    @Implements(value = NativeBridge.class, isInAndroidSdk = false)
    public static class FakeNative {
        static byte[] state, battery;
        static int[] preview;
        static final List<byte[]> restoreAttempts = new ArrayList<>(), restoredBatteries = new ArrayList<>();
        static int opens, failRestores;
        static boolean closed, paused, lastAdvance;
        static int framesToAdvance, draws, batteryReads;
        static int settingsApplies;
        static PlaybackSettings appliedSettings;
        static int appliedEffect = -1, appliedResampler = -1;
        static String[] openOptions, liveOptions;
        static String openCore;
        static float displayRate;
        static double fps;
        static String message;
        static int buttons, rotation, preemptive;
        static boolean rumbling;
        static void reset() {
            state = new byte[]{1, 2}; battery = new byte[]{3, 4};
            preview = new int[]{2, 1, 0xffff0000, 0xff00ff00};
            restoreAttempts.clear(); restoredBatteries.clear();
            opens = failRestores = 0; closed = lastAdvance = false; paused = true;
            framesToAdvance = 1; draws = batteryReads = 0;
            settingsApplies = 0; appliedSettings = null; appliedEffect = -1; appliedResampler = -1;
            openOptions = liveOptions = null;
            openCore = null;
            displayRate = 0f;
            fps = 59.727500569606;
            message = null;
            buttons = 0;
            rotation = -1;
            preemptive = -1;
            rumbling = false;
        }
        private static void requireGl() { assertTrue("Core operation must use the queued GL boundary", QueuedGl.executing); }
        @Implementation protected static void __staticInitializer__() {}
        @Implementation protected static void initGl() {}
        @Implementation protected static String openRom(String nativeDir, String filesDir, String system, byte[] rom,
                                                        String title, String[] coreOptions) {
            requireGl(); opens++; closed = false; state = new byte[]{1, 2}; battery = new byte[]{0};
            openCore = system; openOptions = coreOptions.clone(); return null;
        }
        @Implementation protected static void setDisplayRate(float hertz) { requireGl(); displayRate = hertz; }
        @Implementation protected static void setDisplayRotation(int value) { requireGl(); rotation = value; }
        @Implementation protected static void setPreemptiveFrames(int frames) { requireGl(); preemptive = frames; }
        /** As the bridge: a paused or closed game never vibrates. */
        @Implementation protected static boolean rumbling() { return rumbling && !paused && !closed; }
        @Implementation protected static String takeMessage() {
            String taken = message; message = null; return taken;
        }
        @Implementation protected static double frameRate() { requireGl(); return closed ? 0.0 : fps; }
        @Implementation protected static void setCoreOptions(String[] coreOptions) {
            requireGl(); liveOptions = coreOptions.clone();
        }
        @Implementation protected static String stateCompatibility() { requireGl(); return COMPATIBILITY; }
        @Implementation protected static byte[] saveState() { requireGl(); return state.clone(); }
        @Implementation protected static int[] capturePreview() {
            requireGl(); return preview == null ? null : preview.clone();
        }
        @Implementation protected static byte[] saveBattery() { batteryReads++; return closed ? null : battery.clone(); }
        @Implementation protected static boolean restoreBattery(byte[] value) {
            requireGl(); restoredBatteries.add(value.clone()); battery = value.clone(); return true;
        }
        @Implementation protected static boolean restoreState(byte[] value) {
            requireGl(); restoreAttempts.add(value.clone());
            if (failRestores > 0) { failRestores--; state = new byte[]{127}; return false; }
            state = value.clone(); return true;
        }
        @Implementation protected static void setPaused(boolean value) { requireGl(); paused = value; }
        @Implementation protected static void setPlaybackOptions(boolean audioEnabled, int volume,
                boolean integerScale, int brightness, int contrast, int screenEffect, int resampler) {
            appliedResampler = resampler;
            requireGl(); settingsApplies++;
            appliedSettings = new PlaybackSettings(audioEnabled, volume, integerScale, brightness, contrast);
            appliedEffect = screenEffect;
        }
        @Implementation protected static void setButtons(int mask) { buttons = mask; }
        @Implementation protected static void clearButtons() {}
        @Implementation protected static void setAudioEnabled(boolean enabled) {}
        @Implementation protected static int drawFrame(int width, int height, boolean advance) {
            draws++; lastAdvance = advance; return advance ? framesToAdvance : 0;
        }
        @Implementation protected static void closeRom() { closed = true; }
    }
}
