package com.ravenemu.app.data.saves;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The one executor for save files (SRAM and states) in this process. The
 * player queues its last SRAM write in onPause, before the library resumes:
 * a transfer started from the library therefore always runs after it, and an
 * imported save is never overwritten by a late write of the game it replaces.
 * Never shut down. */
public final class SaveStorage {
    public static final ExecutorService IO = Executors.newSingleThreadExecutor(work -> {
        Thread thread = new Thread(work, "RavenEmu saves");
        thread.setDaemon(true);
        return thread;
    });

    private SaveStorage() {}
}
