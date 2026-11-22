package com.ravenemu.app.data.saves;

import java.io.IOException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** One phone's saves in memory, as CloudSaves keeps them. */
final class FakeLocalSaves implements CloudSync.LocalSaves {
    final Map<String, byte[]> saves = new LinkedHashMap<>();
    final Map<String, byte[]> keptAside = new LinkedHashMap<>();
    final Set<String> inPlay = new HashSet<>();
    final Set<String> unreadable = new HashSet<>();

    @Override public byte[] read(String key) throws IOException {
        if (unreadable.contains(key)) throw new IOException("Fichier de sauvegarde vide");
        byte[] save = saves.get(key);
        return save == null ? null : save.clone();
    }

    @Override public long modified(String key) { return saves.containsKey(key) ? 1_000 : 0; }

    @Override public boolean replace(String key, String expectedSha256, byte[] bytes) {
        if (inPlay.contains(key)) return false;
        byte[] current = saves.get(key);
        String sha = current == null ? null : CloudSync.sha256(current);
        if (expectedSha256 == null ? sha != null : !expectedSha256.equals(sha)) return false;
        if (current != null) keptAside.put(key, current);
        saves.put(key, bytes.clone());
        return true;
    }
}
