package com.ravenemu.app.settings;

import com.ravenemu.app.platform.ClearSealer;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36, shadows = ClearSealer.class)
public final class AchievementAccountTest {
    private Context context;
    private File file;

    @Before public void prepare() {
        context = RuntimeEnvironment.getApplication();
        file = new File(context.getNoBackupFilesDir(), AchievementAccount.FILE);
        AchievementAccount.clear(context);
    }

    @Test public void noAccountUntilOneIsSaved() {
        assertNull(AchievementAccount.load(context));
        assertFalse(file.exists());
    }

    @Test public void theTokenIsSealedOutOfBackupsAndReadBack() throws Exception {
        new AchievementAccount("Raven", "TOKEN-123", "Raven Rouge", "https://media.retroachievements.org/UserPic/Raven.png",
                34, 12).save(context);
        assertTrue("Excluded from backups", file.getParentFile().equals(context.getNoBackupFilesDir()));
        String stored = new String(Files.readAllBytes(file.toPath()), StandardCharsets.ISO_8859_1);
        assertFalse("Never in plain text", stored.contains("TOKEN-123"));
        AchievementAccount loaded = AchievementAccount.load(context);
        assertEquals("Raven", loaded.user);
        assertEquals("TOKEN-123", loaded.token);
        assertEquals("Raven Rouge", loaded.displayName);
        assertEquals("https://media.retroachievements.org/UserPic/Raven.png", loaded.avatarUrl);
        assertEquals(34, loaded.softcorePoints);
        assertEquals(12, loaded.hardcorePoints);
    }

    @Test public void aDamagedOrForeignFileReadsAsNoAccountAndSigningOutForgetsIt() throws Exception {
        new AchievementAccount("Raven", "TOKEN-123", "", "", 0, 0).save(context);
        assertEquals("The name stands in for a missing display name", "Raven", AchievementAccount.load(context).displayName);
        try (FileOutputStream out = new FileOutputStream(file)) { out.write("not sealed".getBytes(StandardCharsets.UTF_8)); }
        assertNull(AchievementAccount.load(context));
        new AchievementAccount("Raven", "", "", "", 0, 0).save(context);
        assertNull("An account without its token is none", AchievementAccount.load(context));
        new AchievementAccount("Raven", "TOKEN-123", "", "", -5, 0).save(context);
        assertEquals(0, AchievementAccount.load(context).softcorePoints);
        AchievementAccount.clear(context);
        assertNull(AchievementAccount.load(context));
        assertFalse(file.exists());
    }
}
