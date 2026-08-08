package com.ravenemu.nativebridge;

/** Compact JNI transport for the GBA debug overlay. */
public final class NativeGbaDebugSnapshot {
    public final int[] scalars;
    public final int[] layerPixels;
    public final int[] swiCounts;
    public final double[] timingsMillis;

    public NativeGbaDebugSnapshot(
            int[] scalars,
            int[] layerPixels,
            int[] swiCounts,
            double[] timingsMillis
    ) {
        this.scalars = scalars;
        this.layerPixels = layerPixels;
        this.swiCounts = swiCounts;
        this.timingsMillis = timingsMillis;
    }
}
