package com.ravenemu.app.data.saves;

import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/** This phone's side of the cloud sync: the cartridge saves of
 * SaveRepository, read and replaced on the saves' own executor (SaveStorage),
 * after the player's last write. A save the cloud replaces is first copied to
 * files/cloud/backups/, five versions per game. The games being played are
 * never replaced: the library marks them before it opens the player. */
public final class CloudSaves implements CloudSync.LocalSaves {
    static final String FOLDER = "cloud";
    static final String BACKUPS = "backups";
    /** Versions kept aside for each game. */
    static final int BACKUPS_PER_GAME = 5;
    private static final Set<String> IN_PLAY = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private final File files;

    public CloudSaves(File files) { this.files = files; }

    /** UI thread, before the player opens: those saves stay as they are. */
    public static void markInPlay(String... keys) {
        for (String key : keys) if (key != null) IN_PLAY.add(key);
    }

    /** UI thread, back in the library: true if a game was being played. */
    public static boolean clearInPlay() {
        boolean played = !IN_PLAY.isEmpty();
        IN_PLAY.clear();
        return played;
    }

    @Override public byte[] read(String key) throws IOException {
        return onSavesExecutor(() -> SaveRepository.read(files, key, "srm"));
    }

    @Override public long modified(String key) {
        return new File(new File(files, "native-saves"), key + ".srm").lastModified();
    }

    @Override public boolean replace(String key, String expectedSha256, byte[] bytes) throws IOException {
        return onSavesExecutor(() -> {
            if (IN_PLAY.contains(key)) return false;
            byte[] current = SaveRepository.read(files, key, "srm");
            String sha = current == null ? null : CloudSync.sha256(current);
            if (expectedSha256 == null ? sha != null : !expectedSha256.equals(sha)) return false;
            if (current != null) keepAside(key, sha, current);
            SaveRepository.write(files, key, "srm", bytes);
            return true;
        });
    }

    /** The versions kept aside for a game, newest first. */
    public File[] backups(String key) {
        File[] kept = new File(new File(files, FOLDER), BACKUPS).listFiles(
                (folder, name) -> name.startsWith(key + "-") && name.endsWith(".srm"));
        if (kept == null) return new File[0];
        Arrays.sort(kept, (a, b) -> b.getName().compareTo(a.getName()));
        return kept;
    }

    private void keepAside(String key, String sha256, byte[] bytes) throws IOException {
        File folder = new File(new File(files, FOLDER), BACKUPS);
        if (!folder.isDirectory() && !folder.mkdirs())
            throw new IOException("Impossible de mettre de côté la sauvegarde remplacée");
        // Sorted by name: the time first, zero-padded.
        String name = String.format(java.util.Locale.ROOT, "%s-%013d-%s.srm", key, System.currentTimeMillis(),
                sha256.substring(0, 16));
        AtomicFile file = new AtomicFile(new File(folder, name));
        FileOutputStream stream = file.startWrite();
        try {
            stream.write(bytes);
            file.finishWrite(stream);
        } catch (IOException | RuntimeException error) {
            file.failWrite(stream);
            throw error;
        }
        File[] kept = backups(key);
        for (int i = BACKUPS_PER_GAME; i < kept.length; i++) //noinspection ResultOfMethodCallIgnored
            kept[i].delete();
    }

    private interface Work<T> { T run() throws IOException; }

    private static <T> T onSavesExecutor(Work<T> work) throws IOException {
        Future<T> result = SaveStorage.IO.submit(work::run);
        try {
            return result.get();
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
            throw new IOException("Synchronisation interrompue", stopped);
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof IOException) throw (IOException) cause;
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            throw new IOException(cause);
        }
    }
}
