package com.ravenemu.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Offline unlock journal rules, ported from RavenEmu 0.8.1 with the RavenEmu 0.8.1 test cases. */
class AwardPolicyTest {
    private val url = "https://retroachievements.org/dorequest.php"
    private val award = "r=awardachievement&u=test&t=secret&a=7&h=0&m=hash"

    @Test fun onlySdkAwardsSentToRetroAchievementsAreJournaled() {
        assertTrue(AwardPolicy.isSdkAward(url, award))
        assertFalse(AwardPolicy.isSdkAward(url, "r=login2&u=test"))
        assertFalse(AwardPolicy.isSdkAward("https://other.example/dorequest.php", award))
        assertFalse(AwardPolicy.isSdkAward("http://retroachievements.org/dorequest.php", award))
        assertFalse(AwardPolicy.isSdkAward(url, award + "&extra=" + "x".repeat(8192)))
    }

    @Test fun hardcoreAwardsWaitForRetroAchievementsApproval() {
        val hardcore = award.replace("h=0", "h=1")
        assertFalse(AwardPolicy.isSdkAward(url, hardcore, allowHardcore = false))
        // Once approved, the same journal also keeps Hardcore unlocks.
        assertTrue(AwardPolicy.isSdkAward(url, hardcore, allowHardcore = true))
    }

    @Test fun replayRefreshesOnlyTheTokenForTheSameAccount() {
        val post = "r=awardachievement&u=Player+One&t=revoked&a=7&h=0&m=hash&v=sdk-signature&o=5"
        val refreshed = AwardPolicy.authenticatedReplay(url, post, "player one", "new+& token")
        assertEquals(post.replace("t=revoked", "t=new%2B%26+token"), refreshed)
        assertNull(AwardPolicy.authenticatedReplay(url, post, "someone else", "new"))
        assertNull(AwardPolicy.authenticatedReplay(url, post + "&t=second", "Player One", "new"))
        assertNull(AwardPolicy.authenticatedReplay(url, post.replace("h=0", "h=1"), "Player One", "new"))
    }

    @Test fun signOutRevokesLateWritesButAllowsANewSession() {
        val guard = AwardJournalGuard()
        val oldClient = guard.open()
        var journal = listOf("first", "second")
        val lateAcknowledgement = journal - "first"
        guard.clear { journal = emptyList() }
        oldClient.access(Unit) { journal = lateAcknowledgement }
        oldClient.access(Unit) { journal = journal + "late unlock" }
        assertTrue(journal.isEmpty())
        guard.open().access(Unit) { journal = journal + "new session" }
        assertEquals(listOf("new session"), journal)
    }

    @Test fun clientsOfTheSameFileSerializeTheirTransactions() {
        val first = AwardJournalGuard.forFile("transaction-test")
        val second = AwardJournalGuard.forFile("transaction-test")
        assertSame(first, second)
        val leases = listOf(first.open(), second.open())
        val start = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        var value = 0
        try {
            val futures = leases.map { lease ->
                workers.submit {
                    check(start.await(5, TimeUnit.SECONDS))
                    repeat(1000) { lease.access(Unit) { val read = value; Thread.yield(); value = read + 1 } }
                }
            }
            start.countDown()
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
            assertEquals(2000, value)
        } finally { workers.shutdownNow() }
    }

    @Test fun failedAwardsRetryWithBackoffAndSentOnesAreNotRepeated() {
        val replay = AwardReplay(listOf("a", "b"))
        val attempts = mutableListOf<String>()
        assertEquals(5000L, replay.attempt({ true }) { attempts += it; it == "a" })
        assertEquals(10000L, replay.attempt({ true }) { attempts += it; false })
        assertNull(replay.attempt({ true }) { attempts += it; true })
        assertEquals(listOf("a", "b", "b", "b"), attempts)
        assertNull(replay.attempt({ true }) { fail("already acknowledged"); false })
    }

    @Test fun revokedReplayStopsAndTheDelayStaysBounded() {
        val replay = AwardReplay(listOf("a"))
        repeat(12) { assertTrue(replay.attempt({ true }) { false }!! <= 120000L) }
        assertNull(replay.attempt({ false }) { fail("signed out"); false })
    }
}
