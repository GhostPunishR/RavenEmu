package com.ravenemu.nativepreview;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.IOException;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/** Separate, non-distributable Java/NDK proof of concept.
 * All emulator calls run on the GL thread; button input uses an atomic mask. */
public final class MainActivity extends Activity implements GLSurfaceView.Renderer {
    private static final int PICK_ROM = 71;
    private static final int MAX_ROM = 64 * 1024 * 1024;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final AtomicBoolean disposed = new AtomicBoolean(false);
    private GLSurfaceView surface;
    private TextView status;
    private int width = 1, height = 1, buttons;
    private long nextFrameNanos;
    private long nextBatterySaveNanos;
    private volatile String activeSaveKey;
    private volatile boolean menuOpen;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        FrameLayout root = new FrameLayout(this);
        surface = new GLSurfaceView(this);
        surface.setEGLContextClientVersion(2);
        surface.setRenderer(this);
        surface.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        root.addView(surface, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(20, 12, 20, 8);
        Button open = new Button(this);
        open.setText("Importer ROM GB / GBC / GBA");
        open.setOnClickListener(view -> pickRom());
        header.addView(open);
        LinearLayout navigation = new LinearLayout(this);
        Button library = new Button(this);
        library.setText("Bibliothèque");
        library.setOnClickListener(view -> showLibrary());
        navigation.addView(library, new LinearLayout.LayoutParams(0, -2, 1));
        Button menu = new Button(this);
        menu.setText("Menu C");
        menu.setOnClickListener(view -> toggleNativeMenu());
        navigation.addView(menu, new LinearLayout.LayoutParams(0, -2, 1));
        header.addView(navigation);
        status = new TextView(this);
        status.setTextColor(0xffffffff);
        status.setShadowLayer(3f, 1f, 1f, 0xff000000);
        status.setText("Prévisualisation C / Libretro — audio AAudio + sauvegardes");
        status.setPadding(10, 6, 10, 6);
        header.addView(status);
        LinearLayout saveActions = new LinearLayout(this);
        Button quickSave = new Button(this);
        quickSave.setText("Sauver état");
        quickSave.setOnClickListener(view -> quickSave());
        Button quickLoad = new Button(this);
        quickLoad.setText("Charger état");
        quickLoad.setOnClickListener(view -> quickLoad());
        saveActions.addView(quickSave, new LinearLayout.LayoutParams(0, -2, 1));
        saveActions.addView(quickLoad, new LinearLayout.LayoutParams(0, -2, 1));
        header.addView(saveActions);
        FrameLayout.LayoutParams headerParams = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        root.addView(header, headerParams);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(8, 4, 8, 18);
        controls.addView(controlRow(
                key("◀", NativeBridge.LEFT),
                key("▲", NativeBridge.UP),
                key("▼", NativeBridge.DOWN),
                key("▶", NativeBridge.RIGHT)));
        controls.addView(controlRow(
                key("B", NativeBridge.B),
                key("A", NativeBridge.A),
                key("SELECT", NativeBridge.SELECT),
                key("START", NativeBridge.START)));
        FrameLayout.LayoutParams bottom = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        root.addView(controls, bottom);
        setContentView(root);
    }

