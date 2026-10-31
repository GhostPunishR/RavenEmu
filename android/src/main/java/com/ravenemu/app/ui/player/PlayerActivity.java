package com.ravenemu.app.ui.player;
import com.ravenemu.app.bridge.NativeBridge;
import com.ravenemu.app.data.library.CoverRepository;
import com.ravenemu.app.data.library.RomLibrary;
import com.ravenemu.app.data.saves.CheatList;
import com.ravenemu.app.data.saves.CheatRepository;
import com.ravenemu.app.data.saves.GbaBiosRepository;
import com.ravenemu.app.data.saves.SaveRepository;
import com.ravenemu.app.data.saves.SaveStorage;
import com.ravenemu.app.data.saves.SaveTransfer;
import com.ravenemu.app.data.saves.StatePreview;
import com.ravenemu.app.data.saves.StateSlotRepository;
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
import com.ravenemu.app.ui.library.HomeActivity;
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
public final class PlayerActivity extends Activity implements GLSurfaceView.Renderer {
    public static final String EXTRA_ROM_KEY = "com.ravenemu.app.ROM_KEY";
    // Shared ordering: a recreated Activity must read SRAM only after the
    // previous Activity's queued pause snapshot has been written.
    /** Shared with the library's save transfers: one order for every save write. */
    private static final ExecutorService STORAGE_IO = SaveStorage.IO;
    private final ExecutorService io = STORAGE_IO;
    private final AtomicBoolean disposed = new AtomicBoolean(false);
    private GLSurfaceView surface;
    private TextView gameTitle;
    private int width = 1, height = 1, touchButtons;
    /** UI thread: a physical controller's keys and axes. */
    private final GamepadMapper gamepad = new GamepadMapper();
    private GameControlsView gameControls;
    /** Shown under the bar while the controls are moved. */
    private LinearLayout editBar;
    private boolean editingLandscape;
    /** UI thread: the open game's frame rate, for the display mode; 0 without. */
    private double gameFps;
    private DisplayManager displays;
    /** The refresh rate follows the display: 60, 90 or 120 Hz, as the system switches. */
    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int displayId) {}
        @Override public void onDisplayRemoved(int displayId) {}
        @Override public void onDisplayChanged(int displayId) {
            if (displayId == Display.DEFAULT_DISPLAY) sendDisplay();
        }
    };
    /** The cartridge's motor on the phone's vibrator, after each drawn frame. */
    private CartridgeRumble rumble;
    /** Settings → Interface: "Vibration de la cartouche". */
    private volatile boolean rumbleEnabled = CartridgeSettings.DEFAULTS.rumble;
    /** RetroAchievements over the game (UI thread), and its session (GL thread). */
    private AchievementOverlay achievementOverlay;
    private PlayerAchievements achievements;
    private final BadgeCache badges = new BadgeCache();
    /** The game whose achievements run, and the account they run with. */
    private volatile RomLibrary.Entry activeEntry;
    private volatile String activeAccount;
    private boolean achievementsRefreshQueued;
    /** UI thread: rcheevos just allowed this pause (Hardcore). */
    private boolean pauseGranted;
    private long nextBatterySaveNanos;
    /** The game's cheats: written on the UI thread only, read on the GL one. */
    private volatile List<CheatList.Cheat> cheats = Collections.emptyList();
    private volatile String cheatsKey;
    /** GL thread: the Hardcore lock the core's cheats were last set for. */
    private boolean cheatsLockApplied;
    private volatile String activeSaveKey;
    /** GL thread: the core of the open game ("gb", "gbc" or "gba"), for its options. */
    private String activeCore;
    private volatile boolean menuOpen;
    private volatile boolean gamePaused = true;
    private volatile boolean foreground;
    private volatile String stateCompatibility;
    private PlayerPauseState pauseState;
    private PlayerMenuSheet playerMenu;
    private StateSlotRepository.Slot[] stateSlots;
    private Bitmap[] statePreviews = new Bitmap[StateSlotRepository.SLOT_COUNT];
    private volatile UndoState undoState;

    private static final class UndoState {
        final String key, compatibility;
        final byte[] snapshot;
        UndoState(String key, String compatibility, byte[] snapshot) {
            this.key = key; this.compatibility = compatibility; this.snapshot = snapshot;
        }
    }

    private static final class SlotBatch {
        final StateSlotRepository.Slot[] slots;
        final Bitmap[] previews;
        SlotBatch(StateSlotRepository.Slot[] slots, Bitmap[] previews) {
            this.slots = slots; this.previews = previews;
        }
    }
    private boolean slotBusy, restoreMenu;
    private int selectedSlot;
    private final Object ioGate = new Object();
    private int menuGeneration;
    private Runnable pendingRomLoad;
    private boolean settingsLaunching;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) {
            restoreMenu = state.getBoolean("player.menu");
            selectedSlot = Math.max(0, Math.min(3, state.getInt("player.slot")));
        }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(HomeActivity.BACKGROUND);
        surface = new GLSurfaceView(this);
        surface.setEGLContextClientVersion(2);
        surface.setRenderer(this);
        surface.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        displays = getSystemService(DisplayManager.class);
        rumble = new CartridgeRumble(this);
        pauseState = new PlayerPauseState(paused -> {
            gamePaused = paused;
            releaseInput();
            if (gameControls != null) gameControls.setEnabled(!paused);
            // As in the beta: the screen stays on while the game runs, a game
            // played on a controller included; the menu lets it sleep.
            if (paused) getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            surface.queueEvent(() -> {
                if (!disposed.get()) NativeBridge.setPaused(paused);
            });
        });
        pauseState.menu(restoreMenu);

        // Mirror the 48dp RavenEmu player toolbar: back, current game,
        // and the three-dot game menu. The GL/C frontend is unchanged.
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setBackgroundColor(HomeActivity.BACKGROUND);

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        View back = RavenIcon.button(this, RavenIcon.Glyph.BACK, HomeActivity.TEXT, 24, "Retour à la bibliothèque");
        back.setOnClickListener(view -> finish());
        toolbar.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        gameTitle = new TextView(this);
        gameTitle.setText("RavenEmu");
        gameTitle.setSingleLine(true);
        gameTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        gameTitle.setTextSize(16);
        gameTitle.setTextColor(HomeActivity.TEXT);
        gameTitle.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.addView(gameTitle, new LinearLayout.LayoutParams(0, dp(48), 1));

        View menu = RavenIcon.button(this, RavenIcon.Glyph.MORE, HomeActivity.TEXT, 24, "Menu du jeu");
        menu.setOnClickListener(view -> togglePlayerMenu());
        toolbar.addView(menu, new LinearLayout.LayoutParams(dp(48), dp(48)));
        header.addView(toolbar);

        // As in the beta, nothing between the bar and the picture; messages
        // show in the game menu, or briefly over the game.
        editBar = new LinearLayout(this);
        editBar.setGravity(Gravity.CENTER_VERTICAL);
        editBar.setBackgroundColor(HomeActivity.SURFACE);
        editBar.setPadding(dp(12), 0, dp(4), 0);
        TextView hint = new TextView(this);
        hint.setText("Faites glisser les boutons");
        hint.setTextColor(HomeActivity.MUTED);
        hint.setTextSize(12);
        editBar.addView(hint, new LinearLayout.LayoutParams(0, -2, 1));
        TextView restore = barButton("Rétablir");
        restore.setContentDescription("Remettre les boutons à leur place d'origine");
        restore.setOnClickListener(view -> gameControls.setControlLayout(ControlLayout.DEFAULT));
        editBar.addView(restore, new LinearLayout.LayoutParams(-2, dp(48)));
        TextView done = barButton("Terminé");
        done.setContentDescription("Garder cette disposition des boutons");
        done.setOnClickListener(view -> finishMovingControls());
        editBar.addView(done, new LinearLayout.LayoutParams(-2, dp(48)));
        editBar.setVisibility(View.GONE);
        header.addView(editBar, new LinearLayout.LayoutParams(-1, dp(48)));
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));

        gameControls = new GameControlsView(this, this::setTouchButtons);
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
            refreshAchievementsSoon();
        }, () -> {
            // GL thread, as rcheevos asks when Hardcore starts during a game.
            if (!NativeBridge.resetGame()) postStatus("Le redémarrage du jeu a échoué.");
        });
        if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
            FrameLayout stage = new FrameLayout(this);
            root.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));
            FrameLayout.LayoutParams video = new FrameLayout.LayoutParams(-1, -1);
            video.leftMargin = video.rightMargin = dp(116);
            video.bottomMargin = dp(44);
            stage.addView(screen, video);
            // The whole stage, so a control can be moved beside or over the picture.
            stage.addView(gameControls, new FrameLayout.LayoutParams(-1, -1));
        } else {
            root.addView(screen, new LinearLayout.LayoutParams(-1, 0, 1));
            root.addView(gameControls, new LinearLayout.LayoutParams(-1, -2));
        }
        // Reserve actual status bar, cutout, nav buttons and keyboard space.
        // Android 15 edge-to-edge would otherwise overlay the toolbar/footer.
        SystemBarInsets.install(this, root);
        setContentView(root);
        // The RavenEmu-style Java library selects a private ROM; only JNI/C
        // executes it, preserving the Java -> C -> RavenCore C++ architecture.
        String launchKey = getIntent().getStringExtra(EXTRA_ROM_KEY);
        if (launchKey != null && CoverRepository.validKey(launchKey)) {
            submitIo(() -> {
                try {
                    for (RomLibrary.Entry entry : RomLibrary.list(getFilesDir())) {
                        if (launchKey.equals(entry.key)) {
                            loadFromLibrary(entry);
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

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** The display the game is on: the surface's once its window is shown,
     * the default one before. Never Activity.getDisplay(), which throws for a
     * context without a display (Robolectric's activities). */
    private Display currentDisplay() {
        Display display = surface != null ? surface.getDisplay() : null;
        if (display == null && displays != null) display = displays.getDisplay(Display.DEFAULT_DISPLAY);
        return display;
    }

    /** UI thread: the native pacer locks on the display only when it knows its
     * rate; the tilt follows the screen, a half turn included. */
    private void sendDisplay() {
        Display display = currentDisplay();
        final float hertz = display != null ? display.getRefreshRate() : 0f;
        final int rotation = display != null ? display.getRotation() : Surface.ROTATION_0;
        if (surface != null) surface.queueEvent(() -> {
            if (disposed.get()) return;
            NativeBridge.setDisplayRate(hertz);
            NativeBridge.setDisplayRotation(rotation);
        });
    }

    /** UI thread: a refresh rate the game divides, as long as it is on screen. */
    private void applyDisplayMode() {
        int mode = DisplayModes.modeFor(currentDisplay(), gameFps);
        WindowManager.LayoutParams attributes = getWindow().getAttributes();
        if (attributes.preferredDisplayModeId == mode) return;
        attributes.preferredDisplayModeId = mode;
        getWindow().setAttributes(attributes);
    }

    private TextView barButton(String label) {
        TextView button = new TextView(this);
        button.setText(label);
        button.setGravity(Gravity.CENTER);
        button.setTextSize(14);
        button.setTextColor(HomeActivity.ACCENT);
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setMinWidth(dp(48));
        return button;
    }

    /** From the game menu: the game waits while the player drags the controls. */
    private void startMovingControls() {
        if (disposed.get() || gameControls == null || gameControls.isEditing()) return;
        pauseState.editing(true);
        if (playerMenu != null) playerMenu.dismiss();
        editingLandscape = gameControls.isLandscape();
        gameControls.setEditing(true);
        editBar.setVisibility(View.VISIBLE);
    }

    /** Keeps the places for the orientation they were chosen in. */
    private void finishMovingControls() {
        if (gameControls == null || !gameControls.isEditing()) return;
        gameControls.controlLayout().save(this, editingLandscape);
        gameControls.setEditing(false);
        editBar.setVisibility(View.GONE);
        pauseState.editing(false);
    }

    private void setTouchButtons(int mask) {
        if (disposed.get() || gamePaused) return;
        touchButtons = mask;
        NativeBridge.setButtons(gamepad.mask() | touchButtons);
    }

    private void releaseInput() {
        gamepad.reset();
        touchButtons = 0;
        if (gameControls != null) gameControls.releaseAll();
        NativeBridge.clearButtons();
    }

    private void loadFromLibrary(RomLibrary.Entry entry) {
        submitIo(() -> {
            try {
                byte[] rom = RomLibrary.readRom(getFilesDir(), entry);
                byte[] battery = SaveRepository.read(getFilesDir(), entry.key, "srm");
                List<CheatList.Cheat> stored;
                try {
                    stored = CheatRepository.read(getFilesDir(), entry.key);
                } catch (IOException | RuntimeException unreadable) {
                    stored = Collections.emptyList();
                    postStatus("Codes de triche illisibles : " + unreadable.getMessage());
                }
                final List<CheatList.Cheat> storedCheats = stored;
                // Settings → Console picks the core; it and the image options
                // apply as the game loads. The SRAM stays the game's own.
                ConsoleSettings console = ConsoleSettings.load(this);
                String core = console.coreFor(entry.system);
                byte[] bios = null;
                if ("gba".equals(core) && console.gbaBios != ConsoleSettings.BIOS_RAVENCORE) {
                    try { bios = GbaBiosRepository.read(getFilesDir()); }
                    catch (IOException unreadable) { bios = null; }
                }
                final boolean biosMissing = "gba".equals(core)
                        && console.gbaBios != ConsoleSettings.BIOS_RAVENCORE && bios == null;
                String[] coreOptions = CoreOptions.atLoad(core, PlaybackSettings.load(this), console,
                        CartridgeSettings.load(this), bios != null);
                String stateVariant = console.stateVariant(core, bios == null ? null : GbaBiosRepository.digest(bios));
                // Signed in from Settings → RetroAchievements: the game's
                // achievements follow it. Without an account, no network.
                final AchievementAccount account = AchievementAccount.load(this);
                final AchievementsHttp http = account == null ? null : new AchievementsHttp(RetroUserAgent.of(this));
                final AwardJournal journal = account == null ? null : AwardJournal.open(this);
                final boolean hardcore = AchievementSettings.load(this).hardcore;
                if (disposed.get()) return;
                String nativeDir = getApplicationInfo().nativeLibraryDir;
                String dataDir = getFilesDir().getAbsolutePath();
                queueRomLoad(() -> {
                    if (disposed.get()) return;
                    undoState = null;
                    String failure = NativeBridge.openRom(nativeDir, dataDir,
                            core, rom, entry.title, coreOptions);
                    String notice = null;
                    if (failure == null && battery != null && !NativeBridge.restoreBattery(battery)) {
                        // A file of another size, from another emulator or an
                        // import: read as RavenCore reads one, its first bytes
                        // over the core's fresh SRAM (SaveTransfer.fit).
                        byte[] fitted = SaveTransfer.fit(battery, NativeBridge.saveBattery());
                        if (fitted == null) {
                            notice = "Ce jeu n'a pas de mémoire de sauvegarde : fichier conservé, non chargé";
                        } else if (fitted != battery && NativeBridge.restoreBattery(fitted)) {
                            notice = "Sauvegarde de " + battery.length + " octets lue sur les "
                                    + fitted.length + " octets du jeu";
                        } else {
                            achievements.stop();
                            NativeBridge.closeRom();
                            failure = "SRAM incompatible : fichier original conservé";
                        }
                    }
                    if (failure == null && notice != null) postStatus(notice);
                    // Once the SRAM is in: the ROM is identified by its hash.
                    if (failure == null) achievements.start(http, account, journal, entry.system, rom, hardcore);
                    else achievements.stop();
                    if (failure == null) {
                        // The game's cheats, none while Hardcore is asked for or runs.
                        cheatsLockApplied = achievements.cheatsLocked();
                        if (NativeBridge.setCheats(cheatsLockApplied ? new String[0]
                                : CheatList.active(storedCheats)) >= 0) {
                            String reason = NativeBridge.cheatRefusal();
                            if (reason != null) postStatus(reason);
                        }
                        final boolean locked = cheatsLockApplied;
                        runOnUiThread(() -> {
                            if (disposed.get()) return;
                            cheats = storedCheats;
                            cheatsKey = entry.key;
                            if (playerMenu != null) playerMenu.setCheats(storedCheats, !locked);
                        });
                    } else {
                        runOnUiThread(() -> { cheats = Collections.emptyList(); cheatsKey = null; });
                    }
                    activeEntry = failure == null ? entry : null;
                    activeAccount = failure == null ? accountKey(account, hardcore) : null;
                    final double fps = failure == null ? NativeBridge.frameRate() : 0.0;
                    if (failure == null) {
                        activeSaveKey = entry.key;
                        activeCore = core;
                        String identity = NativeBridge.stateCompatibility();
                        stateCompatibility = identity == null ? null : identity + stateVariant;
                        nextBatterySaveNanos = 0;
                    } else { activeSaveKey = null; stateCompatibility = null; activeCore = null; }
                    final String result = failure;
                    runOnUiThread(() -> {
                        if (!disposed.get()) {
                            achievementOverlay.clear();
                            badges.setHttp(http);
                        }
                        if (!disposed.get() && result == null && gameTitle != null) {
                            gameFps = fps;
                            applyDisplayMode();
                            releaseInput();
                            gameControls.setShoulders("gba".equals(entry.system));
                            gameTitle.setText(entry.title);
                            if (biosMissing) android.widget.Toast.makeText(this,
                                    "Vrai BIOS GBA absent : RavenCore utilise le sien. Choisissez son fichier "
                                            + "dans Paramètres → Console.", android.widget.Toast.LENGTH_LONG).show();
                            if (restoreMenu) { restoreMenu = false; togglePlayerMenu(); }
                        }
                    });
                    if (result != null) postStatus("Échec : " + result);
                });
            } catch (Exception error) {
                postStatus("Chargement impossible : " + error.getMessage());
            }
        });
    }

    private void queueRomLoad(Runnable load) {
        runOnUiThread(() -> {
            if (disposed.get()) return;
            pendingRomLoad = load;
            dispatchRomLoad();
        });
    }

    /** A storage callback may arrive after GLSurfaceView acknowledged pause.
     * Defer opening a core so it cannot race the paused-session cleanup. */
    private void dispatchRomLoad() {
        if (disposed.get() || !foreground || pendingRomLoad == null) return;
        Runnable load = pendingRomLoad;
        pendingRomLoad = null;
        surface.queueEvent(() -> {
            if (disposed.get()) return;
            if (!foreground) { queueRomLoad(load); return; }
            load.run();
        });
    }

    private void togglePlayerMenu() {
        finishMovingControls();
        if (playerMenu != null && playerMenu.isShowing()) {
            playerMenu.dismiss();
            return;
        }
        if (activeSaveKey == null || stateCompatibility == null) {
            postStatus("Charge un jeu avant d’ouvrir le menu");
            return;
        }
        // The menu pauses the game: in Hardcore, rcheevos says whether it may now.
        if (achievements.hardcoreActive() && !pauseGranted) {
            surface.queueEvent(() -> {
                if (disposed.get()) return;
                final boolean allowed = achievements.canPause();
                runOnUiThread(() -> {
                    if (disposed.get()) return;
                    if (!allowed) {
                        android.widget.Toast.makeText(this, HardcorePolicy.PAUSE_REFUSED,
                                android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    pauseGranted = true;
                    try { togglePlayerMenu(); } finally { pauseGranted = false; }
                });
            });
            return;
        }
        menuOpen = true;
        pauseState.menu(true);
        releaseInput();
        if (playerMenu != null) selectedSlot = playerMenu.getSelectedSlot();
        final int generation = ++menuGeneration;
        playerMenu = new PlayerMenuSheet(this, gameTitle.getText().toString(), selectedSlot, badges,
                new PlayerMenuSheet.Listener() {
                    @Override public void onSave(int slot) { operateSlot(slot, 0); }
                    @Override public void onLoad(int slot) { operateSlot(slot, 1); }
                    @Override public void onDelete(int slot) { operateSlot(slot, 2); }
                    @Override public void onUndo() { undoLastLoad(); }
                    @Override public void onMoveControls() { startMovingControls(); }
                    @Override public void onSettings() {
                        if (!disposed.get() && foreground && !slotBusy && !settingsLaunching) {
                            settingsLaunching = true;
                            startActivity(new Intent(PlayerActivity.this, PlaybackSettingsActivity.class));
                        }
                    }
                    @Override public void onAchievementsOpened() { refreshAchievements(); }
                    @Override public void onAchievementsSignIn() {
                        if (!disposed.get() && foreground && !slotBusy && !settingsLaunching) {
                            settingsLaunching = true;
                            startActivity(new Intent(PlayerActivity.this, PlaybackSettingsActivity.class)
                                    .putExtra(PlaybackSettingsActivity.EXTRA_PAGE, PlaybackSettingsActivity.RETRO));
                        }
                    }
                    @Override public void onAchievementsRetry() { restartAchievements(); }
                    @Override public void onAchievementsSignOut() { signOutOfAchievements(); }
                    @Override public void onFetchScores(int board, boolean around) {
                        if (disposed.get()) return;
                        surface.queueEvent(() -> {
                            if (!disposed.get()) achievements.fetchScores(board, around);
                        });
                        refreshAchievements();
                    }
                    @Override public void onOpenLink(String url) {
                        try {
                            startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)));
                        } catch (android.content.ActivityNotFoundException missing) {
                            postStatus("Aucune application pour ouvrir ce lien");
                        }
                    }
                    @Override public void onCheatAdd(String name, String code) { addCheat(name, code); }
                    @Override public void onCheatToggle(int index) {
                        List<CheatList.Cheat> list = new ArrayList<>(cheats);
                        if (index < 0 || index >= list.size() || achievements.cheatsLocked()) return;
                        list.set(index, list.get(index).withEnabled(!list.get(index).enabled));
                        changeCheats(list);
                    }
                    @Override public void onCheatDelete(int index) {
                        List<CheatList.Cheat> list = new ArrayList<>(cheats);
                        if (index < 0 || index >= list.size()) return;
                        list.remove(index);
                        changeCheats(list);
                    }
                    @Override public void onDismiss() {
                        if (generation != menuGeneration) return;
                        selectedSlot = playerMenu.getSelectedSlot();
                        menuOpen = false;
                        if (!disposed.get()) pauseState.menu(false);
                    }
                });
        stateSlots = null;
        statePreviews = new Bitmap[StateSlotRepository.SLOT_COUNT];
        renderPlayerMenu();
        playerMenu.setCheats(cheats, !achievements.cheatsLocked());
        playerMenu.show();
        refreshSlots();
        refreshAchievements();
    }

    /** The beta's GbCheatsUi.add: a code the core takes is kept, on; a refused
     * one stays in the form with the core's reason. UI thread. */
    private void addCheat(String name, String code) {
        final PlayerMenuSheet menu = playerMenu;
        if (menu == null || disposed.get()) return;
        final String line = CheatList.oneLine(code);
        final String key = cheatsKey;
        if (line.isEmpty()) { menu.cheatRefused("Saisissez un code."); return; }
        if (key == null) { menu.cheatRefused("Codes de triche pas encore prêts."); return; }
        if (achievements.cheatsLocked()) { menu.cheatRefused(CheatsPanel.HARDCORE); return; }
        final List<CheatList.Cheat> before = cheats;
        final CheatList.Cheat added = new CheatList.Cheat(
                name == null || name.trim().isEmpty() ? "Code " + (before.size() + 1) : name.trim(), line, true);
        List<CheatList.Cheat> candidate = new ArrayList<>(before);
        candidate.add(added);
        final String[] codes = CheatList.active(candidate);
        surface.queueEvent(() -> {
            if (disposed.get() || !key.equals(activeSaveKey)) return;
            final int refused = NativeBridge.setCheats(codes);
            final String reason = NativeBridge.cheatRefusal();
            runOnUiThread(() -> {
                if (disposed.get() || !key.equals(cheatsKey)) return;
                if (refused == -2) {
                    menu.cheatRefused("Codes de triche indisponibles pour ce jeu.");
                } else if (refused == codes.length - 1) {
                    // The core keeps the others as they were.
                    menu.cheatRefused(reason == null ? "Code refusé par le core." : reason);
                } else {
                    List<CheatList.Cheat> now = new ArrayList<>(cheats);
                    now.add(added);
                    changeCheats(now);
                    menu.cheatAdded();
                }
            });
        });
    }

    /** A new list: shown at once, then given to the core and written whole. */
    private void changeCheats(List<CheatList.Cheat> list) {
        final String key = cheatsKey;
        if (key == null || disposed.get()) return;
        final List<CheatList.Cheat> kept = Collections.unmodifiableList(new ArrayList<>(list));
        cheats = kept;
        if (playerMenu != null) playerMenu.setCheats(kept, !achievements.cheatsLocked());
        surface.queueEvent(() -> {
            if (disposed.get() || !key.equals(activeSaveKey) || cheatsLockApplied) return;
            NativeBridge.setCheats(CheatList.active(kept));
            String reason = NativeBridge.cheatRefusal();
            if (reason != null) postStatus(reason);
        });
        submitIo(() -> {
            try { CheatRepository.write(getFilesDir(), key, kept); }
            catch (IOException | RuntimeException failure) {
                postStatus("Codes de triche non enregistrés : " + failure.getMessage());
            }
        });
    }

    /** The menu's RetroAchievements: a snapshot on the GL thread, the
     * unlocks still waiting counted from the journal off it. */
    private void refreshAchievements() {
        final PlayerMenuSheet menu = playerMenu;
        if (disposed.get() || menu == null || !menu.isShowing() || surface == null) return;
        surface.queueEvent(() -> {
            if (disposed.get()) return;
            final AchievementsSnapshot snapshot = achievements.snapshot();
            final AwardJournal journal = achievements.journal();
            submitIo(() -> {
                int pending = journal == null || !snapshot.signedIn ? 0 : journal.forAccount(snapshot.user).size();
                runOnUiThread(() -> {
                    if (!disposed.get() && playerMenu == menu && menu.isShowing())
                        menu.setAchievements(snapshot.withPending(pending));
                });
            });
        });
    }

    /** Events come in bursts (progress, scores): one refresh for them. */
    private void refreshAchievementsSoon() {
        if (achievementsRefreshQueued || playerMenu == null || !playerMenu.isShowing()) return;
        achievementsRefreshQueued = true;
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            achievementsRefreshQueued = false;
            refreshAchievements();
        }, 250);
    }

    /** The account and the mode a game's achievements run with. */
    private static String accountKey(AchievementAccount account, boolean hardcoreRequested) {
        return account == null ? null
                : account.user + "\n" + account.token + "\n" + HardcorePolicy.enabled(hardcoreRequested);
    }

    /** The stored account now, for the game running: signed in, out, or
     * another one in Settings → RetroAchievements, as the beta applied it at
     * once. "Réessayer" asks for the same with the same account. */
    private void restartAchievements() { refreshAchievementAccount(true); }

    private void refreshAchievementAccount(boolean force) {
        final RomLibrary.Entry entry = activeEntry;
        if (entry == null || disposed.get()) return;
        submitIo(() -> {
            final AchievementAccount account = AchievementAccount.load(this);
            final boolean hardcore = AchievementSettings.load(this).hardcore;
            final String key = accountKey(account, hardcore);
            final String previous = activeAccount;
            if (!force && java.util.Objects.equals(key, previous)) return;
            // Hardcore turned on during the game: it starts over, as in the beta.
            final boolean restartGame = account != null && HardcorePolicy.enabled(hardcore)
                    && (previous == null || !previous.endsWith("\ntrue"));
            byte[] rom = null;
            if (account != null) {
                try { rom = RomLibrary.readRom(getFilesDir(), entry); }
                catch (IOException unreadable) { postStatus("RetroAchievements : jeu illisible"); return; }
            }
            final byte[] game = rom;
            final AchievementsHttp http = account == null ? null : new AchievementsHttp(RetroUserAgent.of(this));
            final AwardJournal journal = account == null ? null : AwardJournal.open(this);
            if (disposed.get()) return;
            surface.queueEvent(() -> {
                if (disposed.get() || activeEntry != entry) return;
                if (restartGame) NativeBridge.resetGame();
                achievements.start(http, account, journal, entry.system, game, hardcore);
                activeAccount = key;
                runOnUiThread(() -> {
                    if (disposed.get()) return;
                    if (http != null) badges.setHttp(http);
                    achievementOverlay.clear();
                    refreshAchievements();
                });
            });
        });
    }

    /** From the game menu, as in the beta: the token and the unlocks still
     * waiting are forgotten on this device. */
    private void signOutOfAchievements() {
        submitIo(() -> {
            AwardJournal.clear(this);
            AchievementAccount.clear(this);
            surface.queueEvent(() -> {
                if (disposed.get()) return;
                achievements.stop();
                activeAccount = null;
                runOnUiThread(() -> {
                    if (disposed.get()) return;
                    if (playerMenu != null && playerMenu.isShowing()) playerMenu.showMenu();
                    refreshAchievements();
                });
            });
        });
    }

    private void refreshSlots() {
        final String key = activeSaveKey, compatibility = stateCompatibility;
        if (key == null || compatibility == null) return;
        submitIo(() -> {
            try {
                SlotBatch loaded = readSlotBatch(key, compatibility);
                runOnUiThread(() -> {
                    if (disposed.get() || !key.equals(activeSaveKey)) return;
                    stateSlots = loaded.slots;
                    statePreviews = loaded.previews;
                    renderPlayerMenu();
                });
            } catch (IOException error) { finishSlotOperation("Emplacements indisponibles : " + error.getMessage(), null); }
        });
    }

    /** Capture/restore on GL, disk work on the single storage executor. */
    private void operateSlot(int slot, int action) {
        if (disposed.get() || slotBusy || stateSlots == null || activeSaveKey == null ||
                stateCompatibility == null || slot < 0 || slot >= StateSlotRepository.SLOT_COUNT) return;
        if (action == 1 && refuseStateLoad()) return;
        final String key = activeSaveKey, compatibility = stateCompatibility;
        final PlayerMenuSheet requestingMenu = playerMenu;
        final int requestingGeneration = menuGeneration;
        selectedSlot = slot;
        slotBusy = true;
        pauseState.operation(true);
        renderPlayerMenu();
        if (action == 0) {
            surface.queueEvent(() -> {
                if (!slotSessionAvailable(key, compatibility)) return;
                byte[] snapshot = NativeBridge.saveState();
                byte[] battery = NativeBridge.saveBattery();
                int[] preview = NativeBridge.capturePreview();
                if (snapshot == null) { finishSlotOperation("État indisponible", null); return; }
                submitIo(() -> {
                    try {
                        byte[] thumbnail = null;
                        String message = "Emplacement " + (slot + 1) + " enregistré";
                        try { thumbnail = StatePreview.encode(preview); }
                        catch (IOException error) { message += " · aperçu indisponible"; }
                        StateSlotRepository.write(getFilesDir(), key, slot, compatibility, snapshot, thumbnail);
                        try { SaveRepository.write(getFilesDir(), key, "srm", battery); }
                        catch (IOException error) { message += " · SRAM non écrite : " + error.getMessage(); }
                        finishSlotOperation(message,
                                readSlotBatch(key, compatibility));
                    } catch (IOException error) { finishSlotOperation("Sauvegarde impossible : " + error.getMessage(), null); }
                });
            });
        } else submitIo(() -> {
            try {
                if (action == 2) {
                    StateSlotRepository.delete(getFilesDir(), key, slot);
                    finishSlotOperation("Emplacement " + (slot + 1) + " supprimé",
                            readSlotBatch(key, compatibility));
                } else {
                    byte[] snapshot = StateSlotRepository.read(getFilesDir(), key, slot, compatibility);
                    if (snapshot == null) throw new IOException("Emplacement vide");
                    surface.queueEvent(() -> applyState(key, compatibility, snapshot, false,
                            "Emplacement " + (slot + 1) + " chargé", requestingMenu, requestingGeneration));
                }
            } catch (IOException error) { finishSlotOperation("Opération impossible : " + error.getMessage(), null); }
        });
    }

    private SlotBatch readSlotBatch(String key, String compatibility) throws IOException {
        StateSlotRepository.Slot[] slots = StateSlotRepository.list(getFilesDir(), key, compatibility);
        Bitmap[] previews = new Bitmap[StateSlotRepository.SLOT_COUNT];
        for (StateSlotRepository.Slot slot : slots) {
            previews[slot.index] = StatePreview.decode(slot.thumbnail);
        }
        return new SlotBatch(slots, previews);
    }

    /** Hardcore: no save state is loaded, the undo of a load included. */
    private boolean refuseStateLoad() {
        if (!achievements.stateLoadLocked()) return false;
        renderPlayerMenu();
        if (playerMenu != null && playerMenu.isShowing()) playerMenu.showMessage(HardcorePolicy.STATE_LOAD_LOCKED);
        return true;
    }

    private boolean canUndo() {
        UndoState undo = undoState;
        return undo != null && undo.key.equals(activeSaveKey) &&
                undo.compatibility.equals(stateCompatibility);
    }

    private void renderPlayerMenu() {
        if (playerMenu == null) return;
        playerMenu.render(stateSlots, slotBusy);
        playerMenu.setPreviews(statePreviews);
        playerMenu.setUndoAvailable(canUndo());
    }

    private void undoLastLoad() {
        if (disposed.get() || slotBusy || !canUndo()) { renderPlayerMenu(); return; }
        if (refuseStateLoad()) return;
        UndoState undo = undoState;
        final PlayerMenuSheet requestingMenu = playerMenu;
        final int generation = menuGeneration;
        slotBusy = true;
        pauseState.operation(true);
        renderPlayerMenu();
        surface.queueEvent(() -> applyState(undo.key, undo.compatibility, undo.snapshot,
                true, "Chargement annulé", requestingMenu, generation));
    }

    /** Both load and undo keep a rollback snapshot until the core accepts
     * the requested state. Only a successful load replaces the undo point. */
    private void applyState(String key, String compatibility, byte[] snapshot,
                            boolean undoing, String message,
                            PlayerMenuSheet requestingMenu, int generation) {
        if (!slotSessionAvailable(key, compatibility)) return;
        byte[] previous = NativeBridge.saveState();
        if (previous == null) {
            finishSlotOperation((undoing ? "Annulation impossible" : "Chargement annulé")
                    + " : état courant non récupérable", null);
            return;
        }
        boolean restored = NativeBridge.restoreState(snapshot);
        if (!restored && !NativeBridge.restoreState(previous)) {
            achievements.stop();
            NativeBridge.closeRom();
            undoState = null;
            activeSaveKey = stateCompatibility = null;
            activeCore = null;
            finishSlotOperation("Jeu arrêté après échec de restauration ; SRAM conservée", null);
            runOnUiThread(() -> {
                if (disposed.get()) return;
                if (playerMenu != null) playerMenu.dismiss();
                android.widget.Toast.makeText(this,
                        "Jeu arrêté après échec de restauration ; SRAM conservée",
                        android.widget.Toast.LENGTH_LONG).show();
                finish();
            });
            return;
        }
        if (restored) undoState = undoing ? null : new UndoState(key, compatibility, previous);
        finishSlotOperation(restored ? message : undoing
                ? "Annulation impossible : partie courante conservée"
                : "État incompatible : fichier conservé", null);
        if (restored) runOnUiThread(() -> {
            if (!disposed.get() && playerMenu == requestingMenu &&
                    menuGeneration == generation) requestingMenu.dismiss();
        });
    }

    private boolean slotSessionAvailable(String key, String compatibility) {
        if (disposed.get() || !foreground || !key.equals(activeSaveKey) ||
                !compatibility.equals(stateCompatibility)) {
            finishSlotOperation("Opération interrompue : jeu en arrière-plan ou fermé", null);
            return false;
        }
        return true;
    }

    private void finishSlotOperation(String message, SlotBatch updated) {
        runOnUiThread(() -> {
            if (disposed.get()) return;
            slotBusy = false;
            pauseState.operation(false);
            if (updated != null) { stateSlots = updated.slots; statePreviews = updated.previews; }
            if (playerMenu != null && playerMenu.isShowing()) {
                renderPlayerMenu();
                playerMenu.showMessage(message);
            } else {
                android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show();
            }
        });
    }

    private boolean submitIo(Runnable work) {
        synchronized (ioGate) {
            if (disposed.get()) return false;
            io.execute(work);
            return true;
        }
    }

    /** A problem worth telling: briefly over the game. */
    private void postStatus(String message) {
        runOnUiThread(() -> {
            if (!disposed.get())
                android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show();
        });
    }

    private void persistBattery() throws IOException {
        final String key = activeSaveKey;
        if (key == null) return;
        byte[] battery = NativeBridge.saveBattery();
        if (battery != null) submitIo(() -> {
            try { SaveRepository.write(getFilesDir(), key, "srm", battery); }
            catch (IOException error) { postStatus("Sauvegarde impossible : " + error.getMessage()); }
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
        if (advanced > 0 && activeSaveKey != null && now >= nextBatterySaveNanos) {
            nextBatterySaveNanos = now + 30_000_000_000L;
            try {
                persistBattery();
            } catch (IOException error) {
                postStatus("Sauvegarde automatique : " + error.getMessage());
            }
        }
    }

    /** A controller key, while the game runs: the buttons it holds reach the core. */
    private boolean gamepadKey(int keyCode, KeyEvent event) {
        if (gamePaused || !gamepad.handles(keyCode)) return false;
        if (gamepad.key(keyCode, event.getAction(), event.getRepeatCount()))
            NativeBridge.setButtons(gamepad.mask() | touchButtons);
        return true;
    }

    /** The D-pad as hat axes and the left stick, on many Bluetooth controllers. */
    @Override public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (!gamePaused && event.isFromSource(InputDevice.SOURCE_JOYSTICK)
                && event.getAction() == MotionEvent.ACTION_MOVE) {
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
            finishMovingControls();
            return true;
        }
        // The controller's Home key opens the game menu, as in the beta.
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_BUTTON_MODE ||
            (menuOpen && keyCode == KeyEvent.KEYCODE_BACK)) {
            if (event.getRepeatCount() == 0) togglePlayerMenu();
            return true;
        }
        return gamepadKey(keyCode, event) || super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        return gamepadKey(keyCode, event) || super.onKeyUp(keyCode, event);
    }

    @Override protected void onResume() {
        super.onResume();
        foreground = true;
        settingsLaunching = false;
        if (surface != null) surface.onResume();
        if (displays != null) displays.registerDisplayListener(displayListener, new Handler(Looper.getMainLooper()));
        sendDisplay();
        // Settings → Interface: size, spacing, style and haptics of the touch
        // controls, and where the player moved them for this orientation.
        if (gameControls != null) {
            gameControls.setSettings(ControlSettings.load(this));
            gameControls.setControlLayout(ControlLayout.load(this, gameControls.isLandscape()));
        }
        applyPlaybackSettings();
        if (pauseState != null) pauseState.foreground(true);
        dispatchRomLoad();
        // Back from Settings → RetroAchievements: a new account applies now.
        refreshAchievementAccount(false);
    }

    private void applyPlaybackSettings() {
        final PlaybackSettings settings = PlaybackSettings.load(this);
        final ConsoleSettings console = ConsoleSettings.load(this);
        final CartridgeSettings cartridge = CartridgeSettings.load(this);
        final LatencySettings latency = LatencySettings.load(this);
        rumbleEnabled = cartridge.rumble;
        if (achievementOverlay != null)
            achievementOverlay.setSettings(AchievementSettings.load(this), settings.audioEnabled, settings.audioVolume);
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
        finishMovingControls();
        if (displays != null) displays.unregisterDisplayListener(displayListener);
        foreground = false;
        if (pauseState != null) pauseState.foreground(false);
        releaseInput();
        // Wait for the GL/emulator thread to stop before touching session data.
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
    }

    @Override protected void onDestroy() {
        releaseInput();
        synchronized (ioGate) {
            disposed.set(true);
        }
        pendingRomLoad = null;
        undoState = null;
        statePreviews = new Bitmap[StateSlotRepository.SLOT_COUNT];
        if (playerMenu != null) playerMenu.dismiss();
        // The emulation thread was already suspended by GLSurfaceView.onPause.
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
        if (!focused && !disposed.get()) releaseInput();
    }
}
