package com.ravenemu.app.settings;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/** RetroAchievements' approval, as it will be once granted: tests only. */
@Implements(value = HardcorePolicy.class, isInAndroidSdk = false)
public class ApprovedHardcore {
    @Implementation protected static boolean approved() { return true; }
}
