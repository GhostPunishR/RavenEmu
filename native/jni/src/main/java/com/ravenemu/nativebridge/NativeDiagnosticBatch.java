package com.ravenemu.nativebridge;

/** Diagnostics drained atomically after a native emulation call. */
public final class NativeDiagnosticBatch {
    public final int[] events;
    public final String[] details;

    public NativeDiagnosticBatch(int[] events, String[] details) {
        this.events = events;
        this.details = details;
    }
}
