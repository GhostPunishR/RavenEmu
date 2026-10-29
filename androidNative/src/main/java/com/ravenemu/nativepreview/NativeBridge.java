package com.ravenemu.nativepreview;

/** JNI API of the experimental C frontend. setButtons and clearButtons are
 * atomic UI inputs; core calls are serialized with the GL/emulation thread,
 * including lifecycle cleanup after the GL thread has paused. */
final class NativeBridge {
    static {
        System.loadLibrary("raven_android");
    }

    // These bit indices match Libretro RETRO_DEVICE_ID_JOYPAD_*.
    static final int B = 1 << 0;
    static final int SELECT = 1 << 2;
    static final int START = 1 << 3;
    static final int UP = 1 << 4;
    static final int DOWN = 1 << 5;
    static final int LEFT = 1 << 6;
    static final int RIGHT = 1 << 7;
    static final int A = 1 << 8;
    static final int L = 1 << 10;
    static final int R = 1 << 11;

    private NativeBridge() {}

    static native void initGl();

    /** Return null on success, otherwise an error suitable for display. */
    static native String openRom(String nativeLibraryDirectory, String filesDirectory,
                                 String system, byte[] data, String filename);

    static native void drawFrame(int surfaceWidth, int surfaceHeight, boolean advance);
    /** Current combined joypad mask. Brief presses survive until one core frame. */
    static native void setButtons(int buttons);
    /** Pause/menu/focus loss: release held buttons and discard pending presses. */
    static native void clearButtons();
    static native byte[] saveBattery();
    static native boolean restoreBattery(byte[] battery);
    static native byte[] saveState();
    static native boolean restoreState(byte[] state);
    static native void setAudioEnabled(boolean enabled);
    /** C-rendered menu: 0 toggle, 1 up, 2 down, 3 select, 4 close.
     * Results: 0 nothing, 1 resume, 2 library, 3 save, 4 load. EGL thread. */
    static native int menuInput(int command);
    static native void closeRom();
}
