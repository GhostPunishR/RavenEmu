package com.ravenemu.app.platform;

import com.ravenemu.app.data.saves.CloudStore;
import com.ravenemu.app.data.saves.FakeCloudStore;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/** The Google Drive of the screens' tests, in memory: no test reaches Google.
 * Every GoogleDriveHttp reaches `folder`, the RavenEmu folder of `email`. */
@Implements(value = GoogleDriveHttp.class, isInAndroidSdk = false)
public class FakeGoogleDrive {
    public static volatile FakeCloudStore folder = new FakeCloudStore();
    public static volatile String email = "player@example.com";

    public static void reset() {
        folder = new FakeCloudStore();
        email = "player@example.com";
    }

    @Implementation protected String email() { return email; }

    @Implementation protected CloudStore.Fetched get(String name, int maxBytes) { return folder.get(name, maxBytes); }

    @Implementation protected int put(String name, byte[] body, String ifMatch, boolean create) {
        return folder.put(name, body, ifMatch, create);
    }

    @Implementation protected int delete(String name) { return folder.delete(name); }
}
