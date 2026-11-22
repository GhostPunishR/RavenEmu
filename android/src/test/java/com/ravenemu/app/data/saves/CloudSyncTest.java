package com.ravenemu.app.data.saves;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Two phones and one WebDAV folder in memory: what reaches the cloud, what
 * comes back, and that nothing is ever lost on the way. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36)
public final class CloudSyncTest {
    private static final String ZELDA = key('1'), MARIO = key('2');
    private static final List<String> LIBRARY = Arrays.asList(ZELDA, MARIO);
    private final FakeCloudStore cloud = new FakeCloudStore();
    private final FakeLocalSaves phone = new FakeLocalSaves(), tablet = new FakeLocalSaves();
    private final Map<String, CloudSync.Resolution> none = Collections.emptyMap();

    private static String key(char c) {
        char[] chars = new char[64];
        Arrays.fill(chars, c);
        return new String(chars);
    }

    private static byte[] save(String text) { return text.getBytes(StandardCharsets.UTF_8); }

    private CloudSyncState sync(FakeLocalSaves saves, CloudSyncState state, long now) {
        return new CloudSync(cloud, saves, saves == phone ? "Pixel 8" : "Galaxy Tab").run(state, LIBRARY, none, now);
    }

    @Test public void aSaveMadeOnOnePhoneReachesTheOther() throws Exception {
        phone.saves.put(ZELDA, save("zelda 1"));
        CloudSyncState first = sync(phone, CloudSyncState.empty("account"), 100);
        assertEquals("", first.lastError);
        assertEquals(1, first.lastUploaded);
        assertEquals(CloudSync.sha256(save("zelda 1")), first.base(ZELDA));
        CloudManifest list = CloudManifest.parse(cloud.files.get(CloudManifest.FILE));
        CloudManifest.Entry entry = list.get(ZELDA);
        assertEquals("Pixel 8", entry.device);
        assertEquals(100, entry.modified);
        assertArrayEquals(save("zelda 1"), cloud.files.get(CloudManifest.fileName(ZELDA, entry.sha256)));
        assertNull("No save, nothing sent", list.get(MARIO));

        CloudSyncState other = sync(tablet, CloudSyncState.empty("account"), 200);
        assertEquals(1, other.lastDownloaded);
        assertArrayEquals(save("zelda 1"), tablet.saves.get(ZELDA));
        assertTrue("Nothing to keep aside", tablet.keptAside.isEmpty());

        // Played on the tablet: the phone takes the new save and keeps its own aside.
        tablet.saves.put(ZELDA, save("zelda 2"));
        assertEquals(1, sync(tablet, other, 300).lastUploaded);
        CloudSyncState back = sync(phone, first, 400);
        assertEquals(1, back.lastDownloaded);
        assertArrayEquals(save("zelda 2"), phone.saves.get(ZELDA));
        assertArrayEquals(save("zelda 1"), phone.keptAside.get(ZELDA));
        assertTrue(back.conflicts.isEmpty());
        // In step: nothing moves.
        CloudSyncState quiet = sync(phone, back, 500);
        assertEquals(0, quiet.lastUploaded + quiet.lastDownloaded);
    }

    @Test public void savesChangedOnBothSidesWaitForThePlayerAndNeitherIsLost() throws Exception {
        phone.saves.put(ZELDA, save("start"));
        CloudSyncState phoneState = sync(phone, CloudSyncState.empty("a"), 1);
        CloudSyncState tabletState = sync(tablet, CloudSyncState.empty("a"), 2);
        phone.saves.put(ZELDA, save("phone progress"));
        tablet.saves.put(ZELDA, save("tablet progress"));
        tabletState = sync(tablet, tabletState, 3);
        phoneState = sync(phone, phoneState, 4);
        assertEquals(1, phoneState.conflicts.size());
        CloudSyncState.Conflict conflict = phoneState.conflicts.get(0);
        assertEquals(ZELDA, conflict.key);
        assertEquals("Galaxy Tab", conflict.cloudDevice);
        assertEquals(CloudSync.sha256(save("phone progress")), conflict.phoneSha256);
        assertArrayEquals("Untouched until the player chooses", save("phone progress"), phone.saves.get(ZELDA));
        // Asked again, the same conflict, still untouched.
        phoneState = sync(phone, phoneState, 5);
        assertEquals(1, phoneState.conflicts.size());

        // "Garder ce téléphone": the phone's goes up, the tablet's stays in the history.
        Map<String, CloudSync.Resolution> keep = new HashMap<>();
        keep.put(ZELDA, new CloudSync.Resolution(true, conflict.phoneSha256, conflict.cloudSha256));
        phoneState = new CloudSync(cloud, phone, "Pixel 8").run(phoneState, LIBRARY, keep, 6);
        assertTrue(phoneState.conflicts.isEmpty());
        assertEquals(1, phoneState.lastUploaded);
        CloudManifest.Entry entry = CloudManifest.parse(cloud.files.get(CloudManifest.FILE)).get(ZELDA);
        assertEquals(conflict.phoneSha256, entry.sha256);
        assertArrayEquals(save("tablet progress"),
                cloud.files.get(CloudManifest.fileName(ZELDA, entry.history.get(0))));
        // The tablet, unchanged since, simply follows.
        tabletState = sync(tablet, tabletState, 7);
        assertArrayEquals(save("phone progress"), tablet.saves.get(ZELDA));
        assertArrayEquals(save("tablet progress"), tablet.keptAside.get(ZELDA));
    }

