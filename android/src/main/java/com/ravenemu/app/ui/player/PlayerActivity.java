package com.ravenemu.app.ui.player;
import com.ravenemu.app.bridge.NativeBridge;
import com.ravenemu.app.data.library.CoverRepository;
import com.ravenemu.app.data.library.RomLibrary;
import com.ravenemu.app.data.saves.GbaBiosRepository;
import com.ravenemu.app.data.saves.SaveRepository;
import com.ravenemu.app.data.saves.StatePreview;
import com.ravenemu.app.data.saves.StateSlotRepository;
import com.ravenemu.app.platform.SystemBarInsets;
import com.ravenemu.app.settings.ConsoleSettings;
import com.ravenemu.app.settings.CoreOptions;
import com.ravenemu.app.settings.PlaybackSettings;
import com.ravenemu.app.ui.library.HomeActivity;
import com.ravenemu.app.ui.settings.PlaybackSettingsActivity;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    private static final ExecutorService STORAGE_IO = Executors.newSingleThreadExecutor();
    private final ExecutorService io = STORAGE_IO;
    private final AtomicBoolean disposed = new AtomicBoolean(false);
    private GLSurfaceView surface;
    private TextView status;
    private TextView gameTitle;
    private int width = 1, height = 1, buttons, touchButtons;
    private GameControlsView gameControls;
    private long nextBatterySaveNanos;
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
        pauseState = new PlayerPauseState(paused -> {
            gamePaused = paused;
            releaseInput();
            if (gameControls != null) gameControls.setEnabled(!paused);
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
        TextView back = new TextView(this);
        back.setText("‹");
        back.setGravity(Gravity.CENTER);
        back.setTextSize(32);
        back.setTextColor(HomeActivity.TEXT);
        back.setContentDescription("Retour à la bibliothèque");
        back.setOnClickListener(view -> finish());
        toolbar.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        gameTitle = new TextView(this);
        gameTitle.setText("RavenEmu");
        gameTitle.setSingleLine(true);
        gameTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        gameTitle.setTextSize(16);
        gameTitle.setTextColor(HomeActivity.TEXT);
        toolbar.addView(gameTitle, new LinearLayout.LayoutParams(0, dp(48), 1));

        TextView menu = new TextView(this);
        menu.setText("⋮");
        menu.setGravity(Gravity.CENTER);
        menu.setTextSize(28);
        menu.setTextColor(HomeActivity.TEXT);
        menu.setContentDescription("Menu du jeu");
        menu.setOnClickListener(view -> togglePlayerMenu());
        toolbar.addView(menu, new LinearLayout.LayoutParams(dp(48), dp(48)));
        header.addView(toolbar);

        status = new TextView(this);
        status.setTextColor(HomeActivity.MUTED);
        status.setBackgroundColor(HomeActivity.SURFACE);
        status.setTextSize(11);
        status.setText("Audio et rendu natifs · RavenCore");
        status.setPadding(dp(12), dp(4), dp(12), dp(4));
        header.addView(status, new LinearLayout.LayoutParams(-1, dp(28)));
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));

        gameControls = new GameControlsView(this, this::setTouchButtons);
        gameControls.setEnabled(false);
        if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
            FrameLayout stage = new FrameLayout(this);
            root.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));
            FrameLayout.LayoutParams video = new FrameLayout.LayoutParams(-1, -1);
            video.leftMargin = video.rightMargin = dp(116);
            video.bottomMargin = dp(44);
            stage.addView(surface, video);
            stage.addView(gameControls, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        } else {
            root.addView(surface, new LinearLayout.LayoutParams(-1, 0, 1));
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

    private void setHeld(int mask, boolean held) {
        if (gamePaused) return;
        buttons = held ? (buttons | mask) : (buttons & ~mask);
        NativeBridge.setButtons(buttons | touchButtons);
    }

    private void setTouchButtons(int mask) {
        if (disposed.get() || gamePaused) return;
        touchButtons = mask;
        NativeBridge.setButtons(buttons | touchButtons);
    }

    private void releaseInput() {
        buttons = touchButtons = 0;
        if (gameControls != null) gameControls.releaseAll();
        NativeBridge.clearButtons();
    }

    private void loadFromLibrary(RomLibrary.Entry entry) {
        submitIo(() -> {
            try {
                byte[] rom = RomLibrary.readRom(getFilesDir(), entry);
                byte[] battery = SaveRepository.read(getFilesDir(), entry.key, "srm");
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
                String[] coreOptions = CoreOptions.atLoad(core, PlaybackSettings.load(this), console, bios != null);
                String stateVariant = console.stateVariant(core, bios == null ? null : GbaBiosRepository.digest(bios));
                if (disposed.get()) return;
                String nativeDir = getApplicationInfo().nativeLibraryDir;
                String dataDir = getFilesDir().getAbsolutePath();
                queueRomLoad(() -> {
                    if (disposed.get()) return;
                    undoState = null;
                    String failure = NativeBridge.openRom(nativeDir, dataDir,
                            core, rom, entry.title, coreOptions);
                    if (failure == null && battery != null &&
                            !NativeBridge.restoreBattery(battery)) {
                        NativeBridge.closeRom();
                        failure = "SRAM incompatible : fichier original conservé";
                    }
                    if (failure == null) {
                        activeSaveKey = entry.key;
                        activeCore = core;
                        String identity = NativeBridge.stateCompatibility();
                        stateCompatibility = identity == null ? null : identity + stateVariant;
                        nextBatterySaveNanos = 0;
                    } else { activeSaveKey = null; stateCompatibility = null; activeCore = null; }
                    final String result = failure;
                    runOnUiThread(() -> {
                        if (!disposed.get() && result == null && gameTitle != null) {
                            releaseInput();
                            gameControls.setShoulders("gba".equals(entry.system));
                            gameTitle.setText(entry.title);
                            if (biosMissing) android.widget.Toast.makeText(this,
                                    "Vrai BIOS GBA absent : RavenCore utilise le sien. Choisissez son fichier "
                                            + "dans Paramètres → Console.", android.widget.Toast.LENGTH_LONG).show();
                            if (restoreMenu) { restoreMenu = false; togglePlayerMenu(); }
                        }
                    });
                    postStatus(result == null ? "Jeu : " + entry.label() : "Échec : " + result);
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
        if (playerMenu != null && playerMenu.isShowing()) {
            playerMenu.dismiss();
            return;
        }
        if (activeSaveKey == null || stateCompatibility == null) {
            postStatus("Charge un jeu avant d’ouvrir le menu");
            return;
        }
        menuOpen = true;
        pauseState.menu(true);
        releaseInput();
        if (playerMenu != null) selectedSlot = playerMenu.getSelectedSlot();
        final int generation = ++menuGeneration;
        playerMenu = new PlayerMenuSheet(this, gameTitle.getText().toString(), selectedSlot,
                new PlayerMenuSheet.Listener() {
                    @Override public void onSave(int slot) { operateSlot(slot, 0); }
                    @Override public void onLoad(int slot) { operateSlot(slot, 1); }
                    @Override public void onDelete(int slot) { operateSlot(slot, 2); }
                    @Override public void onUndo() { undoLastLoad(); }
                    @Override public void onSettings() {
                        if (!disposed.get() && foreground && !slotBusy && !settingsLaunching) {
                            settingsLaunching = true;
                            startActivity(new Intent(PlayerActivity.this, PlaybackSettingsActivity.class));
                        }
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
        playerMenu.show();
        refreshSlots();
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
            }
            status.setText(message);
        });
    }

    private boolean submitIo(Runnable work) {
        synchronized (ioGate) {
            if (disposed.get()) return false;
            io.execute(work);
            return true;
        }
    }

    private void postStatus(String message) {
        runOnUiThread(() -> {
            if (!disposed.get()) status.setText(message);
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
        int advanced = NativeBridge.drawFrame(width, height, !gamePaused);
        if (advanced > 0 && activeSaveKey != null && now >= nextBatterySaveNanos) {
            nextBatterySaveNanos = now + 30_000_000_000L;
            try {
                persistBattery();
            } catch (IOException error) {
                postStatus("Sauvegarde automatique : " + error.getMessage());
            }
        }
    }

    private int keyMask(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP: return NativeBridge.UP;
            case KeyEvent.KEYCODE_DPAD_DOWN: return NativeBridge.DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT: return NativeBridge.LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return NativeBridge.RIGHT;
            case KeyEvent.KEYCODE_BUTTON_A: return NativeBridge.A;
            case KeyEvent.KEYCODE_BUTTON_B: return NativeBridge.B;
            case KeyEvent.KEYCODE_BUTTON_L1: return NativeBridge.L;
            case KeyEvent.KEYCODE_BUTTON_R1: return NativeBridge.R;
            case KeyEvent.KEYCODE_BUTTON_START: return NativeBridge.START;
            case KeyEvent.KEYCODE_BUTTON_SELECT: return NativeBridge.SELECT;
            default: return 0;
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU ||
            (menuOpen && keyCode == KeyEvent.KEYCODE_BACK)) {
            if (event.getRepeatCount() == 0) togglePlayerMenu();
            return true;
        }
        if (gamePaused) return super.onKeyDown(keyCode, event);
        int mask = keyMask(keyCode);
        if (mask != 0) {
            setHeld(mask, true);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (gamePaused) return super.onKeyUp(keyCode, event);
        int mask = keyMask(keyCode);
        if (mask != 0) {
            setHeld(mask, false);
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override protected void onResume() {
        super.onResume();
        foreground = true;
        settingsLaunching = false;
        if (surface != null) surface.onResume();
        applyPlaybackSettings();
        if (pauseState != null) pauseState.foreground(true);
        dispatchRomLoad();
    }

    private void applyPlaybackSettings() {
        final PlaybackSettings settings = PlaybackSettings.load(this);
        final ConsoleSettings console = ConsoleSettings.load(this);
        if (surface != null) surface.queueEvent(() -> {
            if (disposed.get() || !foreground) return;
            NativeBridge.setPlaybackOptions(settings.audioEnabled, settings.audioVolume,
                    settings.integerScale, settings.brightness, settings.contrast);
            // Changed in the settings during the game: the core takes them at
            // its next frame. The console and the BIOS wait for the next launch.
            if (activeCore != null) NativeBridge.setCoreOptions(CoreOptions.live(activeCore, settings, console));
        });
    }

    @Override protected void onPause() {
        foreground = false;
        if (pauseState != null) pauseState.foreground(false);
        releaseInput();
        // Wait for the GL/emulator thread to stop before touching session data.
        if (surface != null) surface.onPause();
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
        NativeBridge.closeRom();
        super.onDestroy();
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (pauseState != null && !disposed.get()) pauseState.focused(focused);
        if (!focused && !disposed.get()) releaseInput();
    }
}
