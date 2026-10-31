package com.ravenemu.app.settings;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONException;
import org.json.JSONObject;

/** Which RetroAchievements requests may enter the offline journal, and how
 * one is replayed: the beta's AwardPolicy, AwardSyncTracker and
 * AwardJournalEntries (RavenEmu 0.8.1). Only genuine award requests the SDK
 * built, sent to retroachievements.org over HTTPS; never an award made from a
 * badge, an achievement id or a save state. Free of Android types. */
public final class AwardPolicy {
    /** RetroAchievements Hardcore needs the approval of the RetroAchievements
     * team. Until then this stays false, as in the beta: no Hardcore switch
     * is shown, and Hardcore award requests are never journaled or replayed. */
    public static final boolean HARDCORE_APPROVED = false;
    private static final int MAX_POST = 8192;

    private AwardPolicy() {}

    /** One unlock: an achievement, in one mode. */
    public static final class Key {
        public final int id;
        public final boolean hardcore;
        Key(int id, boolean hardcore) { this.id = id; this.hardcore = hardcore; }
        @Override public boolean equals(Object other) {
            return other instanceof Key && ((Key)other).id == id && ((Key)other).hardcore == hardcore;
        }
        @Override public int hashCode() { return id * 2 + (hardcore ? 1 : 0); }
    }

    public static boolean isSdkAward(String url, String post) { return isSdkAward(url, post, HARDCORE_APPROVED); }

    public static boolean isSdkAward(String url, String post, boolean allowHardcore) {
        if (url == null || post == null || post.isEmpty() || post.length() > MAX_POST
                || !post.startsWith("r=awardachievement&")) return false;
        boolean softcore = false, hardcore = false;
        for (String field : post.split("&", -1)) {
            if (field.equals("h=0")) softcore = true;
            if (field.equals("h=1")) hardcore = true;
        }
        if (!softcore && !(allowHardcore && hardcore)) return false;
        try {
            URI uri = new URI(url);
            return "https".equals(uri.getScheme()) && "retroachievements.org".equals(uri.getHost())
                    && uri.getPort() == -1 && uri.getRawUserInfo() == null && uri.getRawFragment() == null;
        } catch (URISyntaxException malformed) {
            return false;
        }
    }

    /** The unlock an award request asks for; null for any other request. */
    public static Key requestKey(String post) {
        if (post == null) return null;
        String[] fields = post.split("&", -1);
        if (fields.length == 0 || !fields[0].equals("r=awardachievement")) return null;
        String id = null, mode = null;
        int ids = 0, modes = 0;
        for (String field : fields) {
            if (field.startsWith("a=")) { id = field.substring(2); ids++; }
            if (field.startsWith("h=")) { mode = field.substring(2); modes++; }
        }
        if (ids != 1 || modes != 1 || !(mode.equals("0") || mode.equals("1"))) return null;
        try {
            int value = Integer.parseInt(id);
            return value > 0 ? new Key(value, mode.equals("1")) : null;
        } catch (NumberFormatException malformed) {
            return null;
        }
    }

    /** As in rcheevos (rc_api_runtime.c): accepted, or already on the account
     * from an earlier attempt whose reply was lost. */
    public static boolean accepted(int status, byte[] body) {
        if (status < 200 || status > 299 || body == null) return false;
        try {
            JSONObject reply = new JSONObject(new String(body, StandardCharsets.UTF_8));
            Object success = reply.opt("Success");
            return Boolean.TRUE.equals(success)
                    || Boolean.FALSE.equals(success) && reply.optString("Error").startsWith("User already has");
        } catch (JSONException malformed) {
            return false;
        }
    }

    /** A replay only refreshes the token. The SDK's user name, hash, timing
     * and signature stay intact (rcheevos signs the award without the token,
     * rc_api_runtime.c), and the account must be the same one. */
    public static String authenticatedReplay(String url, String post, String account, String token) {
        return authenticatedReplay(url, post, account, token, HARDCORE_APPROVED);
    }

    public static String authenticatedReplay(String url, String post, String account, String token,
                                             boolean allowHardcore) {
        if (!isSdkAward(url, post, allowHardcore) || token == null || token.trim().isEmpty() || account == null)
            return null;
        String[] fields = post.split("&", -1);
        String user = null;
        int tokens = 0, users = 0, modes = 0;
        for (String field : fields) {
            if (field.startsWith("t=")) tokens++;
            if (field.startsWith("u=")) { users++; user = field.substring(2); }
            if (field.startsWith("h=")) modes++;
        }
        if (tokens != 1 || users != 1 || modes != 1) return null;
        try {
            if (!URLDecoder.decode(user, "UTF-8").equalsIgnoreCase(account)) return null;
            String encoded = URLEncoder.encode(token, "UTF-8");
            StringBuilder replay = new StringBuilder();
            for (int i = 0; i < fields.length; i++) {
                if (i > 0) replay.append('&');
                replay.append(fields[i].startsWith("t=") ? "t=" + encoded : fields[i]);
            }
            return replay.toString();
        } catch (UnsupportedEncodingException | IllegalArgumentException malformed) {
            return null;
        }
    }

    /** rcheevos rebuilds an award on every retry, with a new delay and
     * signature: one entry per account, achievement and mode, the latest. */
    static String entryKey(String account, String post) {
        Key key = requestKey(post);
        return key == null || account == null ? null
                : account.toLowerCase(java.util.Locale.ROOT) + ":" + key.id + ":" + key.hardcore;
    }

    /** The entries with `entry` as the attempt kept for its unlock; null when full. */
    static <T> List<T> remember(List<T> entries, T entry, int max, java.util.function.Function<T, String> key) {
        if (entries.contains(entry)) return entries;
        String own = key.apply(entry);
        List<T> kept = new ArrayList<>();
        for (T other : entries) if (own == null || !own.equals(key.apply(other))) kept.add(other);
        if (kept.size() >= max) return null;
        kept.add(entry);
        return kept;
    }

    /** An accepted attempt settles every attempt of the same unlock. */
    static <T> List<T> acknowledge(List<T> entries, T entry, java.util.function.Function<T, String> key) {
        String own = key.apply(entry);
        List<T> kept = new ArrayList<>();
        for (T other : entries)
            if (!other.equals(entry) && (own == null || !own.equals(key.apply(other)))) kept.add(other);
        return kept;
    }

    /** One replay per unlock, its latest attempt. */
    static <T> List<T> latest(List<T> entries, java.util.function.Function<T, String> key) {
        Map<String, Integer> last = new HashMap<>();
        for (int i = 0; i < entries.size(); i++) {
            String own = key.apply(entries.get(i));
            last.put(own == null ? "#" + i : own, i);
        }
        List<T> result = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            String own = key.apply(entries.get(i));
            if (last.get(own == null ? "#" + i : own) == i) result.add(entries.get(i));
        }
        return result;
    }
}
