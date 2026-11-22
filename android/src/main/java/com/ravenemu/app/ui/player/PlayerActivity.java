package com.ravenemu.app.ui.player;
import com.ravenemu.app.bridge.NativeBridge;
import com.ravenemu.app.data.library.CoverRepository;
import com.ravenemu.app.data.library.LinkCable;
import com.ravenemu.app.data.library.RomLibrary;
import com.ravenemu.app.data.saves.CheatList;
import com.ravenemu.app.data.saves.CheatRepository;
import com.ravenemu.app.data.saves.GbaBiosRepository;
import com.ravenemu.app.data.saves.SaveRepository;
import com.ravenemu.app.data.saves.SaveStorage;
import com.ravenemu.app.data.saves.SaveTransfer;
import com.ravenemu.app.data.saves.StatePreview;
import com.ravenemu.app.data.saves.StateSlotRepository;
import com.ravenemu.app.data.skins.DeltaSkin;
import com.ravenemu.app.data.skins.DeltaSkinException;
import com.ravenemu.app.data.skins.DeltaSkins;
import com.ravenemu.app.platform.AchievementsHttp;
import com.ravenemu.app.platform.RetroUserAgent;
import com.ravenemu.app.platform.SystemBarInsets;
import com.ravenemu.app.settings.AchievementAccount;
import com.ravenemu.app.settings.AchievementSettings;
import com.ravenemu.app.settings.AwardJournal;
import com.ravenemu.app.settings.HardcorePolicy;
import com.ravenemu.app.settings.CartridgeSettings;
import com.ravenemu.app.settings.ConsoleSettings;
import com.ravenemu.app.settings.ControlLayout;
import com.ravenemu.app.settings.ControlSettings;
import com.ravenemu.app.settings.CoreOptions;
import com.ravenemu.app.settings.LatencySettings;
import com.ravenemu.app.settings.PlaybackSettings;
import com.ravenemu.app.ui.common.RavenIcon;
import com.ravenemu.app.ui.common.RavenTheme;
import com.ravenemu.app.ui.settings.PlaybackSettingsActivity;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.hardware.display.DisplayManager;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/** Separate, non-distributable Java/NDK proof of concept.
 * Core calls use the GL thread while running, or a paused session during
 * lifecycle cleanup; button input uses an atomic mask. */