    @Test public void takingTheCloudKeepsThePhonesSaveAside() {
        phone.saves.put(ZELDA, save("phone"));
        tablet.saves.put(ZELDA, save("tablet"));
        sync(tablet, CloudSyncState.empty("a"), 1);
        CloudSyncState state = sync(phone, CloudSyncState.empty("a"), 2);
        CloudSyncState.Conflict conflict = state.conflicts.get(0);
        Map<String, CloudSync.Resolution> take = new HashMap<>();
        take.put(ZELDA, new CloudSync.Resolution(false, conflict.phoneSha256, conflict.cloudSha256));
        state = new CloudSync(cloud, phone, "Pixel 8").run(state, LIBRARY, take, 3);
        assertTrue(state.conflicts.isEmpty());
        assertArrayEquals(save("tablet"), phone.saves.get(ZELDA));
        assertArrayEquals(save("phone"), phone.keptAside.get(ZELDA));
    }

    @Test public void anAnswerToAnOlderConflictIsNotApplied() {
        phone.saves.put(ZELDA, save("phone"));
        tablet.saves.put(ZELDA, save("tablet"));
        sync(tablet, CloudSyncState.empty("a"), 1);
        CloudSyncState state = sync(phone, CloudSyncState.empty("a"), 2);
        CloudSyncState.Conflict conflict = state.conflicts.get(0);
        // Played again before the answer is applied.
        phone.saves.put(ZELDA, save("phone, later"));
        Map<String, CloudSync.Resolution> take = new HashMap<>();
        take.put(ZELDA, new CloudSync.Resolution(false, conflict.phoneSha256, conflict.cloudSha256));
        state = new CloudSync(cloud, phone, "Pixel 8").run(state, LIBRARY, take, 3);
        assertArrayEquals(save("phone, later"), phone.saves.get(ZELDA));
        assertEquals(1, state.conflicts.size());
        assertEquals(CloudSync.sha256(save("phone, later")), state.conflicts.get(0).phoneSha256);
    }

    @Test public void aListChangedMeanwhileStartsTheSyncAgain() throws Exception {
        phone.saves.put(ZELDA, save("zelda"));
        tablet.saves.put(MARIO, save("mario"));
        cloud.beforeListPut = () -> {
            CloudSyncState tabletState = sync(tablet, CloudSyncState.empty("a"), 1);
            assertEquals(1, tabletState.lastUploaded);
        };
        CloudSyncState state = sync(phone, CloudSyncState.empty("a"), 2);
        assertEquals("", state.lastError);
        assertEquals(1, state.lastUploaded);
        assertEquals(1, state.lastDownloaded);
        CloudManifest list = CloudManifest.parse(cloud.files.get(CloudManifest.FILE));
        assertNotNull("The tablet's save survives", list.get(MARIO));
        assertNotNull(list.get(ZELDA));
        assertArrayEquals(save("mario"), phone.saves.get(MARIO));
        assertTrue("The second list is written over the first's ETag",
                cloud.log.stream().filter(line -> line.startsWith("PUT " + CloudManifest.FILE)).count() >= 2);
    }

