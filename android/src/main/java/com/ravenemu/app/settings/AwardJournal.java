package com.ravenemu.app.settings;

import android.content.Context;
import android.util.AtomicFile;
import com.ravenemu.app.platform.KeystoreSealer;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** The beta's offline journal (AwardJournal.kt): each genuine award request
 * the SDK builds is kept before it is sent, so an unlock earned without the
 * network, or while the player closes (a rotation included), reaches the
 * service at the next session of the same account. Each request carries the
 * account's token: the file is sealed by the Android Keystore, bound to this
 * file, out of backups. Signing out revokes every open journal and deletes it.
 * Blocking: worker threads only. */
public final class AwardJournal {
    static final String FILE = "retroachievements.unsent-awards";
    private static final int MAX_PENDING = 256, MAX_FILE_BYTES = 3 * 1024 * 1024;
    private static final byte[] CONTEXT =
            "RavenEmu native RetroAchievements unsent awards v1".getBytes(StandardCharsets.UTF_8);
    /** Signing out moves it on: a journal opened before never writes again. */
    private static long generation;

    public static final class Award {
        public final String account, url, post, contentType;
        public Award(String account, String url, String post, String contentType) {
            this.account = account; this.url = url; this.post = post; this.contentType = contentType;
        }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Award)) return false;
            Award award = (Award)other;
            return account.equals(award.account) && url.equals(award.url) && post.equals(award.post)
                    && contentType.equals(award.contentType);
        }
        @Override public int hashCode() { return Objects.hash(account, url, post, contentType); }
    }

    private final AtomicFile file;
    private final long openedAt;

    private AwardJournal(Context context, long openedAt) {
        this.file = new AtomicFile(new File(context.getNoBackupFilesDir(), FILE));
        this.openedAt = openedAt;
    }

    public static AwardJournal open(Context context) {
        synchronized (AwardJournal.class) {
            return new AwardJournal(context.getApplicationContext() != null
                    ? context.getApplicationContext() : context, generation);
        }
    }

    /** Signing out: every journal open until now is revoked, then the file goes. */
    public static void clear(Context context) {
        synchronized (AwardJournal.class) {
            ++generation;
            new AtomicFile(new File(context.getNoBackupFilesDir(), FILE)).delete();
        }
    }

    /** False once signed out since it was opened. */
    public boolean isCurrent() {
        synchronized (AwardJournal.class) { return openedAt == generation; }
    }

    /** Kept before the request is sent; false when it may not be, or cannot. */
    public boolean remember(Award entry) {
        synchronized (AwardJournal.class) {
            if (openedAt != generation || entry.account.trim().isEmpty()
                    || !AwardPolicy.isSdkAward(entry.url, entry.post)
                    || entry.contentType.isEmpty() || entry.contentType.length() > 128) return false;
            try {
                List<Award> entries = read();
                List<Award> kept = AwardPolicy.remember(entries, entry, MAX_PENDING, AwardJournal::keyOf);
                if (kept == null) return false;
                if (!kept.equals(entries)) write(kept);
                return true;
            } catch (IOException | GeneralSecurityException | JSONException | RuntimeException unusable) {
                return false;
            }
        }
    }

    /** What an earlier session left for this account, one attempt per unlock. */
    public List<Award> forAccount(String account) {
        synchronized (AwardJournal.class) {
            if (openedAt != generation || account == null) return Collections.emptyList();
            try {
                List<Award> own = new ArrayList<>();
                for (Award award : read()) if (award.account.equalsIgnoreCase(account)) own.add(award);
                return AwardPolicy.latest(own, AwardJournal::keyOf);
            } catch (IOException | GeneralSecurityException | JSONException | RuntimeException unusable) {
                return Collections.emptyList();
            }
        }
    }

    /** Accepted by the service: every attempt of that unlock goes. */
    public void acknowledge(Award entry) {
        synchronized (AwardJournal.class) {
            if (openedAt != generation) return;
            try {
                List<Award> entries = read();
                List<Award> kept = AwardPolicy.acknowledge(entries, entry, AwardJournal::keyOf);
                if (!kept.equals(entries)) write(kept);
            } catch (IOException | GeneralSecurityException | JSONException | RuntimeException unusable) {
                // Kept: it is replayed again, and the service answers "already has".
            }
        }
    }

    private static String keyOf(Award award) { return AwardPolicy.entryKey(award.account, award.post); }

    private List<Award> read() throws IOException, GeneralSecurityException, JSONException {
        byte[] sealed;
        try {
            sealed = file.readFully();
        } catch (FileNotFoundException absent) {
            return new ArrayList<>();
        }
        if (sealed.length > MAX_FILE_BYTES) throw new IOException("Award journal too large");
        byte[] plain = KeystoreSealer.open(sealed, CONTEXT);
        try {
            JSONArray array = new JSONArray(new String(plain, StandardCharsets.UTF_8));
            if (array.length() > MAX_PENDING) throw new IOException("Award journal too long");
            List<Award> entries = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                Award award = new Award(item.getString("account"), item.getString("url"),
                        item.getString("post"), item.getString("type"));
                if (award.account.trim().isEmpty() || !AwardPolicy.isSdkAward(award.url, award.post))
                    throw new IOException("Not an award the SDK built");
                entries.add(award);
            }
            return entries;
        } finally {
            Arrays.fill(plain, (byte)0);
        }
    }

    private void write(List<Award> entries) throws IOException, GeneralSecurityException, JSONException {
        JSONArray array = new JSONArray();
        for (Award entry : entries)
            array.put(new JSONObject().put("account", entry.account).put("url", entry.url)
                    .put("post", entry.post).put("type", entry.contentType));
        byte[] plain = array.toString().getBytes(StandardCharsets.UTF_8);
        byte[] sealed;
        try {
            sealed = KeystoreSealer.seal(plain, CONTEXT);
        } finally {
            Arrays.fill(plain, (byte)0);
        }
        if (sealed.length > MAX_FILE_BYTES) throw new IOException("Award journal too large");
        FileOutputStream stream = file.startWrite();
        try {
            stream.write(sealed);
            file.finishWrite(stream);
        } catch (IOException | RuntimeException error) {
            file.failWrite(stream);
            throw error;
        }
    }
}
