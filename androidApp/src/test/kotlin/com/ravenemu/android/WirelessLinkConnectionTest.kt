package com.ravenemu.android

import com.ravenemu.android.WirelessLinkProtocol.Bye
import com.ravenemu.android.WirelessLinkProtocol.Control
import com.ravenemu.android.WirelessLinkProtocol.Hello
import com.ravenemu.android.WirelessLinkProtocol.Pause
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Deux connexions reliées par la boucle locale, comme deux téléphones. */
class WirelessLinkConnectionTest {
    private class Side : WirelessLinkConnection.Listener {
        val controls = LinkedBlockingQueue<Control>()
        val cores = AtomicInteger()
        val closed = CountDownLatch(1)
        val closings = AtomicInteger()

        override fun onControl(connection: WirelessLinkConnection, message: Control) {
            controls.add(message)
        }
        override fun onCore(connection: WirelessLinkConnection) {
            cores.incrementAndGet()
        }
        override fun onClosed(connection: WirelessLinkConnection) {
            closings.incrementAndGet()
            closed.countDown()
        }
    }

    private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    private val callerSide = Side()
    private val answerSide = Side()
    private lateinit var caller: WirelessLinkConnection
    private lateinit var answer: WirelessLinkConnection

    private fun connect() {
        val socket = Socket(InetAddress.getLoopbackAddress(), server.localPort)
        caller = WirelessLinkConnection(socket, outgoing = true, listener = callerSide)
        answer = WirelessLinkConnection(server.accept(), outgoing = false, listener = answerSide)
        caller.start()
        answer.start()
    }

    @After
    fun tearDown() {
        if (::caller.isInitialized) caller.close()
        if (::answer.isInitialized) answer.close()
        server.close()
    }

    @Test
    fun controlMessagesArriveInOrder() {
        connect()
        val hello = Hello(1, WirelessLinkProtocol.FAMILY_GB, 7, false, "Pixel 7", "Pokémon Rouge")
        caller.sendControl(hello)
        caller.sendControl(Pause(true))
        assertEquals(hello, answerSide.controls.poll(2, TimeUnit.SECONDS))
        assertEquals(Pause(true), answerSide.controls.poll(2, TimeUnit.SECONDS))
    }

    @Test
    fun consoleMessagesWaitForTheCableThenKeepTheirOrder() {
        connect()
        val received = LinkedBlockingQueue<ByteArray>()
        val messages = (1..5).map { byteArrayOf(2, it.toByte()) }
        for (message in messages) caller.sendCore(message)
        caller.sendControl(Pause(false))
        // La pause, envoyée après, prouve que les messages sont arrivés.
        assertEquals(Pause(false), answerSide.controls.poll(2, TimeUnit.SECONDS))
        assertEquals(0, answerSide.cores.get())
        answer.bindCore { received.add(it) }
        caller.sendCore(byteArrayOf(2, 6))
        for (expected in messages + listOf(byteArrayOf(2, 6))) {
            assertArrayEquals(expected, received.poll(2, TimeUnit.SECONDS))
        }
        caller.sendControl(Pause(true))
        assertEquals(Pause(true), answerSide.controls.poll(2, TimeUnit.SECONDS))
        assertEquals(1, answerSide.cores.get())
    }

    @Test
    fun aGoodbyeReachesTheOtherSideBeforeTheConnectionEnds() {
        connect()
        caller.leave(refused = true)
        assertEquals(Bye(true), answerSide.controls.poll(2, TimeUnit.SECONDS))
        // L'autre ferme en réponse : les deux côtés finissent, une fois chacun.
        answer.close()
        assertTrue(callerSide.closed.await(3, TimeUnit.SECONDS))
        assertTrue(answerSide.closed.await(3, TimeUnit.SECONDS))
        caller.close()
        assertEquals(1, callerSide.closings.get())
        assertEquals(1, answerSide.closings.get())
    }

    @Test
    fun aVanishedPartnerClosesTheConnection() {
        connect()
        answer.close()
        assertTrue(callerSide.closed.await(3, TimeUnit.SECONDS))
        assertTrue(caller.isClosed)
        // Plus rien ne part, sans erreur.
        caller.sendCore(byteArrayOf(1, 1, 1))
    }

    @Test
    fun anInvalidFrameEndsTheConnection() {
        val socket = Socket(InetAddress.getLoopbackAddress(), server.localPort)
        answer = WirelessLinkConnection(server.accept(), outgoing = false, listener = answerSide)
        answer.start()
        socket.getOutputStream().apply {
            write(byteArrayOf(9, 1, 0))
            flush()
        }
        assertTrue(answerSide.closed.await(3, TimeUnit.SECONDS))
        socket.close()
    }
}
