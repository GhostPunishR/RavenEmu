package com.ravenemu.android.wireless

import com.ravenemu.android.wireless.WirelessLinkPolicy.Decision
import com.ravenemu.android.wireless.WirelessLinkPolicy.Peer
import com.ravenemu.android.wireless.WirelessLinkProtocol.Bye
import com.ravenemu.android.wireless.WirelessLinkProtocol.Hello
import com.ravenemu.android.wireless.WirelessLinkProtocol.Pause
import com.ravenemu.android.wireless.WirelessLinkProtocol.Ping
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WirelessLinkProtocolTest {
    private fun input(vararg bytes: Int) = DataInputStream(ByteArrayInputStream(ByteArray(bytes.size) { bytes[it].toByte() }))

    @Test
    fun framesCarryTheirChannelAndPayload() {
        val core = byteArrayOf(3, 1, 0, 0, 0, 0x55, 0, 0, 0)
        val stream = WirelessLinkProtocol.frame(WirelessLinkProtocol.CORE, core) +
            WirelessLinkProtocol.frame(WirelessLinkProtocol.CONTROL, WirelessLinkProtocol.encode(Ping))
        val reader = DataInputStream(ByteArrayInputStream(stream))
        val first = WirelessLinkProtocol.readFrame(reader)
        assertEquals(WirelessLinkProtocol.CORE, first.channel)
        assertArrayEquals(core, first.payload)
        val second = WirelessLinkProtocol.readFrame(reader)
        assertEquals(WirelessLinkProtocol.CONTROL, second.channel)
        assertEquals(Ping, WirelessLinkProtocol.decode(second.payload))
        try {
            WirelessLinkProtocol.readFrame(reader)
            fail("fin de connexion attendue")
        } catch (_: EOFException) {
        }
    }

    @Test
    fun invalidFramesAreRefused() {
        for (bytes in listOf(intArrayOf(2, 1, 0), intArrayOf(0, 0))) {
            try {
                WirelessLinkProtocol.readFrame(input(*bytes))
                fail("trame refusée attendue")
            } catch (error: IOException) {
                assertFalse(error is EOFException)
            }
        }
        // Coupée au milieu : fin de connexion.
        try {
            WirelessLinkProtocol.readFrame(input(0, 4, 1, 2))
            fail("fin de connexion attendue")
        } catch (_: EOFException) {
        }
        for (size in listOf(0, 256)) {
            try {
                WirelessLinkProtocol.frame(WirelessLinkProtocol.CORE, ByteArray(size))
                fail("taille refusée attendue")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun controlMessagesSurviveTheTrip() {
        val hello = Hello(1, WirelessLinkProtocol.FAMILY_GBA, -0x1234_5678_9abc_def0L, true, "Pixel 7", "Pokémon Émeraude")
        for (message in listOf(hello, Pause(true), Pause(false), Ping, Bye(true), Bye(false))) {
            assertEquals(message, WirelessLinkProtocol.decode(WirelessLinkProtocol.encode(message)))
        }
    }

    @Test
    fun longNamesAreCutWithoutBreakingACharacter() {
        val name = "🎮".repeat(40)
        val decoded = WirelessLinkProtocol.decode(
            WirelessLinkProtocol.encode(Hello(1, 1, 7, false, name, "é".repeat(100))),
        ) as Hello
        assertEquals("🎮".repeat(15), decoded.device)
        assertEquals("é".repeat(30), decoded.game)
        assertTrue(WirelessLinkProtocol.encode(Hello(1, 1, 7, false, name, name)).size <= WirelessLinkProtocol.MAX_PAYLOAD)
    }

    @Test
    fun malformedControlMessagesAreIgnored() {
        val hello = WirelessLinkProtocol.encode(Hello(1, 1, 42, false, "A", "B"))
        assertNull(WirelessLinkProtocol.decode(hello.copyOf(hello.size - 1)))
        assertNull(WirelessLinkProtocol.decode(hello + 0))
        assertNull(WirelessLinkProtocol.decode(byteArrayOf()))
        assertNull(WirelessLinkProtocol.decode(byteArrayOf(9)))
        assertNull(WirelessLinkProtocol.decode(byteArrayOf(2)))
        assertNull(WirelessLinkProtocol.decode(byteArrayOf(3, 0)))
    }

    @Test
    fun theSmallerIdentifierIsPlayerZeroOnBothSides() {
        // Comparaison non signée : un identifiant « négatif » est grand.
        assertEquals(0, WirelessLinkProtocol.playerFor(5, -1))
        assertEquals(1, WirelessLinkProtocol.playerFor(-1, 5))
        assertEquals(0, WirelessLinkProtocol.playerFor(1, 2))
        assertEquals(1, WirelessLinkProtocol.playerFor(2, 1))
    }

    @Test
    fun aSinglePartnerLinksByItselfFromTheSmallerIdentifier() {
        val other = Peer(20, "Pixel 7")
        assertEquals(Decision.Connect(other), WirelessLinkPolicy.decide(10, listOf(other), emptySet(), null))
        // L'autre appelle : une seule connexion naît.
        assertEquals(Decision.Wait, WirelessLinkPolicy.decide(30, listOf(other), emptySet(), null))
        assertEquals(Decision.Wait, WirelessLinkPolicy.decide(10, emptyList(), emptySet(), null))
        assertEquals(Decision.Wait, WirelessLinkPolicy.decide(10, listOf(other), setOf(20L), null))
        // Sa propre annonce ne compte pas.
        assertEquals(Decision.Wait, WirelessLinkPolicy.decide(10, listOf(Peer(10, "Moi")), emptySet(), null))
    }

    @Test
    fun severalPartnersAskThePlayer() {
        val b = Peer(20, "Pixel 7")
        val a = Peer(30, "Galaxy S23")
        assertEquals(Decision.Choose(listOf(a, b)), WirelessLinkPolicy.decide(10, listOf(b, a), emptySet(), null))
        // Écarté : il ne reste qu'un candidat.
        assertEquals(Decision.Connect(b), WirelessLinkPolicy.decide(10, listOf(b, a), setOf(30L), null))
        // Le choix du joueur appelle, quel que soit l'identifiant.
        assertEquals(Decision.Connect(b), WirelessLinkPolicy.decide(40, listOf(b, a), emptySet(), 20))
        assertEquals(Decision.Wait, WirelessLinkPolicy.decide(40, listOf(a), emptySet(), 20))
    }

    @Test
    fun onlyAFreePhoneWithTheSameConsoleAcceptsACall() {
        val hello = Hello(1, WirelessLinkProtocol.FAMILY_GB, 99, false, "Pixel 7", "")
        assertTrue(WirelessLinkPolicy.accepts(hello, WirelessLinkProtocol.FAMILY_GB, emptySet(), busy = false))
        assertFalse(WirelessLinkPolicy.accepts(hello, WirelessLinkProtocol.FAMILY_GBA, emptySet(), busy = false))
        assertFalse(WirelessLinkPolicy.accepts(hello.copy(version = 2), WirelessLinkProtocol.FAMILY_GB, emptySet(), busy = false))
        assertFalse(WirelessLinkPolicy.accepts(hello, WirelessLinkProtocol.FAMILY_GB, setOf(99L), busy = false))
        assertFalse(WirelessLinkPolicy.accepts(hello, WirelessLinkProtocol.FAMILY_GB, emptySet(), busy = true))
    }

    @Test
    fun shortCodesTellTwoPhonesApart() {
        assertEquals("ABCD", WirelessLinkPolicy.shortCode(0xABCD_0000_0000_1234UL.toLong()))
        assertEquals("0001", WirelessLinkPolicy.shortCode(0x0001_FFFF_FFFF_FFFFL))
    }
}
