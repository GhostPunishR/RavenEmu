package com.ravenemu.android

import com.ravenemu.shared.RetroSync
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RetroServicesTest {
    private val key = AwardSyncTracker.Key("42", false)

    @Test fun `un deblocage ne devient confirme qu'apres reponse positive`() {
        val sync = AwardSyncTracker()
        sync.local(key)
        assertEquals(RetroSync.LOCAL, sync.state(key, true))
        sync.pending(key)
        assertEquals(RetroSync.PENDING, sync.state(key, true))
        assertEquals(1, sync.pendingCount)
        sync.confirmed(key)
        assertEquals(RetroSync.CONFIRMED, sync.state(key, true))
        assertEquals(0, sync.pendingCount)
        sync.pending(key) // a late retry must not undo server confirmation
        assertEquals(RetroSync.CONFIRMED, sync.state(key, true))
    }

    @Test fun `un HTTP 200 seul ne confirme jamais un succes`() {
        fun accepted(status: Int, json: String) = AwardSyncTracker.accepted(status, json.toByteArray())
        assertTrue(accepted(200, """{"Success":true}"""))
        assertFalse(accepted(200, """{"Success":false,"Error":"Refused"}"""))
        assertFalse(accepted(500, """{"Success":true}"""))
        assertFalse(accepted(200, "<html>Error</html>"))
        assertFalse(accepted(-2, ""))
    }

    @Test fun `une reprise de journal reste visible meme avant le rechargement du jeu`() {
        val sync = AwardSyncTracker()
        sync.pending(key)
        assertEquals(RetroSync.PENDING, sync.state(key, false))
        assertEquals(RetroSync.NONE, sync.state(key.copy(hardcore = true), false))
        assertEquals(RetroSync.CONFIRMED, sync.state(key.copy(id = "43"), true))
        assertEquals(1, sync.pendingCount)
    }

    @Test fun `seuls identifiant et mode sont extraits de la demande du SDK`() {
        assertEquals(key, AwardSyncTracker.requestKey("r=awardachievement&u=test&t=private&a=42&h=0"))
        assertNull(AwardSyncTracker.requestKey("r=submitlbentry&a=42&h=0"))
        assertNull(AwardSyncTracker.requestKey("r=awardachievement&a=42&a=43&h=0"))
        assertNull(AwardSyncTracker.requestKey("r=awardachievement&a=42&h=2"))
        assertNull(AwardSyncTracker.requestKey("r=awardachievement&a=-1&h=0"))
    }

    @Test fun `le hardcore exige une approbation et interdit effectivement la restauration`() {
        assertFalse(HardcoreAvailability.APPROVED)
        assertFalse(RetroHardcorePolicy.enabled(true))
        assertFalse(RetroHardcorePolicy.enabled(false, approved = true))
        assertTrue(RetroHardcorePolicy.enabled(true, approved = true))
        RetroHardcorePolicy.requireStateLoadAllowed(false)
        assertFailsWith<IllegalStateException> { RetroHardcorePolicy.requireStateLoadAllowed(true) }
        assertTrue(Regex("RavenEmu/\\d+\\.\\d+\\.\\d+.* rcheevos/.*").matches(RetroHardcorePolicy.USER_AGENT))
    }
}
