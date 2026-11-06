package com.ravenemu.app.settings;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** The beta's AwardPolicy rules: only the SDK's own Softcore award requests to
 * retroachievements.org, replayed for the same account with a fresh token. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36)
public final class AwardPolicyTest {
    static final String URL = "https://retroachievements.org/dorequest.php";
    static final String AWARD = "r=awardachievement&u=Raven&t=OLD&a=5501&h=0&m=abc&o=12&v=sig";

    @Test public void onlyTheSdksSoftcoreAwardsToTheServiceItself() {
        assertTrue(AwardPolicy.isSdkAward(URL, AWARD));
        assertFalse("Hardcore waits for RetroAchievements' approval",
                AwardPolicy.isSdkAward(URL, AWARD.replace("h=0", "h=1")));
        assertTrue(AwardPolicy.isSdkAward(URL, AWARD.replace("h=0", "h=1"), true));
        assertFalse(AwardPolicy.isSdkAward(URL, "r=login2&u=Raven&p=secret"));
        assertFalse(AwardPolicy.isSdkAward("http://retroachievements.org/dorequest.php", AWARD));
        assertFalse(AwardPolicy.isSdkAward("https://media.retroachievements.org/dorequest.php", AWARD));
        assertFalse(AwardPolicy.isSdkAward("https://retroachievements.org:8443/dorequest.php", AWARD));
        assertFalse(AwardPolicy.isSdkAward("https://x@retroachievements.org/dorequest.php", AWARD));
        assertFalse(AwardPolicy.isSdkAward("https://retroachievements.org/dorequest.php#x", AWARD));
        assertFalse(AwardPolicy.isSdkAward(URL, AWARD + "&" + "x".repeat(9000)));
    }

    @Test public void theUnlockAskedForAndWhetherTheServiceTookIt() {
        AwardPolicy.Key key = AwardPolicy.requestKey(AWARD);
        assertEquals(5501, key.id);
        assertFalse(key.hardcore);
        assertNull(AwardPolicy.requestKey("r=awardachievement&a=1&a=2&h=0"));
        assertNull(AwardPolicy.requestKey("r=awardachievement&a=0&h=0"));
        assertNull(AwardPolicy.requestKey("r=awardachievement&a=7&h=2"));
        assertNull(AwardPolicy.requestKey("r=login2&a=7&h=0"));
        assertTrue(AwardPolicy.accepted(200, bytes("{\"Success\":true,\"Score\":12}")));
        assertTrue("Already on the account from a lost reply", AwardPolicy.accepted(200,
                bytes("{\"Success\":false,\"Error\":\"User already has this achievement unlocked.\"}")));
        assertFalse(AwardPolicy.accepted(200, bytes("{\"Success\":false,\"Error\":\"Invalid token\"}")));
        assertFalse(AwardPolicy.accepted(503, bytes("{\"Success\":true}")));
        assertFalse(AwardPolicy.accepted(-1, new byte[0]));
        assertFalse(AwardPolicy.accepted(200, bytes("<html>")));
    }

    @Test public void aReplayOnlyRefreshesTheTokenOfTheSameAccount() {
        assertEquals("r=awardachievement&u=Raven&t=NEW%2B1&a=5501&h=0&m=abc&o=12&v=sig",
                AwardPolicy.authenticatedReplay(URL, AWARD, "raven", "NEW+1"));
        assertNull("Another account", AwardPolicy.authenticatedReplay(URL, AWARD, "Corbeau", "NEW"));
        assertNull(AwardPolicy.authenticatedReplay(URL, AWARD, "Raven", " "));
        assertNull(AwardPolicy.authenticatedReplay(URL, AWARD + "&t=TWICE", "Raven", "NEW"));
        assertNull(AwardPolicy.authenticatedReplay(URL, AWARD.replace("u=Raven", "u=%ZZ"), "Raven", "NEW"));
        assertNull(AwardPolicy.authenticatedReplay(URL, AWARD.replace("h=0", "h=1"), "Raven", "NEW"));
    }

    @Test public void oneEntryPerUnlockTheLatestAttempt() {
        String first = AWARD, retry = AWARD.replace("o=12", "o=40").replace("v=sig", "v=sig2");
        String other = AWARD.replace("a=5501", "a=5502");
        java.util.function.Function<String, String> key = post -> AwardPolicy.entryKey("Raven", post);
        List<String> kept = AwardPolicy.remember(Arrays.asList(first, other), retry, 8, key);
        assertEquals(Arrays.asList(other, retry), kept);
        assertNull("Full", AwardPolicy.remember(Collections.singletonList(other), first, 1, key));
        assertEquals(Collections.singletonList(other), AwardPolicy.acknowledge(Arrays.asList(first, other, retry), retry, key));
        assertEquals(Arrays.asList(other, retry), AwardPolicy.latest(Arrays.asList(first, other, retry), key));
        assertEquals("raven:5501:false", AwardPolicy.entryKey("RAVEN", AWARD));
    }

    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
}
