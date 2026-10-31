package com.ravenemu.app.platform;

import org.junit.Test;
import static org.junit.Assert.*;

/** The beta's RetroUserAgent rules, on the native preview's parts. */
public final class RetroUserAgentTest {
    @Test public void productSystemEngineAndSdk() {
        assertEquals("RavenEmu/0.1 (Android 15; arm64-v8a) RavenCore/0.14.0 rcheevos/12.5",
                RetroUserAgent.build("0.1-dev", "15", "arm64-v8a", "0.14.0", "rcheevos/12.5"));
    }

    @Test public void theServiceReadsANumericVersion() {
        assertEquals("RavenEmu/0.2.0 (Android 14; x86_64)",
                RetroUserAgent.build("0.2.0 - Bêta", "14", "x86_64", "", ""));
        assertTrue(RetroUserAgent.build("dev", "14", "", "", "").startsWith("RavenEmu/0.0.0 (Android 14)"));
    }

    @Test public void nothingBreaksTheHeader() {
        String agent = RetroUserAgent.build("1.2.3", "15 (QPR1)\n", "arm64\tv8a", "0.14.0\r\nX: y", "rcheevos/12.5");
        assertEquals("RavenEmu/1.2.3 (Android 15 QPR1; arm64 v8a) RavenCore/0.14.0 X: y rcheevos/12.5", agent);
        for (char c : agent.toCharArray()) assertTrue("printable ASCII: " + (int)c, c >= 0x20 && c <= 0x7e);
    }
}
