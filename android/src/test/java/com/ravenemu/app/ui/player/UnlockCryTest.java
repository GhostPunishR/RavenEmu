package com.ravenemu.app.ui.player;

import com.ravenemu.app.settings.AchievementSettings;
import com.ravenemu.app.bridge.NativeAchievements;

import android.app.Activity;
import android.media.SoundPool;
import android.os.Looper;
import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.Duration;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSoundPool;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** The crow's cry, the owner's recording from CorvusRetro: played for an
 * unlock and a completed game, at the game's volume; one asked for before the
 * sound is loaded plays once it is, unless a second has passed; the pause,
 * the closing game and a muted game stop it. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36)
public final class UnlockCryTest {
    private Activity activity;

    @Before public void prepare() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
    }

    private static ShadowSoundPool pool(UnlockCry cry) {
        SoundPool pool = ReflectionHelpers.getField(cry, "pool");
        return shadowOf(pool);
    }

    private static void advance(long millis) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis)); }

    @Test public void theRecordingIsCorvusRetrosUnchanged() throws Exception {
        File sound = null;
        for (File directory = new File(System.getProperty("user.dir")).getAbsoluteFile(); directory != null && sound == null;
             directory = directory.getParentFile()) {
            for (String path : new String[]{"src/main/res/raw/achievement_unlock.mp3",
                    "android/src/main/res/raw/achievement_unlock.mp3"}) {
                File candidate = new File(directory, path);
                if (candidate.isFile()) sound = candidate;
            }
        }
        assertNotNull("res/raw/achievement_unlock.mp3", sound);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(sound.toPath()));
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) hex.append(String.format("%02x", b));
        assertEquals("CorvusRetro's croassement, integrated without change",
                "f00a367f0653432af12f74bfd8f9c62194910cfba475b265b0057decf81c2531", hex.toString());
    }

    @Test public void aCryAskedForBeforeTheSoundIsLoadedPlaysOnceItIs() {
        UnlockCry cry = new UnlockCry(activity);
        cry.play(80);
        assertFalse("Not loaded yet", pool(cry).wasResourcePlayed(UnlockCry.SOUND));
        pool(cry).notifyResourceLoaded(UnlockCry.SOUND, true);
        assertTrue(pool(cry).wasResourcePlayed(UnlockCry.SOUND));
        assertEquals("At the game's volume", 0.8f,
                pool(cry).getResourcePlaybacks(UnlockCry.SOUND).get(0).leftVolume, 0.001f);
        cry.release();
    }

    @Test public void aLateOrSilentCryIsNotPlayed() {
        UnlockCry late = new UnlockCry(activity);
        late.play(100);
        advance(UnlockCry.LATE_MILLIS + 10);
        pool(late).notifyResourceLoaded(UnlockCry.SOUND, true);
        assertFalse("A second late: no longer with its popup", pool(late).wasResourcePlayed(UnlockCry.SOUND));
        late.play(0);
        assertFalse("Volume at zero: silent", pool(late).wasResourcePlayed(UnlockCry.SOUND));
        late.play(50);
        assertTrue("Loaded: at once", pool(late).wasResourcePlayed(UnlockCry.SOUND));
        late.release();
        late.play(50);
        UnlockCry stopped = new UnlockCry(activity);
        stopped.play(100);
        stopped.stop();
        pool(stopped).notifyResourceLoaded(UnlockCry.SOUND, true);
        assertFalse("Stopped before it was loaded: nothing waits", pool(stopped).wasResourcePlayed(UnlockCry.SOUND));
        stopped.release();
    }

    @Test public void theOverlayCriesForAnUnlockNotForTheGameStarting() {
        AchievementOverlay overlay = new AchievementOverlay(activity, new BadgeCache());
        activity.setContentView(overlay);
        overlay.setSettings(AchievementSettings.DEFAULTS, true, 70);
        UnlockCry cry = ReflectionHelpers.getField(overlay, "cry");
        pool(cry).notifyResourceLoaded(UnlockCry.SOUND, true);
        overlay.onEvent(new NativeAchievements.Event("game", "Synthetic game", "3", "0", "", "1"));
        assertFalse("The game starting: no cry", pool(cry).wasResourcePlayed(UnlockCry.SOUND));
        overlay.clear();
        overlay.onEvent(new NativeAchievements.Event("achievement", "Premier", "", "5", "", "1"));
        assertTrue("An unlock: the cry", pool(cry).wasResourcePlayed(UnlockCry.SOUND));
        pool(cry).clearPlayed();
        overlay.clear();
        overlay.setSettings(AchievementSettings.DEFAULTS.withSound(false), true, 70);
        overlay.onEvent(new NativeAchievements.Event("achievement", "Second", "", "5", "", "2"));
        assertFalse("Son des succès off: silent", pool(cry).wasResourcePlayed(UnlockCry.SOUND));
        overlay.setSettings(AchievementSettings.DEFAULTS, false, 70);
        overlay.onEvent(new NativeAchievements.Event("completed", "Synthetic game", "1", "1", "", "5"));
        assertFalse("The game's sound off: silent", pool(cry).wasResourcePlayed(UnlockCry.SOUND));
        overlay.setSettings(AchievementSettings.DEFAULTS.withUnlockPopups(false), true, 70);
        overlay.onEvent(new NativeAchievements.Event("completed", "Synthetic game", "1", "1", "", "5"));
        assertTrue("No popup: the cry alone", pool(cry).wasResourcePlayed(UnlockCry.SOUND));
        overlay.release();
    }
}
