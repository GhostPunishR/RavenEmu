package com.ravenemu.android.wireless

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Une connexion TCP du câble sans fil, vers un autre téléphone.
 *
 * Un fil lit les trames et un autre les écrit : [sendCore] et [sendControl]
 * ne bloquent jamais, car RavenCore les appelle depuis le fil d'émulation.
 * Sans rien à envoyer, l'écrivain envoie un [WirelessLinkProtocol.Ping] chaque
 * seconde ; sans rien recevoir pendant [TIMEOUT_MILLIS], la connexion se ferme.
 *
 * Les messages de la console reçus avant [bindCore] attendent dans l'ordre :
 * l'autre téléphone peut brancher son câble avant que celui-ci ne soit prêt.
 */
internal class WirelessLinkConnection(
    private val socket: Socket,
    /** Cette connexion a été ouverte par ce téléphone. */
    val outgoing: Boolean,
    private val listener: Listener,
) : AutoCloseable {
    interface Listener {
        /** Fil de lecture : un message de contrôle, sauf les battements de cœur. */
        fun onControl(connection: WirelessLinkConnection, message: WirelessLinkProtocol.Control)
        /** Fil de lecture : un message de la console arrivé après [bindCore]. */
        fun onCore(connection: WirelessLinkConnection)
        /** Une seule fois, depuis n'importe quel fil. */
        fun onClosed(connection: WirelessLinkConnection)
    }

    private val queue = LinkedBlockingQueue<ByteArray>(QUEUE_LIMIT)
    private val closed = AtomicBoolean(false)
    private val earlyCore = ArrayList<ByteArray>()
    private var coreReceiver: ((ByteArray) -> Unit)? = null
    @Volatile private var leftNanos = 0L

    /** Heure de création ([System.nanoTime]), pour abandonner une présentation trop lente. */
    val openedNanos: Long = System.nanoTime()

    /** La présentation de l'autre téléphone, une fois reçue. */
    @Volatile var peer: WirelessLinkProtocol.Hello? = null

    /** Le téléphone appelé, connu avant sa présentation (connexion sortante). */
    @Volatile var target: Long? = null

    val isClosed: Boolean get() = closed.get()

    fun start() {
        socket.tcpNoDelay = true
        socket.soTimeout = TIMEOUT_MILLIS
        Thread(::readLoop, "RavenEmu-LinkRead").apply { isDaemon = true }.start()
        Thread(::writeLoop, "RavenEmu-LinkWrite").apply { isDaemon = true }.start()
    }

    fun sendCore(message: ByteArray) = enqueue(WirelessLinkProtocol.frame(WirelessLinkProtocol.CORE, message))

    fun sendControl(message: WirelessLinkProtocol.Control) =
        enqueue(WirelessLinkProtocol.frame(WirelessLinkProtocol.CONTROL, WirelessLinkProtocol.encode(message)))

    /** Les messages de la console vont désormais à [receiver], ceux en attente d'abord. */
    fun bindCore(receiver: (ByteArray) -> Unit) = synchronized(earlyCore) {
        for (message in earlyCore) receiver(message)
        earlyCore.clear()
        coreReceiver = receiver
    }

    /** Dit au revoir, puis ferme une fois l'adieu parti. */
    fun leave(refused: Boolean) {
        leftNanos = System.nanoTime()
        sendControl(WirelessLinkProtocol.Bye(refused))
        if (!queue.offer(END)) close()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { socket.close() }
        queue.clear()
        queue.offer(END)
        listener.onClosed(this)
    }

    private fun enqueue(frame: ByteArray) {
        if (closed.get()) return
        // Une file pleine : l'autre téléphone ne lit plus.
        if (!queue.offer(frame)) close()
    }

    private fun readLoop() {
        try {
            val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
            while (!closed.get()) {
                val frame = WirelessLinkProtocol.readFrame(input)
                // Parti : l'autre a eu le temps de lire l'adieu.
                if (leftNanos != 0L && System.nanoTime() - leftNanos > LEAVE_GRACE_NANOS) break
                if (frame.channel == WirelessLinkProtocol.CORE) {
                    deliverCore(frame.payload)
                } else {
                    val message = WirelessLinkProtocol.decode(frame.payload) ?: continue
                    if (message != WirelessLinkProtocol.Ping) listener.onControl(this, message)
                }
            }
        } catch (_: Exception) {
            // Fin de connexion, délai dépassé ou trame invalide : même issue.
        } finally {
            close()
        }
    }

    private fun deliverCore(message: ByteArray) {
        val bound = synchronized(earlyCore) {
            val receiver = coreReceiver
            if (receiver == null) {
                // Rien de légitime n'envoie autant avant la présentation.
                if (earlyCore.size >= QUEUE_LIMIT) throw IllegalStateException("Trop de messages avant la présentation.")
                earlyCore += message
                false
            } else {
                receiver(message)
                true
            }
        }
        if (bound) listener.onCore(this)
    }

    private fun writeLoop() {
        try {
            val output = BufferedOutputStream(socket.getOutputStream())
            val ping = WirelessLinkProtocol.frame(
                WirelessLinkProtocol.CONTROL, WirelessLinkProtocol.encode(WirelessLinkProtocol.Ping),
            )
            while (!closed.get()) {
                var next: ByteArray? = queue.poll(PING_MILLIS, TimeUnit.MILLISECONDS) ?: ping
                while (next != null && next !== END) {
                    output.write(next)
                    next = queue.poll()
                }
                output.flush()
                if (next === END) {
                    // Après l'adieu, la lecture continue jusqu'à ce que l'autre
                    // ferme : fermer avec des octets non lus effacerait l'adieu.
                    runCatching { socket.shutdownOutput() }
                    return
                }
            }
        } catch (_: Exception) {
            close()
        }
    }

    companion object {
        const val PING_MILLIS = 1_000L
        const val TIMEOUT_MILLIS = 5_000
        private const val QUEUE_LIMIT = 256
        private const val LEAVE_GRACE_NANOS = 2_000_000_000L
        private val END = ByteArray(0)
    }
}
