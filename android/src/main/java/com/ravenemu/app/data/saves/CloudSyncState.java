package com.ravenemu.app.data.saves;

import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** What this phone remembers of the cloud sync, in files/cloud/sync-state.json:
 * for each game, the save both sides had at the last sync (the base of
 * CloudSyncPlan), the conflicts waiting for the player, and how the last sync
 * went. Bound to one folder and one account: another starts from nothing. */
public final class CloudSyncState {
    static final String FILE = "sync-state.json";
    private static final int FORMAT = 1;
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final Pattern KEY = Pattern.compile("[a-f0-9]{64}");

    /** A save changed on both sides since the last sync. */
    public static final class Conflict {
        public final String key, phoneSha256, cloudSha256, cloudDevice;
        public final long phoneModified, cloudModified;

        public Conflict(String key, String phoneSha256, long phoneModified, String cloudSha256,
                        long cloudModified, String cloudDevice) {
            this.key = key;
            this.phoneSha256 = phoneSha256;
            this.phoneModified = phoneModified;
            this.cloudSha256 = cloudSha256;
            this.cloudModified = cloudModified;
            this.cloudDevice = cloudDevice == null ? "" : cloudDevice;
        }
    }

    public final String account;
    final Map<String, String> base;
    public final List<Conflict> conflicts;
    /** When the last sync ended (0: never), and its error, or "". */
    public final long lastSync;
    public final String lastError;
    public final int lastUploaded, lastDownloaded;

    CloudSyncState(String account, Map<String, String> base, List<Conflict> conflicts, long lastSync,
                   String lastError, int lastUploaded, int lastDownloaded) {
        this.account = account == null ? "" : account;
        this.base = Collections.unmodifiableMap(new LinkedHashMap<>(base));
        this.conflicts = Collections.unmodifiableList(new ArrayList<>(conflicts));
        this.lastSync = lastSync;
        this.lastError = lastError == null ? "" : lastError;
        this.lastUploaded = lastUploaded;
        this.lastDownloaded = lastDownloaded;
    }

    static CloudSyncState empty(String account) {
        return new CloudSyncState(account, Collections.emptyMap(), Collections.emptyList(), 0, "", 0, 0);
    }

    /** The base of one game, or null. */
    public String base(String key) { return base.get(key); }

    /** The state of this account; a missing, damaged or other account's file
     * is a first sync, which never overwrites a save: it can only conflict. */
    public static CloudSyncState load(File files, String account) {
        AtomicFile file = file(files);
        if (!file.getBaseFile().exists() && !new File(file.getBaseFile().getPath() + ".bak").exists())
            return empty(account);
        try {
            byte[] bytes = file.readFully();
            if (bytes.length > MAX_BYTES) return empty(account);
            JSONObject json = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            if (json.optInt("format") != FORMAT || !account.equals(json.optString("account"))) return empty(account);
            Map<String, String> base = new LinkedHashMap<>();
            JSONObject saved = json.optJSONObject("base");
            if (saved != null) {
                for (Iterator<String> keys = saved.keys(); keys.hasNext(); ) {
                    String key = keys.next();
                    String sha = saved.optString(key);
                    if (KEY.matcher(key).matches() && KEY.matcher(sha).matches()) base.put(key, sha);
                }
            }
            List<Conflict> conflicts = new ArrayList<>();
            JSONArray waiting = json.optJSONArray("conflicts");
            if (waiting != null) {
                for (int i = 0; i < waiting.length(); i++) {
                    JSONObject c = waiting.optJSONObject(i);
                    if (c == null || !KEY.matcher(c.optString("key")).matches()
                            || !KEY.matcher(c.optString("phone")).matches()
                            || !KEY.matcher(c.optString("cloud")).matches()) continue;
                    conflicts.add(new Conflict(c.optString("key"), c.optString("phone"), c.optLong("phoneModified"),
                            c.optString("cloud"), c.optLong("cloudModified"), c.optString("device")));
                }
            }
            return new CloudSyncState(account, base, conflicts, json.optLong("lastSync"), json.optString("lastError"),
                    json.optInt("uploaded"), json.optInt("downloaded"));
        } catch (IOException | JSONException | RuntimeException unreadable) {
            return empty(account);
        }
    }

    /** Atomic: the previous state stays whole if this write fails. */
    public void save(File files) throws IOException {
        byte[] bytes;
        try {
            JSONObject json = new JSONObject().put("format", FORMAT).put("account", account)
                    .put("base", new JSONObject(base)).put("lastSync", lastSync).put("lastError", lastError)
                    .put("uploaded", lastUploaded).put("downloaded", lastDownloaded);
            JSONArray waiting = new JSONArray();
            for (Conflict c : conflicts)
                waiting.put(new JSONObject().put("key", c.key).put("phone", c.phoneSha256)
                        .put("phoneModified", c.phoneModified).put("cloud", c.cloudSha256)
                        .put("cloudModified", c.cloudModified).put("device", c.cloudDevice));
            bytes = json.put("conflicts", waiting).toString().getBytes(StandardCharsets.UTF_8);
        } catch (JSONException impossible) {
            throw new IOException(impossible);
        }
        File folder = new File(files, CloudSaves.FOLDER);
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("Impossible de créer le dossier du cloud");
        AtomicFile file = file(files);
        FileOutputStream stream = file.startWrite();
        try {
            stream.write(bytes);
            file.finishWrite(stream);
        } catch (IOException | RuntimeException error) {
            file.failWrite(stream);
            throw error;
        }
    }

    /** Forgets everything: signing out, or another folder. */
    public static void clear(File files) {
        file(files).delete();
    }

    private static AtomicFile file(File files) {
        return new AtomicFile(new File(new File(files, CloudSaves.FOLDER), FILE));
    }
}