    @Test public void aFileThatDoesNotMatchItsDigestReplacesNothing() throws Exception {
        tablet.saves.put(ZELDA, save("good"));
        sync(tablet, CloudSyncState.empty("a"), 1);
        String name = CloudManifest.fileName(ZELDA, CloudSync.sha256(save("good")));
        cloud.files.put(name, save("evil"));
        phone.saves.put(MARIO, save("mine"));
        CloudSyncState state = sync(phone, CloudSyncState.empty("a"), 2);
        assertTrue(state.lastError, state.lastError.contains("incomplète"));
        assertNull(phone.saves.get(ZELDA));
        assertNull("Not taken, not agreed", state.base(ZELDA));
        assertEquals("The other games still sync", 1, state.lastUploaded);
        assertNotNull(CloudManifest.parse(cloud.files.get(CloudManifest.FILE)).get(MARIO));
    }

    @Test public void aGameBeingPlayedOrChangedMeanwhileIsLeftForTheNextSync() {
        tablet.saves.put(ZELDA, save("cloud"));
        sync(tablet, CloudSyncState.empty("a"), 1);
        phone.inPlay.add(ZELDA);
        CloudSyncState state = sync(phone, CloudSyncState.empty("a"), 2);
        assertNull(phone.saves.get(ZELDA));
        assertNull(state.base(ZELDA));
        assertEquals(0, state.lastDownloaded);
        phone.inPlay.clear();
        state = sync(phone, state, 3);
        assertArrayEquals(save("cloud"), phone.saves.get(ZELDA));
    }

    @Test public void anUnreadableSaveIsSkippedAndTheOthersStillSync() throws Exception {
        phone.saves.put(ZELDA, save("zelda"));
        phone.saves.put(MARIO, save("mario"));
        phone.unreadable.add(ZELDA);
        CloudSyncState state = sync(phone, CloudSyncState.empty("a"), 1);
        assertTrue(state.lastError, state.lastError.contains("illisible"));
        assertEquals(1, state.lastUploaded);
        CloudManifest list = CloudManifest.parse(cloud.files.get(CloudManifest.FILE));
        assertNull(list.get(ZELDA));
        assertNotNull(list.get(MARIO));
    }

    @Test public void onlyTheOldestVersionsAreDeleted() throws Exception {
        CloudSyncState state = CloudSyncState.empty("a");
        for (int version = 1; version <= 4; version++) {
            phone.saves.put(ZELDA, save("v" + version));
            state = sync(phone, state, version);
        }
        assertTrue(cloud.files.containsKey(CloudManifest.fileName(ZELDA, CloudSync.sha256(save("v2")))));
        assertTrue(cloud.files.containsKey(CloudManifest.fileName(ZELDA, CloudSync.sha256(save("v3")))));
        assertFalse(cloud.files.containsKey(CloudManifest.fileName(ZELDA, CloudSync.sha256(save("v1")))));
        long deletes = cloud.log.stream().filter(line -> line.startsWith("DELETE")).count();
        assertEquals(1, deletes);
    }

    @Test public void theServersRefusalsAreToldInFrench() {
        phone.saves.put(ZELDA, save("zelda"));
        cloud.status = 401;
        assertEquals("Identifiant ou mot de passe refusé", sync(phone, CloudSyncState.empty("a"), 1).lastError);
        cloud.status = 507;
        assertEquals("Espace du cloud plein", sync(phone, CloudSyncState.empty("a"), 1).lastError);
        cloud.status = 0;
        cloud.missingFolder = true;
        assertEquals("Dossier introuvable : créez-le d'abord dans votre cloud",
                sync(phone, CloudSyncState.empty("a"), 1).lastError);
    }

    @Test public void signingInCreatesTheListOnlyInAnExistingFolder() throws Exception {
        assertNull(CloudSync.verify(cloud));
        assertEquals(0, CloudManifest.parse(cloud.files.get(CloudManifest.FILE)).size());
        assertEquals("PUT " + CloudManifest.FILE + " if-none-match", cloud.log.get(1));
        // Signing in again reads it, writes nothing.
        cloud.log.clear();
        assertNull(CloudSync.verify(cloud));
        assertEquals(Collections.singletonList("GET " + CloudManifest.FILE), cloud.log);
        FakeCloudStore missing = new FakeCloudStore();
        missing.missingFolder = true;
        assertEquals("Dossier introuvable : créez-le d'abord dans votre cloud", CloudSync.verify(missing));
        FakeCloudStore refused = new FakeCloudStore();
        refused.status = 401;
        assertEquals("Identifiant ou mot de passe refusé", CloudSync.verify(refused));
    }
}
