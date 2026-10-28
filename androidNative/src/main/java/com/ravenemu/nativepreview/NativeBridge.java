package com.ravenemu.nativepreview;

/** JNI API of the experimental C frontend. All methods except setButtons
 * are called on the GLSurfaceView rendering thread, in serialized order. */
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

    private NativeBridge() {}

    static native void initGl();

    /** Return null on success, otherwise an error suitable for display. */
    static native String openRom(String nativeLibraryDirectory, String filesDirectory,
                                 String system, byte[] data, String filename);

    static native void drawFrame(int surfaceWidth, int surfaceHeight, boolean advance);
    static native void setButtons(int buttons);
    static native byte[] saveBattery();
    static native boolean restoreBattery(byte[] battery);
    static native byte[] saveState();
    static native boolean restoreState(byte[] state);
    static native void setAudioEnabled(boolean enabled);
    static native void closeRom();
}
