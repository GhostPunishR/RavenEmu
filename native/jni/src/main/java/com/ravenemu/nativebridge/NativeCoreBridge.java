package com.ravenemu.nativebridge;

import java.io.File;

/**
 * Stable Java/JNI boundary shared by every RavenEmu native core.
 *
 * The bridge deliberately transports only primitive arrays and tiny immutable
 * value objects. Emulator policy and the public Kotlin API stay outside JNI.
 */
public final class NativeCoreBridge {
    private NativeCoreBridge() {}

    static {
        String explicitLibrary = System.getProperty("ravenemu.native.library");
        if (explicitLibrary == null || explicitLibrary.trim().isEmpty()) {
            System.loadLibrary("ravenemu_native");
        } else {
            System.load(new File(explicitLibrary).getAbsolutePath());
        }
    }

    public static native long create(int consoleStorageId, int forcedSaveType);
    public static native void destroy(long handle);
    public static native void loadRom(long handle, byte[] rom, byte[] batteryRam);
    public static native void loadRomFromDescriptor(
        long handle, int descriptor, long sizeBytes, byte[] batteryRam);
    public static native void reset(long handle);
    public static native void runFrame(long handle, int[] framebuffer, boolean renderVideo);
    public static native void setButton(long handle, int buttonOrdinal, boolean pressed);
    public static native void setTouch(long handle, boolean down, int x, int y);
    public static native int readAudio(long handle, short[] destination);
    public static native boolean rumbleActive(long handle);
    public static native int framebufferFormat(long handle);
    public static native boolean hasBatteryRam(long handle);
    public static native boolean batteryRamDirty(long handle);
    public static native NativeBatterySnapshot snapshotBatteryRam(long handle);
    public static native void acknowledgeBatteryRamSaved(long handle, long generation);
    public static native byte[] saveState(long handle);
    public static native void loadState(long handle, byte[] state);
    public static native void setClockEpoch(long handle, boolean overridden, long epochSeconds);
    public static native int gbaSaveType(long handle);
    public static native void setGbaForcedSaveType(long handle, int forcedSaveType);
    public static native boolean gbaRtcActive(long handle);

    /**
     * Impose la présence de l'horloge de cartouche Game Boy Advance.
     *
     * <p>Tri-état transporté en entier, faute d'{@code Optional} à la frontière
     * JNI : négatif rend la main à la détection, zéro impose l'absence, positif
     * impose la présence. Pris en compte au prochain chargement de ROM.
     */
    public static native void setGbaForcedRtc(long handle, int forcedRtc);
    public static native void setMeasuringTime(long handle, boolean enabled);
    public static native boolean measuringTime(long handle);
    public static native NativeGbaDebugSnapshot debugSnapshot(long handle);
    public static native NativeDiagnosticBatch drainDiagnostics(long handle);
}
