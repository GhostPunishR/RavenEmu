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
 *
 * A damaged entry is skipped, the others kept. A file that cannot be opened
 * at all (another key, damaged bytes) is set aside, never deleted, once a new
 * one can be sealed: the unlocks that follow are kept again, rather than lost
 * until the player signs out. A Keystore that is only unavailable for now
 * seals nothing either, so it never moves a good file. Blocking: worker
 * threads only. */
public final class AwardJournal {
    static final String FILE = "retroachievements.unsent-awards";
    /** Where a journal that cannot be opened is set aside (one, the latest). */
    static final String UNREADABLE = FILE + ".unreadable";
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
            // Its requests carry the token too: they go with the account.
            new File(context.getNoBackupFilesDir(), UNREADABLE).delete();
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
            List<Award> entries;
            try {
                entries = read();
            } catch (IOException | GeneralSecurityException | JSONException | RuntimeException unreadable) {
                return startOver(entry);
            }
            try {
                List<Award> kept = AwardPolicy.remember(entries, entry, MAX_PENDING, AwardJournal::keyOf);
                if (kept == null) return false;
                if (!kept.equals(entries)) write(seal(kept));
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
                if (!kept.equals(entries)) write(seal(kept));
            } catch (IOException | GeneralSecurityException | JSONException | RuntimeException unusable) {
                // Kept: it is replayed again, and the service answers "already has".
            }
        }
    }

    /** The file cannot be opened: a new journal is sealed first (a Keystore
     * unavailable for now fails here and moves nothing), then the old file is
     * set aside and the new one written. */
    private boolean startOver(Award entry) {
        try {
            List<Award> fresh = AwardPolicy.remember(new ArrayList<>(), entry, MAX_PENDING, AwardJournal::keyOf);
            if (fresh == null) return false;
            byte[] sealed = seal(fresh);
            File base = file.getBaseFile();
            File aside = new File(base.getParentFile(), UNREADABLE);
            if (aside.exists() && !aside.delete()) return false;
            if (base.exists() && !base.renameTo(aside)) return false;
            write(sealed);
            return true;
        } catch (IOException | GeneralSecurityException | JSONException | RuntimeException unusable) {
            return false;
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
            List<Award> entries = new ArrayList<>();
            // The latest MAX_PENDING, as remember keeps them; a damaged entry
            // is skipped, never sent, and goes at the next write.
            for (int i = Math.max(0, array.length() - MAX_PENDING); i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                Award award = new Award(item.optString("account"), item.optString("url"),
                        item.optString("post"), item.optString("type"));
                if (award.account.trim().isEmpty() || !AwardPolicy.isSdkAward(award.url, award.post)
                        || award.contentType.isEmpty() || award.contentType.length() > 128) continue;
                entries.add(award);
            }
            return entries;
        } finally {
            Arrays.fill(plain, (byte)0);
        }
    }

    private static byte[] seal(List<Award> entries) throws IOException, GeneralSecurityException, JSONException {
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
        return sealed;
    }

    private void write(byte[] sealed) throws IOException {
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
