package com.ravenemu.app.settings;

import static org.junit.Assert.*;

import android.content.Context;
import com.ravenemu.app.platform.ClearSealer;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36, shadows = ClearSealer.class)
public final class CloudAccountTest {
    private final Context context = RuntimeEnvironment.getApplication();

    @After public void clean() {
        CloudAccount.clear(context);
        ClearSealer.unavailable = false;
    }

    @Test public void theAccountIsSealedOutOfBackupsAndReadBack() throws Exception {
        assertNull(CloudAccount.load(context));
        new CloudAccount("https://cloud.example.org/remote.php/dav/files/me/RavenEmu/", "me", "app-pass", false)
                .save(context);
        File file = new File(context.getNoBackupFilesDir(), CloudAccount.FILE);
        String raw = new String(Files.readAllBytes(file.toPath()), StandardCharsets.ISO_8859_1);
        assertFalse("The password never reaches the file in clear", raw.contains("app-pass"));
        assertFalse(raw.contains("cloud.example.org"));
        CloudAccount read = CloudAccount.load(context);
        assertEquals("me", read.user);
        assertEquals("app-pass", read.password);
        assertFalse(read.automatic);
        assertTrue(read.withAutomatic(true).automatic);
        CloudAccount.clear(context);
        assertFalse(CloudAccount.stored(context));
        assertNull(CloudAccount.load(context));
    }

    @Test public void theSyncStateFollowsTheFolderAndUserNotThePassword() {
        String folder = "https://cloud.example.org/dav/RavenEmu/";
        assertEquals(new CloudAccount(folder, "me", "one", true).fingerprint(),
                new CloudAccount(folder, "me", "two", false).fingerprint());
        assertNotEquals(new CloudAccount(folder, "me", "one", true).fingerprint(),
                new CloudAccount(folder, "you", "one", true).fingerprint());
        assertNotEquals(new CloudAccount(folder, "me", "one", true).fingerprint(),
                new CloudAccount(folder + "Other/", "me", "one", true).fingerprint());
    }

    @Test public void anotherFilesSealedBytesDoNotOpenAsTheAccount() throws Exception {
        File file = new File(context.getNoBackupFilesDir(), CloudAccount.FILE);
        Files.write(file.toPath(), ClearSealer.sealedFor("{\"folder\":\"https://x/\",\"user\":\"u\",\"password\":\"p\"}"
                .getBytes(StandardCharsets.UTF_8), "RavenEmu.other".getBytes(StandardCharsets.US_ASCII)));
        assertTrue(CloudAccount.stored(context));
        assertNull(CloudAccount.load(context));
    }
}