public final class PlayerActivity extends Activity implements GLSurfaceView.Renderer, RavenTheme.Host {
    public static final String EXTRA_ROM_KEY = "com.ravenemu.app.ROM_KEY";
    /** With EXTRA_ROM_KEY: the second game, joined to the first by the link cable. */
    public static final String EXTRA_LINK_ROM_KEY = "com.ravenemu.app.LINK_ROM_KEY";
    // Shared ordering: a recreated Activity must read SRAM only after the
    // previous Activity's queued pause snapshot has been written.
    /** Shared with the library's save transfers: one order for every save write. */
    private static final ExecutorService STORAGE_IO = SaveStorage.IO;
    private final ExecutorService io = STORAGE_IO;
    final AtomicBoolean disposed = new AtomicBoolean(false);
    GLSurfaceView surface;
    TextView gameTitle;
    /** The bar's ⋮: shown only where no touch Menu button is on screen. */
    View headerMenu;
    int width = 1, height = 1, touchButtons;
    /** UI thread: a physical controller's keys and axes. */
    final GamepadMapper gamepad = new GamepadMapper();
    GameControlsView gameControls;
    /** Two games joined by the link cable (the beta's LinkPlayerScreen): one
     * shown at a time, the other running behind; no game menu. */
    boolean linkMode;
    /** Each player's save key while linked games run; null otherwise. */
    volatile String[] linkKeys;
    /** UI thread: the linked games, the player shown, its switch in the bar. */
    RomLibrary.Entry[] linkEntries;
    int linkFocus;
    TextView linkSwitch;
    /** Portrait: the Delta skin chosen for the game's console, drawn over the
     * picture's column instead of the touch controls; null in landscape. */
    DeltaSkinView skinView;
    /** The picture and the achievements over it: the skin's game area when it shows. */
    FrameLayout pictureFrame;
    /** The picture, the touch controls and the skin over them; RetroAchievements'
     * banners and indicators cover it whole while a skin is shown. */
    FrameLayout skinStage;
    /** Landscape: where the picture sits beside the classic controls. */
    FrameLayout.LayoutParams classicLandscapePicture;
    /** UI thread: the open game's system, for its console's skin; null before a game. */
    String loadedSystem;
    /** Settings → Vidéo → Pixels entiers, as the renderer draws the picture. */
    boolean integerScale;
    int skinRequest;
    /** Skins are read and checked off the UI thread, apart from the saves. */
    static final ExecutorService SKINS = java.util.concurrent.Executors.newSingleThreadExecutor();
    /** Shown under the bar while the controls are moved. */
    LinearLayout editBar;
    boolean editingLandscape;
    /** UI thread: the open game's frame rate, for the display mode; 0 without. */
    double gameFps;
    DisplayManager displays;
    /** The refresh rate follows the display: 60, 90 or 120 Hz, as the system switches. */
    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int displayId) {}
        @Override public void onDisplayRemoved(int displayId) {}
        @Override public void onDisplayChanged(int displayId) {
            if (displayId == Display.DEFAULT_DISPLAY) displayPart.sendDisplay();
        }
    };
    /** The cartridge's motor on the phone's vibrator, after each drawn frame. */
    CartridgeRumble rumble;
    /** Settings → Interface: "Vibration de la cartouche". */
    private volatile boolean rumbleEnabled = CartridgeSettings.DEFAULTS.rumble;
    /** The controller played with: its motor rumbles and its sensors move the
     * cartridge's (UI thread picks it; -1 for the touch screen). */
    final ControllerMotion motion = new ControllerMotion();
    int controllerId = -1;
    volatile boolean sensorsWanted = CartridgeSettings.DEFAULTS.sensors;
    /** GL thread: what the native driver was last given of the controller's sensors. */
    private int fedMotion;
    private final float[] motionAcceleration = new float[3], motionTurning = new float[3];
    private final android.hardware.input.InputManager.InputDeviceListener controllers =
            new android.hardware.input.InputManager.InputDeviceListener() {
                @Override public void onInputDeviceAdded(int deviceId) {}
                @Override public void onInputDeviceChanged(int deviceId) {}
                @Override public void onInputDeviceRemoved(int deviceId) {
                    // A controller switched off sends no last motion: its
                    // stick or hat would stay held. The touch keeps its own.
                    gamepad.reset();
                    NativeBridge.setButtons(touchButtons);
                    if (deviceId == controllerId) inputPart.controllerUsed(null);
                }
            };
    /** RetroAchievements over the game (UI thread), and its session (GL thread). */
    AchievementOverlay achievementOverlay;
    PlayerAchievements achievements;
    final BadgeCache badges = new BadgeCache();
    /** The game whose achievements run, and the account they run with. */
    volatile RomLibrary.Entry activeEntry;
    volatile String activeAccount;
    boolean achievementsRefreshQueued;
    /** UI thread: rcheevos just allowed this pause (Hardcore). */
    boolean pauseGranted;
    long nextBatterySaveNanos;
    /** The game's cheats: written on the UI thread only, read on the GL one. */
    volatile List<CheatList.Cheat> cheats = Collections.emptyList();
    volatile String cheatsKey;
    /** GL thread: the Hardcore lock the core's cheats were last set for. */
    boolean cheatsLockApplied;
    volatile String activeSaveKey;
    /** GL thread: the core of the open game ("gbc" or "gba"), for its options. */
    String activeCore;
    volatile boolean menuOpen;
    volatile boolean gamePaused = true;
    volatile boolean foreground;
    volatile String stateCompatibility;
    PlayerPauseState pauseState;
    PlayerMenuSheet playerMenu;
    StateSlotRepository.Slot[] stateSlots;
    Bitmap[] statePreviews = new Bitmap[StateSlotRepository.SLOT_COUNT];
    volatile UndoState undoState;

    static final class UndoState {
        final String key, compatibility;
        final byte[] snapshot;
        UndoState(String key, String compatibility, byte[] snapshot) {
            this.key = key; this.compatibility = compatibility; this.snapshot = snapshot;
        }
    }

    static final class SlotBatch {
        final StateSlotRepository.Slot[] slots;
        final Bitmap[] previews;
        SlotBatch(StateSlotRepository.Slot[] slots, Bitmap[] previews) {
            this.slots = slots; this.previews = previews;
        }
    }
    boolean slotBusy, restoreMenu;
    int selectedSlot;
    private final Object ioGate = new Object();
    int menuGeneration;
    Runnable pendingRomLoad;
    boolean settingsLaunching;
    /** Settings → Interface → Thème, as it was when the game opened: a game
     * keeps its colours until it is closed, never restarted for a theme. */
    RavenTheme theme;

    // Each component works on this activity's state, on the threads its methods name.
    final PlayerSkins skinPart = new PlayerSkins(this);
    final PlayerRomLoader romPart = new PlayerRomLoader(this);
    final PlayerGameMenu menuPart = new PlayerGameMenu(this);
    final PlayerStateSlots slotPart = new PlayerStateSlots(this);
    final PlayerAchievementSession achievementPart = new PlayerAchievementSession(this);
    final PlayerDisplay displayPart = new PlayerDisplay(this);
    final PlayerInput inputPart = new PlayerInput(this);

    @Override public RavenTheme theme() { return theme; }

    @Override
    public void onCreate(Bundle state) {
        theme = RavenTheme.apply(this);
        super.onCreate(state);
        if (state != null) {
            restoreMenu = state.getBoolean("player.menu");
            selectedSlot = Math.max(0, Math.min(3, state.getInt("player.slot")));
            linkFocus = state.getInt("player.link_focus") == 1 ? 1 : 0;
        }
        String linkKey = getIntent().getStringExtra(EXTRA_LINK_ROM_KEY);
        linkMode = linkKey != null && CoverRepository.validKey(linkKey);
        if (linkMode) restoreMenu = false;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(theme.background);
        surface = new GLSurfaceView(this);
        surface.setEGLContextClientVersion(2);
        surface.setRenderer(this);
        surface.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        displays = getSystemService(DisplayManager.class);
        rumble = new CartridgeRumble(this);
        pauseState = new PlayerPauseState(paused -> {
            gamePaused = paused;
            inputPart.releaseInput();
            if (gameControls != null) gameControls.setEnabled(!paused);
            if (skinView != null) skinView.setEnabled(!paused);
            // As in the beta: the screen stays on while the game runs, a game
            // played on a controller included; the menu lets it sleep.
            if (paused) getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            surface.queueEvent(() -> {
                if (!disposed.get()) NativeBridge.setPaused(paused);
            });
        });
        pauseState.menu(restoreMenu);

        // Mirror the 48dp RavenEmu player toolbar: back, current game, and
        // the three-dot game menu only where no touch Menu is on screen
        // (landscape's classic controls, a skin without one).
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setBackgroundColor(theme.background);

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        View back = RavenIcon.button(this, RavenIcon.Glyph.BACK, theme.accent, 24, "Retour à la bibliothèque");
        back.setOnClickListener(view -> finish());
        toolbar.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        gameTitle = new TextView(this);
        gameTitle.setText("RavenEmu");
        gameTitle.setSingleLine(true);
        gameTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        gameTitle.setTextSize(16);
        gameTitle.setTextColor(theme.text);
        gameTitle.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.addView(gameTitle, new LinearLayout.LayoutParams(0, dp(48), 1));

        if (linkMode) {
            // The beta's link bar: the other player in place of the game menu.
            gameTitle.setSingleLine(false);
            gameTitle.setMaxLines(2);
            linkSwitch = new TextView(this);
            linkSwitch.setTextColor(theme.accent);
            linkSwitch.setTextSize(13);
            linkSwitch.setGravity(Gravity.CENTER);
            linkSwitch.setPadding(dp(10), 0, dp(12), 0);
            linkSwitch.setCompoundDrawablePadding(dp(6));
            linkSwitch.setCompoundDrawablesRelativeWithIntrinsicBounds(
                    new RavenIcon(RavenIcon.Glyph.SWAP, theme.accent, dp(18)), null, null, null);
            android.graphics.drawable.GradientDrawable outline = new android.graphics.drawable.GradientDrawable();
            outline.setCornerRadius(dp(18));
            outline.setStroke(Math.max(1, dp(1)), theme.accent);
            linkSwitch.setBackground(outline);
            linkSwitch.setOnClickListener(view -> romPart.switchLinkedPlayer());
            LinearLayout.LayoutParams switchSize = new LinearLayout.LayoutParams(-2, dp(36));
            switchSize.setMarginEnd(dp(8));
            toolbar.addView(linkSwitch, switchSize);
            romPart.showLinkTitle();
        } else {
            headerMenu = RavenIcon.button(this, RavenIcon.Glyph.MORE, theme.accent, 24, "Menu du jeu");
            headerMenu.setOnClickListener(view -> menuPart.togglePlayerMenu());
            toolbar.addView(headerMenu, new LinearLayout.LayoutParams(dp(48), dp(48)));
        }
        header.addView(toolbar);

        // As in the beta, nothing between the bar and the picture; messages
        // show in the game menu, or briefly over the game.
        editBar = new LinearLayout(this);
        editBar.setGravity(Gravity.CENTER_VERTICAL);
        editBar.setBackgroundColor(theme.surface);
        editBar.setPadding(dp(12), 0, dp(4), 0);
        TextView hint = new TextView(this);
        hint.setText("Faites glisser les boutons");
        hint.setTextColor(theme.muted);
        hint.setTextSize(12);
        editBar.addView(hint, new LinearLayout.LayoutParams(0, -2, 1));
        TextView restore = barButton("Rétablir");
        restore.setContentDescription("Remettre les boutons à leur place d'origine");
        restore.setOnClickListener(view -> gameControls.setControlLayout(ControlLayout.DEFAULT));
        editBar.addView(restore, new LinearLayout.LayoutParams(-2, dp(48)));
        TextView done = barButton("Terminé");
        done.setContentDescription("Garder cette disposition des boutons");
        done.setOnClickListener(view -> skinPart.finishMovingControls());
        editBar.addView(done, new LinearLayout.LayoutParams(-2, dp(48)));
        editBar.setVisibility(View.GONE);
        header.addView(editBar, new LinearLayout.LayoutParams(-1, dp(48)));
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));

        // Linked games have no game menu: no Menu button among the controls.
        gameControls = new GameControlsView(this, inputPart::setTouchButtons, linkMode ? null : menuPart::togglePlayerMenu);
        skinPart.updateHeaderMenu();
        gameControls.setEnabled(false);
        // The picture, and RetroAchievements' banners over it.
        FrameLayout screen = new FrameLayout(this);
        screen.addView(surface, new FrameLayout.LayoutParams(-1, -1));
        achievementOverlay = new AchievementOverlay(this, badges);
        screen.addView(achievementOverlay, new FrameLayout.LayoutParams(-1, -1));
        achievements = new PlayerAchievements(command -> runOnUiThread(() -> {
            if (!disposed.get()) command.run();
        }), event -> {
            achievementOverlay.onEvent(event);
            achievementPart.refreshAchievementsSoon();
        }, () -> {
            // GL thread, as rcheevos asks when Hardcore starts during a game.
            if (!NativeBridge.resetGame()) postStatus("Le redémarrage du jeu a échoué.");
        });
        if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
            FrameLayout stage = new FrameLayout(this);
            root.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));
            FrameLayout.LayoutParams video = new FrameLayout.LayoutParams(-1, -1);
            // The columns of the controls beside the picture, the strip of
            // Select, Start and Menu under it.
            video.leftMargin = video.rightMargin = dp(gameControls.sideWidth());
            video.bottomMargin = dp(GameControlsView.BOTTOM_STRIP);
            classicLandscapePicture = video;
            stage.addView(screen, video);
            // The whole stage, so a control can be moved beside or over the picture.
            stage.addView(gameControls, new FrameLayout.LayoutParams(-1, -1));
            // A skin's landscape representation covers the whole stage and
            // places the picture in its screen frame.
            skinView = skinPart.newSkinView(true);
            stage.addView(skinView, new FrameLayout.LayoutParams(-1, -1));
            skinStage = stage;
        } else {
            // The picture over the touch controls; a Delta skin covers both
            // and gives the picture its place.
            FrameLayout stage = new FrameLayout(this);
            LinearLayout column = new LinearLayout(this);
            column.setOrientation(LinearLayout.VERTICAL);
            column.addView(screen, new LinearLayout.LayoutParams(-1, 0, 1));
            // The controls' room below the picture; the controls themselves
            // cover the whole stage, so that one can be moved over the picture.
            column.addView(gameControls.footprint(), new LinearLayout.LayoutParams(-1, -2));
            stage.addView(column, new FrameLayout.LayoutParams(-1, -1));
            stage.addView(gameControls, new FrameLayout.LayoutParams(-1, -1));
            skinView = skinPart.newSkinView(false);
            stage.addView(skinView, new FrameLayout.LayoutParams(-1, -1));
            root.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));
            skinStage = stage;
        }
        pictureFrame = screen;
        // Reserve actual status bar, cutout, nav buttons and keyboard space.
        // Android 15 edge-to-edge would otherwise overlay the toolbar/footer.
        SystemBarInsets.install(this, root);
        setContentView(root);
        // The RavenEmu-style Java library selects a private ROM; only JNI/C
        // executes it, preserving the Java -> C -> RavenCore C++ architecture.
        String launchKey = getIntent().getStringExtra(EXTRA_ROM_KEY);
        if (linkMode && launchKey != null && CoverRepository.validKey(launchKey)) {
            submitIo(() -> {
                try {
                    RomLibrary.Entry first = null, second = null;
                    for (RomLibrary.Entry entry : RomLibrary.list(getFilesDir())) {
                        if (launchKey.equals(entry.key)) first = entry;
                        if (linkKey.equals(entry.key)) second = entry;
                    }
                    if (first == null || second == null) postStatus("Jeu absent de la bibliothèque");
                    else if (first.key.equals(second.key) || !LinkCable.compatible(first.system, second.system))
                        postStatus("Ces deux jeux ne se relient pas : Game Boy et Game Boy Color ensemble, "
                                + "ou deux Game Boy Advance.");
                    else romPart.loadLinkedFromLibrary(first, second);
                } catch (IOException error) {
                    postStatus("Chargement impossible : " + error.getMessage());
                }
            });
        } else if (launchKey != null && CoverRepository.validKey(launchKey)) {
            submitIo(() -> {
                try {
                    for (RomLibrary.Entry entry : RomLibrary.list(getFilesDir())) {
                        if (launchKey.equals(entry.key)) {
                            romPart.loadFromLibrary(entry);
                            return;
                        }
                    }
                    postStatus("Jeu absent de la bibliothèque");
                } catch (IOException error) {
                    postStatus("Chargement impossible : " + error.getMessage());
                }
            });
        }
    }

    int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private TextView barButton(String label) {
        TextView button = new TextView(this);
        button.setText(label);
        button.setGravity(Gravity.CENTER);
        button.setTextSize(14);
        button.setTextColor(theme.accent);
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setMinWidth(dp(48));
        return button;
    }

    /** GLSurfaceView runs queued events even while paused, when onPause and
     * onDestroy use the session from the UI thread. Core work that must not
     * be lost (the cheats) waits here for onResume; the rest is dropped. */
    private final List<Runnable> heldForResume = new ArrayList<>();

    /** Core work from the UI or another thread, on the GL thread while the
     * player shows; posted after the pause, it runs at the next onResume. */
    void queueCore(Runnable work) {
        surface.queueEvent(() -> {
            if (disposed.get()) return;
            if (foreground) { work.run(); return; }
            runOnUiThread(() -> {
                if (disposed.get()) return;
                if (foreground) queueCore(work);
                else heldForResume.add(work);
            });
        });
    }

    boolean submitIo(Runnable work) {
        synchronized (ioGate) {
            if (disposed.get()) return false;
            io.execute(work);
            return true;
        }
    }

    /** A problem worth telling: briefly over the game. */
    void postStatus(String message) {
        runOnUiThread(() -> {
            if (!disposed.get())
                android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show();
        });
    }

    /** The SRAM written on leaving fails after onDestroy: the player is gone,
     * the toast goes through the application so the loss is still told. */
    private void postSaveFailure(IOException error) {
        final android.content.Context app = getApplicationContext();
        final String message = "Sauvegarde impossible : " + error.getMessage();
        new Handler(Looper.getMainLooper()).post(() ->
                android.widget.Toast.makeText(app, message, android.widget.Toast.LENGTH_LONG).show());
    }

    private void persistBattery() throws IOException {
        final String[] players = linkKeys;
        if (players != null) {
            // Each player's SRAM in its own game's save, in the saves' order.
            for (int player = 0; player < 2; player++) {
                final String saveKey = players[player];
                final byte[] saved = NativeBridge.savePlayerBattery(player);
                if (saved != null) submitIo(() -> {
                    try { SaveRepository.write(getFilesDir(), saveKey, "srm", saved); }
                    catch (IOException error) { postSaveFailure(error); }
                });
            }
            return;
        }
        final String key = activeSaveKey;
        if (key == null) return;
        byte[] battery = NativeBridge.saveBattery();
        if (battery != null) submitIo(() -> {
            try { SaveRepository.write(getFilesDir(), key, "srm", battery); }
            catch (IOException error) { postSaveFailure(error); }
        });
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        NativeBridge.initGl();
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int w, int h) {
        width = Math.max(1, w);
        height = Math.max(1, h);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        long now = System.nanoTime();
        // The controller's motion sensors, if it has some, for this frame.
        int moving = motion.read(motionAcceleration, motionTurning);
        if (moving != 0 || fedMotion != 0) {
            NativeBridge.feedControllerMotion(moving, motionAcceleration[0], motionAcceleration[1],
                    motionAcceleration[2], motionTurning[0], motionTurning[1], motionTurning[2]);
            fedMotion = moving;
        }
        // Hardcore waits for the sign-in and the ROM's identification.
        int advanced = NativeBridge.drawFrame(width, height, !gamePaused && !achievements.holdFrames());
        // The frames shown reached RetroAchievements in the bridge: now its
        // answers, requests and events.
        achievements.pump();
        final boolean cheatsLock = achievements.cheatsLocked();
        if (activeSaveKey != null && cheatsLock != cheatsLockApplied) {
            // Hardcore came or went: no cheat runs in it.
            cheatsLockApplied = cheatsLock;
            NativeBridge.setCheats(cheatsLock ? new String[0] : CheatList.active(cheats));
            runOnUiThread(() -> {
                if (!disposed.get() && playerMenu != null) playerMenu.setCheats(cheats, !cheatsLock);
            });
        }
        rumble.update(rumbleEnabled && NativeBridge.rumbling(), now);
        // What the core asks to show, as RetroArch shows it: briefly over the game.
        final String message = NativeBridge.takeMessage();
        if (message != null) runOnUiThread(() -> {
            if (!disposed.get()) android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show();
        });
        if (advanced > 0 && (activeSaveKey != null || linkKeys != null) && now >= nextBatterySaveNanos) {
            nextBatterySaveNanos = now + 30_000_000_000L;
            try {
                persistBattery();
            } catch (IOException error) {
                postStatus("Sauvegarde automatique : " + error.getMessage());
            }
        }
    }

    /** The D-pad as hat axes and the left stick, on many Bluetooth controllers. */
    @Override public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (!gamePaused && event.isFromSource(InputDevice.SOURCE_JOYSTICK)
                && event.getAction() == MotionEvent.ACTION_MOVE) {
            inputPart.controllerUsed(event.getDevice());
            gamepad.axes(event.getAxisValue(MotionEvent.AXIS_HAT_X), event.getAxisValue(MotionEvent.AXIS_HAT_Y),
                    event.getAxisValue(MotionEvent.AXIS_X), event.getAxisValue(MotionEvent.AXIS_Y));
            NativeBridge.setButtons(gamepad.mask() | touchButtons);
            return true;
        }
        return super.dispatchGenericMotionEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && gameControls != null && gameControls.isEditing()) {
            skinPart.finishMovingControls();
            return true;
        }
        // The controller's Home key opens the game menu, as in the beta.
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_BUTTON_MODE ||
            (menuOpen && keyCode == KeyEvent.KEYCODE_BACK)) {
            if (event.getRepeatCount() == 0) menuPart.togglePlayerMenu();
            return true;
        }
        return inputPart.gamepadKey(keyCode, event) || super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        return inputPart.gamepadKey(keyCode, event) || super.onKeyUp(keyCode, event);
    }

    @Override protected void onResume() {
        super.onResume();
        foreground = true;
        settingsLaunching = false;
        if (surface != null) surface.onResume();
        if (!heldForResume.isEmpty()) {
            List<Runnable> held = new ArrayList<>(heldForResume);
            heldForResume.clear();
            for (Runnable work : held) queueCore(work);
        }
        if (displays != null) displays.registerDisplayListener(displayListener, new Handler(Looper.getMainLooper()));
        displayPart.sendDisplay();
        android.hardware.input.InputManager inputs = getSystemService(android.hardware.input.InputManager.class);
        if (inputs != null) inputs.registerInputDeviceListener(controllers, new Handler(Looper.getMainLooper()));
        // Settings → Interface: size, spacing, style and haptics of the touch
        // controls, and where the player moved them for this orientation.
        if (gameControls != null) {
            gameControls.setSettings(ControlSettings.load(this));
            gameControls.setControlLayout(ControlLayout.load(this, gameControls.isLandscape()));
            skinPart.fitLandscapePicture();
        }
        // Back from Settings → Interface: another skin, or none.
        skinPart.refreshSkin();
        applyPlaybackSettings();
        if (pauseState != null) pauseState.foreground(true);
        romPart.dispatchRomLoad();
        // Back from Settings → RetroAchievements: a new account applies now.
        achievementPart.refreshAchievementAccount(false);
    }

    private void applyPlaybackSettings() {
        final PlaybackSettings settings = PlaybackSettings.load(this);
        final ConsoleSettings console = ConsoleSettings.load(this);
        final CartridgeSettings cartridge = CartridgeSettings.load(this);
        final LatencySettings latency = LatencySettings.load(this);
        rumbleEnabled = cartridge.rumble;
        sensorsWanted = cartridge.sensors;
        integerScale = settings.integerScale;
        if (achievementOverlay != null) {
            achievementOverlay.setSettings(AchievementSettings.load(this), settings.audioEnabled, settings.audioVolume);
            achievementOverlay.setIntegerScale(settings.integerScale);
        }
        if (surface != null) surface.queueEvent(() -> {
            if (disposed.get() || !foreground) return;
            NativeBridge.setPlaybackOptions(settings.audioEnabled, settings.audioVolume,
                    settings.integerScale, settings.brightness, settings.contrast, settings.effectCode(),
                    settings.resamplerCode());
            NativeBridge.setPreemptiveFrames(latency.preemptiveFrames);
            // Changed in the settings during the game: the core takes them at
            // its next frame. The console and the BIOS wait for the next launch.
            if (activeCore != null)
                NativeBridge.setCoreOptions(CoreOptions.live(activeCore, settings, console, cartridge));
        });
    }

    @Override protected void onPause() {
        skinPart.finishMovingControls();
        if (achievementOverlay != null) achievementOverlay.pauseSound();
        if (displays != null) displays.unregisterDisplayListener(displayListener);
        android.hardware.input.InputManager inputs = getSystemService(android.hardware.input.InputManager.class);
        if (inputs != null) inputs.unregisterInputDeviceListener(controllers);
        // Back from the background, the next input picks the controller again.
        motion.stop();
        controllerId = -1;
        rumble.setController(null);
        foreground = false;
        if (pauseState != null) pauseState.foreground(false);
        inputPart.releaseInput();
        // Wait for the GL/emulator thread to stop before touching session data.
        // Events queued later still run on it: those that touch the core
        // check foreground, set to false above.
        if (surface != null) surface.onPause();
        rumble.stop();
        NativeBridge.setAudioEnabled(false);
        try {
            persistBattery();
        } catch (IOException error) {
            postStatus("Sauvegarde impossible : " + error.getMessage());
        }
        super.onPause();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putBoolean("player.menu", menuOpen || restoreMenu);
        state.putInt("player.slot", playerMenu != null ? playerMenu.getSelectedSlot() : selectedSlot);
        state.putInt("player.link_focus", linkFocus);
    }

    @Override protected void onDestroy() {
        inputPart.releaseInput();
        synchronized (ioGate) {
            disposed.set(true);
        }
        pendingRomLoad = null;
        undoState = null;
        statePreviews = new Bitmap[StateSlotRepository.SLOT_COUNT];
        if (playerMenu != null) playerMenu.dismiss();
        // The emulation thread was already suspended by GLSurfaceView.onPause;
        // events queued since then return at once (disposed, or not foreground).
        if (surface != null) surface.onPause();
        if (achievements != null) achievements.stop();
        if (achievementOverlay != null) achievementOverlay.release();
        badges.release();
        NativeBridge.closeRom();
        super.onDestroy();
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (pauseState != null && !disposed.get()) pauseState.focused(focused);
        if (!focused && !disposed.get()) inputPart.releaseInput();
    }
}
