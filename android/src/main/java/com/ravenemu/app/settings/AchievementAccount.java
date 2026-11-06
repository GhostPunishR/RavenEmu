package com.ravenemu.app.settings;

import android.content.Context;
import android.util.AtomicFile;
import com.ravenemu.app.platform.KeystoreSealer;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import org.json.JSONException;
import org.json.JSONObject;

/** The RetroAchievements account of this preview, after the beta's
 * AchievementAccount.kt: the token only, never the password, sealed by the
 * Android Keystore in a file excluded from backups. The name, avatar and
 * points are kept with it to show the account without the network. No beta
 * data is read: com.ravenemu.dev has its own file and key. */
public final class AchievementAccount {
    static final String FILE = "retroachievements.account";

    public final String user, token, displayName, avatarUrl;
    public final long softcorePoints, hardcorePoints;

    public AchievementAccount(String user, String token, String displayName, String avatarUrl,
                              long softcorePoints, long hardcorePoints) {
        this.user = user == null ? "" : user;
        this.token = token == null ? "" : token;
        this.displayName = displayName == null || displayName.trim().isEmpty() ? this.user : displayName;
        this.avatarUrl = avatarUrl == null ? "" : avatarUrl;
        this.softcorePoints = Math.max(0, softcorePoints);
        this.hardcorePoints = Math.max(0, hardcorePoints);
    }

    /** The stored account; null without one, or when it cannot be read
     * (another device's key, a damaged file): signing in again replaces it. */
    public static AchievementAccount load(Context context) {
        AtomicFile file = file(context);
        if (!file.getBaseFile().exists()) return null;
        byte[] plain = null;
        try {
            plain = KeystoreSealer.open(file.readFully());
            JSONObject json = new JSONObject(new String(plain, StandardCharsets.UTF_8));
            AchievementAccount account = new AchievementAccount(json.getString("user"), json.getString("token"),
                    json.optString("displayName"), json.optString("avatarUrl"),
                    json.optLong("softcore"), json.optLong("hardcore"));
            return account.user.isEmpty() || account.token.isEmpty() ? null : account;
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
            plain = new JSONObject().put("user", user).put("token", token).put("displayName", displayName)
                    .put("avatarUrl", avatarUrl).put("softcore", softcorePoints).put("hardcore", hardcorePoints)
                    .toString().getBytes(StandardCharsets.UTF_8);
        } catch (JSONException impossible) {
            throw new IOException(impossible);
        }
        byte[] sealed;
        try {
            sealed = KeystoreSealer.seal(plain);
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

    /** Signing out: the token is forgotten on this device. */
    public static void clear(Context context) {
        file(context).delete();
    }

    private static AtomicFile file(Context context) {
        return new AtomicFile(new File(context.getNoBackupFilesDir(), FILE));
    }
}
