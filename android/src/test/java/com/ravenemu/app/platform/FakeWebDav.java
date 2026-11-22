package com.ravenemu.app.platform;

import com.ravenemu.app.data.saves.CloudStore;
import com.ravenemu.app.data.saves.FakeCloudStore;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/** The WebDAV folder of the screens' tests, in memory: no test reaches the
 * network. Every WebDavHttp, whatever its address, reaches `folder`. */
@Implements(value = WebDavHttp.class, isInAndroidSdk = false)
public class FakeWebDav {
    public static volatile FakeCloudStore folder = new FakeCloudStore();

    public static void reset() { folder = new FakeCloudStore(); }

    @Implementation protected CloudStore.Fetched get(String name, int maxBytes) { return folder.get(name, maxBytes); }

    @Implementation protected int put(String name, byte[] body, String ifMatch, boolean create) {
        return folder.put(name, body, ifMatch, create);
    }

    @Implementation protected int delete(String name) { return folder.delete(name); }
}
