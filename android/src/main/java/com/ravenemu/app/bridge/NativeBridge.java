package com.ravenemu.app.bridge;


/** JNI API of the experimental C frontend. setButtons and clearButtons are
 * atomic UI inputs; core calls are serialized with the GL/emulation thread,
 * including lifecycle cleanup after the GL thread has paused. */
public final class NativeBridge {
    static {
        System.loadLibrary("raven_android");
    }

    // These bit indices match Libretro RETRO_DEVICE_ID_JOYPAD_*.
    public static final int B = 1 << 0;
    public static final int SELECT = 1 << 2;
    public static final int START = 1 << 3;
    public static final int UP = 1 << 4;
    public static final int DOWN = 1 << 5;
    public static final int LEFT = 1 << 6;
    public static final int RIGHT = 1 << 7;
    public static final int A = 1 << 8;
    public static final int L = 1 << 10;
    public static final int R = 1 << 11;

    private NativeBridge() {}

    public static native void initGl();

    /** Return null on success, otherwise an error suitable for display.
     * coreOptions: alternating Libretro option keys and values, applied before
     * the game loads; those the core does not offer are skipped. */
    public static native String openRom(String nativeLibraryDirectory, String filesDirectory,
                                 String system, byte[] data, String filename, String[] coreOptions);
    /** GL thread: change core options during the game (alternating keys and
     * values); the core takes them at its next frame. */
    public static native void setCoreOptions(String[] coreOptions);

    /** GL thread: native monotonic pacing at the core's FPS, bounded catch-up.
     * Returns the number of core frames actually executed (0..3). */
    public static native int drawFrame(int surfaceWidth, int surfaceHeight, boolean allowAdvance);
    /** Current combined joypad mask. Brief presses survive until one core frame. */
    public static native void setButtons(int buttons);
    /** Pause/menu/focus loss: release held buttons and discard pending presses. */
    public static native void clearButtons();
    /** GL thread: pause emulation and audio together, without drawing a menu. */
    public static native void setPaused(boolean paused);
    /** GL owner: output preferences, independent of the lifecycle pause gate. */
    public static native void setPlaybackOptions(boolean audioEnabled, int audioVolume,
                                          boolean integerScale, int brightness, int contrast);
    /** Pinned core revision + Libretro core identity, for private state slots. */
    public static native String stateCompatibility();
    public static native byte[] saveBattery();
    public static native boolean restoreBattery(byte[] battery);
    public static native byte[] saveState();
    /** GL thread: bounded width/height/ARGB copy, or null without a fresh frame. */
    public static native int[] capturePreview();
    public static native boolean restoreState(byte[] state);
    public static native void setAudioEnabled(boolean enabled);
    public static native void closeRom();
}
