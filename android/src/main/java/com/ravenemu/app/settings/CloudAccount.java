package com.ravenemu.app.settings;

import android.content.Context;
import android.util.AtomicFile;
import com.ravenemu.app.data.saves.CloudSync;
import com.ravenemu.app.platform.KeystoreSealer;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import org.json.JSONException;
import org.json.JSONObject;

/** The cloud the cartridge saves are synced with, and whether the sync runs
 * by itself: a WebDAV folder (its address, the user name and the application
 * password) or Google Drive (the account's address only: Google Play
 * services keep its grant and tokens). Sealed by the Android Keystore in a
 * file excluded from backups, as the RetroAchievements token
 * (AchievementAccount); the password is needed at each sync, so it is kept,
 * encrypted, and nowhere else. */
public final class CloudAccount {
    static final String FILE = "cloud.account";
    /** Binds the sealed bytes to this file: they never open as another's. */
    private static final byte[] CONTEXT = "RavenEmu.cloud.account.v1".getBytes(StandardCharsets.US_ASCII);

    public static final String WEBDAV = "webdav", DRIVE = "google-drive";

    /** WEBDAV or DRIVE; for Google Drive, `user` is the account's address. */
    public final String kind;
    public final String folder, user, password;
    public final boolean automatic;

    /** A WebDAV folder. */
    public CloudAccount(String folder, String user, String password, boolean automatic) {
        this(WEBDAV, folder, user, password, automatic);
    }

    private CloudAccount(String kind, String folder, String user, String password, boolean automatic) {
        this.kind = kind;
        this.folder = folder == null ? "" : folder;
        this.user = user == null ? "" : user;
        this.password = password == null ? "" : password;
        this.automatic = automatic;
    }

    /** The Google Drive of `email`. */
    public static CloudAccount googleDrive(String email, boolean automatic) {
        return new CloudAccount(DRIVE, "", email, "", automatic);
    }

    public boolean isGoogleDrive() { return DRIVE.equals(kind); }

    public CloudAccount withAutomatic(boolean enabled) {
        return new CloudAccount(kind, folder, user, password, enabled);
    }

    /** The same folder and user, or the same Google account: the sync state
     * survives a new password, and is never another cloud's. */
    public String fingerprint() {
        String identity = isGoogleDrive() ? DRIVE + "\n" + user : folder + "\n" + user;
        return CloudSync.sha256(identity.getBytes(StandardCharsets.UTF_8));
    }

    /** The stored account; null without one, or unreadable (another device's
     * key, a damaged file): signing in again replaces it. */
    public static CloudAccount load(Context context) {
        AtomicFile file = file(context);
        if (!file.getBaseFile().exists()) return null;
        byte[] plain = null;
        try {
            plain = KeystoreSealer.open(file.readFully(), CONTEXT);
            JSONObject json = new JSONObject(new String(plain, StandardCharsets.UTF_8));
            String kind = json.optString("kind", WEBDAV);
            boolean automatic = json.optBoolean("automatic", true);
            if (DRIVE.equals(kind)) {
                String email = json.getString("user");
                return email.isEmpty() ? null : googleDrive(email, automatic);
            }
            if (!WEBDAV.equals(kind)) return null;
            CloudAccount account = new CloudAccount(json.getString("folder"), json.getString("user"),
                    json.getString("password"), automatic);
            return account.folder.isEmpty() || account.user.isEmpty() ? null : account;
        } catch (IOException | GeneralSecurityException | JSONException | RuntimeException unreadable) {
            return null;
        } finally {
            if (plain != null) Arrays.fill(plain, (byte)0);
        }
    }

    /** Atomic: the previous account stays whole if this write fails. */
    public void save(Context context) throws IOException, GeneralSecurityException {
        byte[] plain;
        try {
            plain = new JSONObject().put("kind", kind).put("folder", folder).put("user", user).put("password", password)
                    .put("automatic", automatic).toString().getBytes(StandardCharsets.UTF_8);
        } catch (JSONException impossible) {
            throw new IOException(impossible);
        }
        byte[] sealed;
        try {
            sealed = KeystoreSealer.seal(plain, CONTEXT);
        } finally {
            Arrays.fill(plain, (byte)0);
        }
        AtomicFile file = file(context);
        FileOutputStream stream = file.startWrite();
        try {
            stream.write(sealed);
            file.finishWrite(stream);
        } catch (IOException | RuntimeException error) {
            file.failWrite(stream);
            throw error;
        }
    }

    /** Signing out: the address and the password, or the Google account, are
     * forgotten on this device. */
    public static void clear(Context context) {
        file(context).delete();
    }

    public static boolean stored(Context context) {
        return file(context).getBaseFile().exists();
    }

    private static AtomicFile file(Context context) {
        return new AtomicFile(new File(context.getNoBackupFilesDir(), FILE));
    }
}
