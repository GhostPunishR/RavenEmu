package com.ravenemu.nativebridge;

/** Owns one passive native core and releases it when a session is discarded. */
public final class NativeCoreHandle implements AutoCloseable {
    private long value;

    public NativeCoreHandle(int consoleStorageId, int forcedSaveType) {
        value = NativeCoreBridge.create(consoleStorageId, forcedSaveType);
    }

    public synchronized long value() {
        if (value == 0) throw new IllegalStateException("Le cœur natif RavenEmu est fermé");
        return value;
    }

    @Override
    public synchronized void close() {
        if (value == 0) return;
        NativeCoreBridge.destroy(value);
        value = 0;
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void finalize() throws Throwable {
        try {
            close();
        } finally {
            super.finalize();
        }
    }
}
