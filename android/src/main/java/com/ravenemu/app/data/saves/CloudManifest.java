package com.ravenemu.app.data.saves;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** The list of cartridge saves in the cloud folder, ravenemu-saves.json: for
 * each game (the SHA-256 identity of SaveRepository), the SHA-256 of its save,
 * its size, when and on which device it was sent, and the two versions before
 * it, whose files stay in the folder. Each version has its own file, named
 * after its contents, so a file is never rewritten with other bytes; the list
 * is replaced only if nobody changed it meanwhile (WebDAV ETag). Fields this
 * version does not know are kept as they are. Free of Android types. */
public final class CloudManifest {
    public static final String FILE = "ravenemu-saves.json";
    static final int FORMAT = 1;
    /** Older versions kept in the folder for each game. */
    static final int HISTORY = 2;
    static final int MAX_BYTES = 2 * 1024 * 1024;
    static final int MAX_GAMES = 4096;
    private static final Pattern KEY = Pattern.compile("[a-f0-9]{64}");
    private static final Pattern SHORT = Pattern.compile("[a-f0-9]{16}");
    private static final int MAX_DEVICE_CHARS = 80;

    public static final class Entry {
        public final String sha256, device;
        public final long size, modified;
        /** The short digests of the older versions, newest first. */
        public final List<String> history;

        Entry(String sha256, long size, long modified, String device, List<String> history) {
            this.sha256 = sha256;
            this.size = size;
            this.modified = modified;
            this.device = device;
            this.history = Collections.unmodifiableList(history);
        }
    }

    private final JSONObject root;
    private final JSONObject saves;

    private CloudManifest(JSONObject root, JSONObject saves) {
        this.root = root;
        this.saves = saves;
    }

    public static CloudManifest empty() {
        try {
            JSONObject saves = new JSONObject();
            return new CloudManifest(new JSONObject().put("format", FORMAT).put("saves", saves), saves);
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** Checked whole before use: one bad entry and nothing is synced. */
    public static CloudManifest parse(byte[] body) throws IOException {
        if (body == null || body.length == 0 || body.length > MAX_BYTES)
            throw new IOException("Liste des sauvegardes du cloud illisible");
        try {
            JSONObject root = new JSONObject(new String(body, StandardCharsets.UTF_8));
            int format = root.optInt("format", -1);
            if (format > FORMAT)
                throw new IOException("Le cloud a été écrit par une version plus récente de RavenEmu : "
                        + "mettez l'application à jour");
            if (format != FORMAT) throw new IOException("Liste des sauvegardes du cloud illisible");
            JSONObject saves = root.getJSONObject("saves");
            if (saves.length() > MAX_GAMES) throw new IOException("Trop de sauvegardes dans le cloud");
            CloudManifest manifest = new CloudManifest(root, saves);
            for (Iterator<String> keys = saves.keys(); keys.hasNext(); ) {
                String key = keys.next();
                if (!KEY.matcher(key).matches() || manifest.read(saves.getJSONObject(key)) == null)
                    throw new IOException("Liste des sauvegardes du cloud illisible");
            }
            return manifest;
        } catch (JSONException malformed) {
            throw new IOException("Liste des sauvegardes du cloud illisible", malformed);
        }
    }

    /** The cloud's save for this game, or null. */
    public Entry get(String key) {
        JSONObject entry = saves.optJSONObject(key);
        return entry == null ? null : read(entry);
    }

    public int size() { return saves.length(); }

    /** The save sent now becomes the game's; the one before it joins the
     * history. Returns the files that fall out of the history, to delete. */
    public List<String> put(String key, String sha256, long size, long modified, String device) {
        if (!KEY.matcher(key).matches() || !KEY.matcher(sha256).matches() || size <= 0
                || size > SaveRepository.BATTERY_LIMIT_BYTES)
            throw new IllegalArgumentException("Invalid cloud save");
        try {
            JSONObject entry = saves.optJSONObject(key);
            List<String> history = new ArrayList<>();
            if (entry != null) {
                Entry previous = read(entry);
                if (previous != null) {
                    if (!previous.sha256.equals(sha256)) history.add(shortDigest(previous.sha256));
                    for (String older : previous.history)
                        if (!history.contains(older) && !older.equals(shortDigest(sha256))) history.add(older);
                }
            } else {
                entry = new JSONObject();
                saves.put(key, entry);
            }
            List<String> dropped = new ArrayList<>();
            while (history.size() > HISTORY) dropped.add(fileName(key, history.remove(history.size() - 1)));
            entry.put("sha256", sha256).put("size", size).put("modified", modified)
                    .put("device", clip(device)).put("history", new JSONArray(history));
            return dropped;
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public byte[] toBytes() { return root.toString().getBytes(StandardCharsets.UTF_8); }

    /** The file of one version: the game, then the start of its contents' SHA-256. */
    public static String fileName(String key, String digest) {
        String start = digest.length() > 16 ? digest.substring(0, 16) : digest;
        if (!KEY.matcher(key).matches() || !SHORT.matcher(start).matches())
            throw new IllegalArgumentException("Invalid cloud save");
        return "ravenemu-" + key + "-" + start + ".srm";
    }

    private static String shortDigest(String sha256) { return sha256.substring(0, 16); }

    private static String clip(String device) {
        String name = device == null ? "" : device.replaceAll("\\p{Cntrl}", " ").trim();
        return name.length() > MAX_DEVICE_CHARS ? name.substring(0, MAX_DEVICE_CHARS) : name;
    }

    private static Entry read(JSONObject entry) {
        String sha256 = entry.optString("sha256");
        long size = entry.optLong("size", -1);
        if (!KEY.matcher(sha256).matches() || size <= 0 || size > SaveRepository.BATTERY_LIMIT_BYTES) return null;
        List<String> history = new ArrayList<>();
        JSONArray older = entry.optJSONArray("history");
        if (older != null) {
            for (int i = 0; i < older.length() && history.size() < HISTORY; i++) {
                String digest = older.optString(i);
                if (!SHORT.matcher(digest).matches()) return null;
                history.add(digest);
            }
        }
        return new Entry(sha256, size, Math.max(0, entry.optLong("modified")), clip(entry.optString("device")),
                history);
    }
}
