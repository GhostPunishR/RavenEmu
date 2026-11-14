package com.ravenemu.app.settings;

import com.ravenemu.app.platform.ClearSealer;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36, shadows = ClearSealer.class)
public final class AwardJournalTest {
    private static final String URL = AwardPolicyTest.URL, TYPE = "application/x-www-form-urlencoded";
    private Context context;
    private File file;

    @Before public void prepare() {
        context = RuntimeEnvironment.getApplication();
        file = new File(context.getNoBackupFilesDir(), AwardJournal.FILE);
        AwardJournal.clear(context);
        AchievementAccount.clear(context);
    }

    private static AwardJournal.Award award(String account, String post) {
        return new AwardJournal.Award(account, URL, post, TYPE);
    }

    @Test public void anAwardIsKeptSealedOutOfBackupsUntilAccepted() throws Exception {
        AwardJournal journal = AwardJournal.open(context);
        AwardJournal.Award award = award("Raven", AwardPolicyTest.AWARD);
        assertTrue(journal.remember(award));
        assertEquals(context.getNoBackupFilesDir(), file.getParentFile());
        String stored = new String(Files.readAllBytes(file.toPath()), StandardCharsets.ISO_8859_1);
        assertFalse("The token never in plain text", stored.contains("t=OLD"));
        List<AwardJournal.Award> pending = AwardJournal.open(context).forAccount("RAVEN");
        assertEquals(1, pending.size());
        assertEquals(award, pending.get(0));
        assertTrue(journal.forAccount("Corbeau").isEmpty());
        journal.acknowledge(award);
        assertTrue(journal.forAccount("Raven").isEmpty());
    }

    @Test public void onlyTheSdksAwardsAndOneAttemptPerUnlock() {
        AwardJournal journal = AwardJournal.open(context);
        assertFalse(journal.remember(award("Raven", "r=login2&u=Raven&p=secret")));
        assertFalse("Hardcore waits for approval",
                journal.remember(award("Raven", AwardPolicyTest.AWARD.replace("h=0", "h=1"))));
        assertFalse(journal.remember(award(" ", AwardPolicyTest.AWARD)));
        assertFalse(file.exists());
        String retry = AwardPolicyTest.AWARD.replace("o=12", "o=40");
        assertTrue(journal.remember(award("Raven", AwardPolicyTest.AWARD)));
        assertTrue(journal.remember(award("Raven", retry)));
        assertTrue(journal.remember(award("Corbeau", AwardPolicyTest.AWARD.replace("u=Raven", "u=Corbeau"))));
        List<AwardJournal.Award> pending = journal.forAccount("Raven");
        assertEquals("The latest attempt only", 1, pending.size());
        assertEquals(retry, pending.get(0).post);
        journal.acknowledge(award("Raven", AwardPolicyTest.AWARD));
        assertTrue("Every attempt of the unlock goes", journal.forAccount("Raven").isEmpty());
        assertEquals(1, journal.forAccount("Corbeau").size());
    }

    @Test public void signingOutRevokesOpenJournalsAndAForeignFileIsNoJournal() throws Exception {
        AwardJournal before = AwardJournal.open(context);
        assertTrue(before.remember(award("Raven", AwardPolicyTest.AWARD)));
        AwardJournal.clear(context);
        assertFalse(file.exists());
        assertFalse(before.isCurrent());
        assertFalse("A revoked journal never writes again", before.remember(award("Raven", AwardPolicyTest.AWARD)));
        assertFalse(file.exists());
        assertTrue(before.forAccount("Raven").isEmpty());
        AwardJournal after = AwardJournal.open(context);
        assertTrue(after.isCurrent());
        // The account file is sealed for another file: it never opens as a journal.
        new AchievementAccount("Raven", "TOKEN", "", "", 0, 0).save(context);
        Files.copy(new File(context.getNoBackupFilesDir(), AchievementAccount.FILE).toPath(), file.toPath());
        assertTrue(after.forAccount("Raven").isEmpty());
        try (FileOutputStream out = new FileOutputStream(file)) { out.write(new byte[]{1, 2, 3}); }
        assertTrue(after.forAccount("Raven").isEmpty());
    }

    /** One damaged entry no longer takes the others with it. */
    @Test public void aDamagedEntryIsSkippedAndTheOthersStay() throws Exception {
        AwardJournal journal = AwardJournal.open(context);
        AwardJournal.Award kept = award("Raven", AwardPolicyTest.AWARD);
        assertTrue(journal.remember(kept));
        String plain = "[{\"account\":\"Raven\",\"url\":\"" + URL + "\",\"post\":\"r=login2&u=Raven&p=x\","
                + "\"type\":\"" + TYPE + "\"},7,{\"account\":\"Raven\",\"url\":\"" + URL + "\",\"post\":\""
                + AwardPolicyTest.AWARD + "\",\"type\":\"" + TYPE + "\"}]";
        Files.write(file.toPath(), ClearSealer.sealedFor(plain.getBytes(StandardCharsets.UTF_8),
                "RavenEmu native RetroAchievements unsent awards v1".getBytes(StandardCharsets.UTF_8)));
        assertEquals("Only the SDK's award, the rest skipped", java.util.Collections.singletonList(kept),
                journal.forAccount("Raven"));
        String other = AwardPolicyTest.AWARD.replace("a=5501", "a=5502");
        assertTrue("Still kept after a damaged entry", journal.remember(award("Raven", other)));
        assertEquals(2, journal.forAccount("Raven").size());
    }

    /** A file that cannot be opened is set aside, byte for byte, and the
     * unlocks that follow are kept again; signing out removes both. */
    @Test public void anUnreadableJournalIsSetAsideAndTheNextUnlockIsKept() throws Exception {
        byte[] foreign = {1, 2, 3, 4};
        Files.write(file.toPath(), foreign);
        File aside = new File(context.getNoBackupFilesDir(), AwardJournal.UNREADABLE);
        AwardJournal journal = AwardJournal.open(context);
        assertTrue(journal.forAccount("Raven").isEmpty());
        AwardJournal.Award award = award("Raven", AwardPolicyTest.AWARD);
        assertTrue(journal.remember(award));
        assertArrayEquals("Set aside, never deleted", foreign, Files.readAllBytes(aside.toPath()));
        assertEquals(java.util.Collections.singletonList(award), journal.forAccount("Raven"));
        AwardJournal.clear(context);
        assertFalse(file.exists());
        assertFalse("Its requests carry the token: they go with the account", aside.exists());
    }

    /** A Keystore unavailable for now seals nothing: a good file is never moved. */
    @Test public void aKeystoreThatCannotSealNowMovesNothing() throws Exception {
        byte[] foreign = {9, 8, 7};
        Files.write(file.toPath(), foreign);
        ClearSealer.unavailable = true;
        try {
            assertFalse(AwardJournal.open(context).remember(award("Raven", AwardPolicyTest.AWARD)));
        } finally {
            ClearSealer.unavailable = false;
        }
        assertArrayEquals(foreign, Files.readAllBytes(file.toPath()));
        assertFalse(new File(context.getNoBackupFilesDir(), AwardJournal.UNREADABLE).exists());
    }
}
