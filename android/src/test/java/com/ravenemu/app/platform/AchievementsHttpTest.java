package com.ravenemu.app.platform;

import android.graphics.Bitmap;
import java.io.ByteArrayOutputStream;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

/** Where RetroAchievements' traffic may go, and which pictures are kept.
 * No test here reaches the network. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class AchievementsHttpTest {
    @Test public void onlyHttpsToRetroAchievementsAndItsSubdomains() {
        assertTrue(AchievementsHttp.allowed("https://retroachievements.org/dorequest.php"));
        assertTrue(AchievementsHttp.allowed("https://media.retroachievements.org/Badge/00234.png"));
        assertTrue(AchievementsHttp.allowed("HTTPS://RetroAchievements.org/dorequest.php"));
        assertFalse("Clear text", AchievementsHttp.allowed("http://retroachievements.org/dorequest.php"));
        assertFalse(AchievementsHttp.allowed("https://retroachievements.org.example.com/x"));
        assertFalse(AchievementsHttp.allowed("https://evilretroachievements.org/x"));
        assertFalse(AchievementsHttp.allowed("https://user@retroachievements.org/x"));
        assertFalse(AchievementsHttp.allowed("file:///data/data/com.ravenemu.dev/x"));
        assertFalse(AchievementsHttp.allowed("not a url"));
        assertFalse(AchievementsHttp.allowed(null));
    }

    @Test public void aRefusedAddressIsNoAnswerWithoutAnyConnection() {
        AchievementsHttp http = new AchievementsHttp("RavenEmu/0.1");
        AchievementsHttp.Answer answer = http.send("https://blocked.invalid/dorequest.php", "r=login2", "");
        assertEquals(AchievementsHttp.NO_ANSWER, answer.status);
        assertEquals(0, answer.body.length);
        assertNull(http.image("http://media.retroachievements.org/Badge/1.png"));
        assertNull(http.badge(""));
    }

    @Test public void badgesDecodeButLargerPicturesAndGarbageDoNot() {
        assertNotNull(AchievementsHttp.decode(png(64)));
        assertNull("Larger than any badge or avatar", AchievementsHttp.decode(png(AchievementsHttp.MAX_IMAGE_SIDE + 1)));
        assertNull(AchievementsHttp.decode(new byte[]{1, 2, 3, 4}));
        assertNull(AchievementsHttp.decode(null));
    }

    private static byte[] png(int side) {
        Bitmap bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out));
        return out.toByteArray();
    }
}