    private LinearLayout controlRow(Button... controlButtons) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        for (Button b : controlButtons) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1);
            p.setMargins(3, 3, 3, 3);
            row.addView(b, p);
        }
        return row;
    }

    private Button key(String label, int mask) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(13);
        button.setAlpha(0.87f);
        button.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                if (menuOpen) {
                    if (mask == NativeBridge.UP) dispatchMenu(1);
                    else if (mask == NativeBridge.DOWN) dispatchMenu(2);
                    else if (mask == NativeBridge.A) dispatchMenu(3);
                } else setHeld(mask, true);
                return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_UP ||
                event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                if (!menuOpen) setHeld(mask, false);
                return true;
            }
            return true;
        });
        return button;
    }

    private void setHeld(int mask, boolean held) {
        buttons = held ? (buttons | mask) : (buttons & ~mask);
        NativeBridge.setButtons(buttons);
    }

    private void pickRom() {
        Intent request = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        request.addCategory(Intent.CATEGORY_OPENABLE);
        request.setType("*/*");
        request.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(request, PICK_ROM);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_ROM || resultCode != RESULT_OK ||
            data == null || data.getData() == null) return;
        Uri uri = data.getData();
        String name = getDisplayName(uri);
        String lower = name.toLowerCase(Locale.ROOT);
        String system;
        if (lower.endsWith(".gbc")) system = "gbc";
        else if (lower.endsWith(".gba")) system = "gba";
        else if (lower.endsWith(".gb") || lower.endsWith(".sgb")) system = "gb";
        else {
            status.setText("Extension non compatible : choisissez .gb, .gbc ou .gba");
            return;
        }
        status.setText("Import : " + name);
        // Copy user-selected bytes to private storage before starting a core.
        io.execute(() -> {
            try {
                byte[] bytes = readRom(uri);
                RomLibrary.Entry imported = RomLibrary.importRom(getFilesDir(), name, bytes);
                if (!disposed.get()) loadFromLibrary(imported);
            } catch (Exception error) {
                postStatus("Import impossible : " + error.getMessage());
            }
        });
    }

    private void showLibrary() {
        io.execute(() -> {
            try {
                List<RomLibrary.Entry> entries = RomLibrary.list(getFilesDir());
                runOnUiThread(() -> {
                    if (disposed.get()) return;
                    String[] labels = new String[entries.size()];
                    for (int i = 0; i < entries.size(); i++) labels[i] = entries.get(i).label();
                    new AlertDialog.Builder(this)
                        .setTitle(entries.isEmpty() ? "Bibliothèque vide" : "Jeux importés")
                        .setItems(labels, (dialog, index) -> loadFromLibrary(entries.get(index)))
                        .setPositiveButton("Importer", (dialog, index) -> pickRom())
                        .setNegativeButton("Fermer", null)
                        .show();
                });
            } catch (Exception error) {
                postStatus("Bibliothèque indisponible : " + error.getMessage());
            }
        });
    }

    private void loadFromLibrary(RomLibrary.Entry entry) {
        io.execute(() -> {
            try {
                byte[] rom = RomLibrary.readRom(getFilesDir(), entry);
                byte[] battery = SaveRepository.read(getFilesDir(), entry.key, "srm");
                if (disposed.get()) return;
                String nativeDir = getApplicationInfo().nativeLibraryDir;
                String dataDir = getFilesDir().getAbsolutePath();
                surface.queueEvent(() -> {
                    if (disposed.get()) return;
                    try {
                        persistBattery();
                    } catch (IOException error) {
                        postStatus("Sauvegarde précédente non écrite : " + error.getMessage());
                        return;
                    }
                    String failure = NativeBridge.openRom(nativeDir, dataDir,
                            entry.system, rom, entry.title);
                    if (failure == null && battery != null &&
                            !NativeBridge.restoreBattery(battery)) {
                        NativeBridge.closeRom();
                        failure = "SRAM incompatible : fichier original conservé";
                    }
                    if (failure == null) {
                        activeSaveKey = entry.key;
                        nextFrameNanos = nextBatterySaveNanos = 0;
                    } else activeSaveKey = null;
                    final String result = failure;
                    postStatus(result == null ? "Jeu : " + entry.label() : "Échec : " + result);
                });
            } catch (Exception error) {
                postStatus("Chargement impossible : " + error.getMessage());
            }
        });
    }

    private void toggleNativeMenu() {
        boolean opening = !menuOpen;
        menuOpen = opening;
        if (opening) {
            buttons = 0;
            NativeBridge.setButtons(0);
        }
        surface.queueEvent(() -> {
            NativeBridge.menuInput(0);
            NativeBridge.setAudioEnabled(!opening);
        });
    }

    private void dispatchMenu(int command) {
        surface.queueEvent(() -> {
            int action = NativeBridge.menuInput(command);
            if (action == 0) return;
            NativeBridge.setAudioEnabled(true);
            runOnUiThread(() -> {
                if (disposed.get()) return;
                menuOpen = false;
                if (action == 2) showLibrary();
                else if (action == 3) quickSave();
                else if (action == 4) quickLoad();
            });
        });
    }

    private String getDisplayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0))
                return cursor.getString(0);
        } catch (Exception ignored) {}
        String name = uri.getLastPathSegment();
        return name == null ? "jeu.gb" : name;
    }

    private byte[] readRom(Uri uri) throws Exception {
        try (InputStream stream = getContentResolver().openInputStream(uri);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (stream == null) throw new IllegalStateException("Document illisible");
            byte[] chunk = new byte[32 * 1024];
            int count;
            while ((count = stream.read(chunk)) != -1) {
                if (output.size() > MAX_ROM - count)
                    throw new IllegalArgumentException("ROM supérieure à 64 MiB");
                output.write(chunk, 0, count);
            }
            return output.toByteArray();
        }
    }

    private void postStatus(String message) {
        runOnUiThread(() -> {
            if (!disposed.get()) status.setText(message);
        });
    }

    private void persistBattery() throws IOException {
        if (activeSaveKey == null) return;
        byte[] battery = NativeBridge.saveBattery();
        if (battery != null)
            SaveRepository.write(getFilesDir(), activeSaveKey, "srm", battery);
    }

    private void quickSave() {
        surface.queueEvent(() -> {
            if (activeSaveKey == null) {
                postStatus("Charge un jeu avant de sauvegarder un état");
                return;
            }
            try {
                byte[] state = NativeBridge.saveState();
                if (state == null) throw new IOException("État indisponible");
                SaveRepository.write(getFilesDir(), activeSaveKey, "state", state);
                persistBattery();
                postStatus("État enregistré");
            } catch (IOException error) {
                postStatus("Sauvegarde impossible : " + error.getMessage());
            }
        });
    }

    private void quickLoad() {
        surface.queueEvent(() -> {
            if (activeSaveKey == null) {
                postStatus("Charge un jeu avant de restaurer un état");
                return;
            }
            try {
                byte[] state = SaveRepository.read(getFilesDir(), activeSaveKey, "state");
                if (state == null) throw new IOException("Aucun état enregistré");
                if (!NativeBridge.restoreState(state))
                    throw new IOException("Format de l'état incompatible");
                postStatus("État restauré");
            } catch (IOException error) {
                postStatus("Restauration impossible : " + error.getMessage());
            }
        });
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        NativeBridge.initGl();
        nextFrameNanos = 0;
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int w, int h) {
        width = Math.max(1, w);
        height = Math.max(1, h);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        long now = System.nanoTime();
        // Approx. 60fps until the full scheduler uses the core AV timing.
        boolean advance = now >= nextFrameNanos;
        if (advance) nextFrameNanos = now + 16_666_667L;
        NativeBridge.drawFrame(width, height, advance);
        if (advance && activeSaveKey != null && now >= nextBatterySaveNanos) {
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
            case KeyEvent.KEYCODE_BUTTON_START: return NativeBridge.START;
            case KeyEvent.KEYCODE_BUTTON_SELECT: return NativeBridge.SELECT;
            default: return 0;
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU ||
            (menuOpen && keyCode == KeyEvent.KEYCODE_BACK)) {
            if (event.getRepeatCount() == 0) toggleNativeMenu();
            return true;
        }
        if (menuOpen) {
            if (event.getRepeatCount() != 0) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) dispatchMenu(1);
            else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) dispatchMenu(2);
            else if (keyCode == KeyEvent.KEYCODE_BUTTON_A ||
                     keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                     keyCode == KeyEvent.KEYCODE_ENTER) dispatchMenu(3);
            return true;
        }
        int mask = keyMask(keyCode);
        if (mask != 0) {
            setHeld(mask, true);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (menuOpen) return true;
        int mask = keyMask(keyCode);
        if (mask != 0) {
            setHeld(mask, false);
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override protected void onResume() {
        super.onResume();
        if (surface != null) {
            surface.onResume();
            surface.queueEvent(() -> NativeBridge.setAudioEnabled(true));
        }
    }

    @Override protected void onPause() {
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

    @Override protected void onDestroy() {
        disposed.set(true);
        io.shutdown();
        // The emulation thread was already suspended by GLSurfaceView.onPause.
        if (surface != null) surface.onPause();
        NativeBridge.closeRom();
        super.onDestroy();
    }
}
