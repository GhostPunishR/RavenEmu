package com.ravenemu.app.data.saves;

import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36)
public final class CloudManifestTest {
    private static final String GAME = repeat('1');
    private static final String V1 = repeat('a'), V2 = repeat('b'), V3 = repeat('c'), V4 = repeat('d');

    private static String repeat(char c) {
        char[] chars = new char[64];
        Arrays.fill(chars, c);
        return new String(chars);
    }

    @Test public void eachVersionHasItsOwnFileAndTheTwoBeforeItStay() throws Exception {
        CloudManifest manifest = CloudManifest.empty();
        assertEquals(Collections.emptyList(), manifest.put(GAME, V1, 8192, 10, "Pixel 8"));
        assertEquals(Collections.emptyList(), manifest.put(GAME, V2, 8192, 20, "Pixel 8"));
        assertEquals(Collections.emptyList(), manifest.put(GAME, V3, 8192, 30, "Galaxy S24"));
        // The fourth version pushes the first out: its file can go.
        List<String> dropped = manifest.put(GAME, V4, 8192, 40, "Galaxy S24");
        assertEquals(Collections.singletonList(CloudManifest.fileName(GAME, V1)), dropped);
        CloudManifest.Entry entry = CloudManifest.parse(manifest.toBytes()).get(GAME);
        assertEquals(V4, entry.sha256);
        assertEquals(40, entry.modified);
        assertEquals("Galaxy S24", entry.device);
        assertEquals(Arrays.asList(V3.substring(0, 16), V2.substring(0, 16)), entry.history);
        assertEquals("ravenemu-" + GAME + "-" + V4.substring(0, 16) + ".srm", CloudManifest.fileName(GAME, V4));
    }

    @Test public void sendingTheSameSaveAgainKeepsItsHistory() {
        CloudManifest manifest = CloudManifest.empty();
        manifest.put(GAME, V1, 100, 1, "A");
        manifest.put(GAME, V2, 100, 2, "A");
        assertEquals(Collections.emptyList(), manifest.put(GAME, V2, 100, 3, "A"));
        assertEquals(Collections.singletonList(V1.substring(0, 16)), manifest.get(GAME).history);
        // Going back to an older version takes it out of the history.
        manifest.put(GAME, V1, 100, 4, "A");
        assertEquals(Collections.singletonList(V2.substring(0, 16)), manifest.get(GAME).history);
    }

    @Test public void fieldsOfALaterVersionAreKept() throws Exception {
        JSONObject json = new JSONObject().put("format", 1).put("future", "kept").put("saves",
                new JSONObject().put(GAME, new JSONObject().put("sha256", V1).put("size", 10).put("modified", 5)
                        .put("device", "A").put("history", new org.json.JSONArray()).put("extra", 7)));
        CloudManifest manifest = CloudManifest.parse(json.toString().getBytes(StandardCharsets.UTF_8));
        manifest.put(GAME, V2, 10, 6, "B");
        JSONObject written = new JSONObject(new String(manifest.toBytes(), StandardCharsets.UTF_8));
        assertEquals("kept", written.getString("future"));
        assertEquals(7, written.getJSONObject("saves").getJSONObject(GAME).getInt("extra"));
    }

    @Test public void aDamagedOrNewerListIsRefusedWhole() throws Exception {
        String good = "{\"format\":1,\"saves\":{\"" + GAME + "\":{\"sha256\":\"" + V1 + "\",\"size\":10}}}";
        assertNotNull(CloudManifest.parse(good.getBytes(StandardCharsets.UTF_8)).get(GAME));
        String[] bad = {"", "[]", "{\"format\":1}", "{\"format\":0,\"saves\":{}}",
                good.replace(GAME, "../" + GAME.substring(3)), good.replace("\"size\":10", "\"size\":0"),
                good.replace("\"size\":10", "\"size\":" + (SaveRepository.BATTERY_LIMIT_BYTES + 1)),
                good.replace(V1, "zz" + V1.substring(2)),
                good.replace("\"size\":10", "\"size\":10,\"history\":[\"../x\"]")};
        for (String body : bad) {
            try {
                CloudManifest.parse(body.getBytes(StandardCharsets.UTF_8));
                fail("Accepted: " + body);
            } catch (IOException refused) {
                assertTrue(refused.getMessage(), refused.getMessage().contains("illisible"));
            }
        }
        try {
            CloudManifest.parse("{\"format\":2,\"saves\":{}}".getBytes(StandardCharsets.UTF_8));
            fail("A newer format was accepted");
        } catch (IOException newer) {
            assertTrue(newer.getMessage().contains("plus récente"));
        }
    }

    @Test public void onlyRavenEmuNamesAndDigestsMakeAFileName() {
        for (String[] pair : new String[][]{{"../x", V1}, {GAME, "../../etc"}, {V1.toUpperCase(), V2}}) {
            try {
                CloudManifest.fileName(pair[0], pair[1]);
                fail("Accepted " + pair[0] + " " + pair[1]);
            } catch (IllegalArgumentException refused) {
                // Expected.
            }
        }
    }
}
