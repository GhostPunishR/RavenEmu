package com.ravenemu.android.retro

import com.ravenemu.shared.retro.RetroSync
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
        // As in rcheevos: an earlier attempt already put the unlock on the account.
        assertTrue(accepted(200, """{"Success":false,"Error":"User already has this achievement awarded."}"""))
        assertTrue(accepted(200, """{"Success":false,"Error":"User already has hardcore and regular achievements awarded."}"""))
        assertFalse(accepted(500, """{"Success":false,"Error":"User already has this achievement awarded."}"""))
        assertFalse(accepted(200, """{"Success":false,"Error":"user already has"}"""))
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
    }

    @Test fun `le user-agent donne des versions numeriques, celles du build`() {
        assertEquals("RavenEmu/0.1.0 (Android 14; arm64-v8a) RavenCore/0.7.1 rcheevos/12.5",
            RetroUserAgent.build("0.1.0 - Bêta", "14", "arm64-v8a", "0.7.1", "rcheevos/12.5"))
        // Sans bibliothèque native (tests), les parties moteur sont omises, jamais inventées.
        assertEquals("RavenEmu/1.2.3 (Android 15)", RetroUserAgent.build("v1.2.3", "15", "", "", ""))
        assertEquals("RavenEmu/0.0.0 (Android)", RetroUserAgent.build("Bêta", "", "", "", ""))
        // Un en-tête HTTP n'accepte que l'ASCII imprimable ; la partie système garde ses parenthèses.
        assertEquals("RavenEmu/0.1.0 (Android 16 Baklava; x86_64) RavenCore/0.7.1 rcheevos/12.5",
            RetroUserAgent.build("0.1.0", "16 (Baklava)", "x86_64\n", "0.7.1", "rcheevos/12.5"))
    }
}
