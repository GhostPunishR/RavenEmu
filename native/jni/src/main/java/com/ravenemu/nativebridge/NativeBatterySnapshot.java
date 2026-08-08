package com.ravenemu.nativebridge;

/** Immutable copy of a native cartridge save and its generation. */
public final class NativeBatterySnapshot {
    public final byte[] data;
    public final long generation;

    public NativeBatterySnapshot(byte[] data, long generation) {
        this.data = data;
        this.generation = generation;
    }
}
