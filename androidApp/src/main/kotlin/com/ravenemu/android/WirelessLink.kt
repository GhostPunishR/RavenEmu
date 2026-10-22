package com.ravenemu.android

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ravenemu.shared.GamePlatform
import com.ravenemu.shared.LoadedGameInfo
import com.ravenemu.shared.WirelessLinkChoice
import com.ravenemu.shared.WirelessLinkView
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Le câble link sans fil (ARCHITECTURE.md, « Câble link sans fil ») : relie tout seul le jeu en
 * cours à celui d'un autre téléphone du même réseau local, dès que le jeu se
 * sert de son port link.
 *
 * - **Recherche.** Tant que le jeu veut se relier ([AndroidGbSession.linkWanted]),
 *   le téléphone s'annonce et cherche les autres (DNS-SD). Le reste du temps,
 *   il n'annonce rien.
 * - **Choix.** Un seul partenaire compatible : la liaison se fait seule.
 *   Plusieurs : le joueur choisit ([choose]).
 * - **Liaison.** Une connexion TCP porte les messages de RavenCore. Le
 *   téléphone au plus petit identifiant est le joueur 0 (parent GBA).
 * - **Pause.** Le menu du jeu d'un côté arrête aussi l'autre ([partnerPaused]),
 *   tant que le câble sert.
 * - **Fin.** Le câble se débranche quand l'autre part, quand l'application
 *   passe en arrière-plan ou sur [unplug] ; il se rebranche tout seul ensuite.
 *
 * Tout l'état vit sur un seul fil ; les appels publics y sont transmis.
 */
internal class WirelessLink(
    context: Context,
    private val session: AndroidGbSession,
) : AutoCloseable {
    /** Ce que le lecteur affiche. */
    var view by mutableStateOf(WirelessLinkView())
        private set

    /** Câble relié : le chargement d'une sauvegarde d'état est interdit. */
    var linked by mutableStateOf(false)
        private set

    /** Le menu du jeu de l'autre est ouvert : la boucle d'émulation attend. */
    @Volatile var partnerPaused = false
        private set

    private val appContext = context.applicationContext
    private val ownId = SecureRandom().nextLong().let { if (it == 0L) 1L else it }
    private val device = Build.MODEL?.trim()?.takeIf { it.isNotEmpty() } ?: "Android"
    private val executor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "RavenEmu-Link").apply { isDaemon = true }
    }
    private val servicer = Servicer().apply { start() }
    private val wifiLock: WifiManager.WifiLock? = runCatching {
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            @Suppress("DEPRECATION")
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        appContext.getSystemService(WifiManager::class.java)
            ?.createWifiLock(mode, "RavenEmu:link")
            ?.apply { setReferenceCounted(false) }
    }.getOrNull()

    // Fil du câble uniquement.
    private var game: LoadedGameInfo? = null
    private var enabled = false
    private var visible = false
    private var paused = false
    private var pauseSent = false
    private var family = 0
    private var lastWantedNanos = 0L
    private var server: ServerSocket? = null
    private var discovery: WirelessLinkDiscovery? = null
    private val found = HashMap<String, WirelessLinkDiscovery.Found>()
    // Connexions qui attendent la présentation de l'autre.
    private val pending = ArrayList<WirelessLinkConnection>()
    private var connectingTo: Long? = null
    private var current: Linked? = null
    // Débranchés par le joueur : refusés jusqu'au prochain jeu.
    private val declined = HashSet<Long>()
    // Ceux qui nous ont refusés : plus appelés, mais encore acceptés.
    private val refusedBy = HashSet<Long>()
    // Partis ou injoignables : pas rappelés avant l'heure indiquée.
    private val cooling = HashMap<Long, Long>()
    // Écartés par « Plus tard » dans le choix.
    private val dismissed = HashSet<Long>()
    private var chosen: Long? = null
    private var choices: List<WirelessLinkPolicy.Peer> = emptyList()
    private var banner: String? = null
    private var ticker: ScheduledFuture<*>? = null
    private var closed = false

    private class Linked(
        val connection: WirelessLinkConnection,
        val link: NativeRemoteLink,
        val peer: WirelessLinkProtocol.Hello,
    )

    /** Le jeu affiché seul à l'écran (null : aucun), et le réglage. */
    fun setGame(game: LoadedGameInfo?, enabled: Boolean) = post {
        if (game !== this.game) {
            unlink(Ending.LEAVE)
            stopSearch()
            closePending()
            declined.clear()
            refusedBy.clear()
            cooling.clear()
            dismissed.clear()
            chosen = null
            banner = null
            lastWantedNanos = 0L
            this.game = game
            family = when (game?.platform) {
                GamePlatform.GB, GamePlatform.GBC -> WirelessLinkProtocol.FAMILY_GB
                GamePlatform.GBA -> WirelessLinkProtocol.FAMILY_GBA
                null -> 0
            }
        }
        this.enabled = enabled
        if (game != null && ticker == null) {
            ticker = executor.scheduleWithFixedDelay(::tick, 0, TICK_MILLIS, TimeUnit.MILLISECONDS)
        } else if (game == null) {
            ticker?.cancel(false)
            ticker = null
        }
        evaluate()
    }

    /** Le jeu est arrêté ici (menu du jeu, interruption) ou repart. */
    fun setPaused(paused: Boolean) = post {
        this.paused = paused
        sendPause()
    }

    /** L'application au premier plan ; en arrière-plan, le câble se débranche. */
    fun setVisible(visible: Boolean) = post {
        this.visible = visible
        evaluate()
    }

    /** Le partenaire choisi parmi plusieurs ; null : plus tard. */
    fun choose(id: Long?) = post {
        if (id == null) {
            dismissed += choices.map { it.id }
        } else {
            chosen = id
            dismissed -= id
        }
        evaluate()
    }

    /** Débrancher depuis le menu du jeu : ce partenaire n'est plus relié pour ce jeu. */
    fun unplug() = post {
        val linkedTo = current ?: return@post
        declined += linkedTo.peer.id
        unlink(Ending.REFUSE)
        evaluate()
    }

    override fun close() {
        post {
            closed = true
            ticker?.cancel(false)
            unlink(Ending.LEAVE)
            stopSearch()
            closePending()
            servicer.finish()
            executor.shutdown()
        }
    }

    private fun post(action: () -> Unit) {
        try {
            executor.execute {
                try {
                    action()
                } catch (error: Exception) {
                    Log.e(TAG, "Wireless link step failed", error)
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Fermé.
        }
    }

    private fun tick() {
        try {
            val now = System.nanoTime()
            if (session.linkWanted) lastWantedNanos = now
            cooling.values.removeAll { it <= now }
            // Une connexion muette après son ouverture n'est pas un RavenEmu ;
            // sa fermeture passe par onClosed, qui met l'appelé en attente.
            for (connection in pending.toList()) {
                if (connection.peer == null && now - connection.openedNanos > HELLO_TIMEOUT_NANOS) {
                    connection.close()
                }
            }
            sendPause()
            evaluate()
        } catch (error: Exception) {
            Log.e(TAG, "Wireless link tick failed", error)
        }
    }

    private val active: Boolean
        get() = !closed && game != null && enabled && visible && family != 0

    // Le jeu s'est servi de son port link il y a peu.
    private fun wanted(now: Long = System.nanoTime()) =
        lastWantedNanos != 0L && now - lastWantedNanos < SEARCH_GRACE_NANOS

    /** Reprend tout : recherche, choix, appel. */
    private fun evaluate() {
        if (!active) {
            if (current != null) {
                unlink(Ending.LEAVE)
                // Repris à son retour au premier plan, sans attendre.
                banner = null
            }
            stopSearch()
            closePending()
            choices = emptyList()
            publish()
            return
        }
        if (current != null) {
            publish()
            return
        }
        if (!wanted() && pending.isEmpty() && connectingTo == null) {
            stopSearch()
            choices = emptyList()
            publish()
            return
        }
        startSearch()
        val peers = found.values.distinctBy { it.id }.map { WirelessLinkPolicy.Peer(it.id, label(it)) }
        val avoided = declined + refusedBy + cooling.keys + dismissed
        choices = when (val decision = WirelessLinkPolicy.decide(ownId, peers, avoided, chosen)) {
            WirelessLinkPolicy.Decision.Wait -> emptyList()
            is WirelessLinkPolicy.Decision.Connect -> {
                found.values.firstOrNull { it.id == decision.peer.id }?.let(::connect)
                emptyList()
            }
            is WirelessLinkPolicy.Decision.Choose -> decision.peers
        }
        publish()
    }

    private fun startSearch() {
        if (server != null) return
        val socket = try {
            ServerSocket(0)
        } catch (error: IOException) {
            Log.w(TAG, "Wireless link server unavailable", error)
            return
        }
        server = socket
        thread(name = "RavenEmu-LinkAccept", isDaemon = true) {
            while (true) {
                val incoming = try {
                    socket.accept()
                } catch (_: IOException) {
                    break
                }
                post {
                    if (server !== socket || !active || current != null) incoming.close()
                    else open(incoming, target = null)
                }
            }
        }
        discovery = WirelessLinkDiscovery(appContext, executor, object : WirelessLinkDiscovery.Listener {
            override fun onPeer(name: String, found: WirelessLinkDiscovery.Found?) {
                if (found == null) {
                    val gone = this@WirelessLink.found.remove(name)
                    if (gone != null && gone.id == chosen) chosen = null
                } else {
                    this@WirelessLink.found[name] = found
                }
                evaluate()
            }
        }).also { it.start(family, ownId, device, socket.localPort) }
        // Annoncée une fois par jeu ; ensuite, le bandeau dit relié ou débranché.
        if (banner == null) banner = "Câble link : recherche d'un autre téléphone…"
    }

    private fun stopSearch() {
        discovery?.stop()
        discovery = null
        server?.let { runCatching { it.close() } }
        server = null
        found.clear()
        if (banner?.startsWith("Câble link : recherche") == true) banner = null
    }

    private fun closePending() {
        for (connection in pending) connection.leave(refused = false)
        pending.clear()
    }

    private fun connect(peer: WirelessLinkDiscovery.Found) {
        if (connectingTo != null || current != null) return
        if (pending.any { it.peer?.id == peer.id || it.outgoing }) return
        connectingTo = peer.id
        thread(name = "RavenEmu-LinkConnect", isDaemon = true) {
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(peer.host, peer.port), CONNECT_TIMEOUT_MILLIS)
                post {
                    connectingTo = null
                    if (!active || current != null) socket.close() else open(socket, target = peer.id)
                }
            } catch (error: IOException) {
                runCatching { socket.close() }
                post {
                    connectingTo = null
                    cool(peer.id)
                    evaluate()
                }
            }
        }
    }

    // [target] : le téléphone appelé ; null pour un appel reçu.
    private fun open(socket: Socket, target: Long?) {
        val connection = WirelessLinkConnection(socket, outgoing = target != null, connectionListener)
        connection.target = target
        pending += connection
        connection.start()
        connection.sendControl(
            WirelessLinkProtocol.Hello(
                version = WirelessLinkProtocol.VERSION,
                family = family,
                id = ownId,
                paused = paused && wanted(),
                device = device,
                game = game?.filename?.substringBeforeLast('.').orEmpty(),
            ),
        )
    }

    private val connectionListener = object : WirelessLinkConnection.Listener {
        override fun onControl(connection: WirelessLinkConnection, message: WirelessLinkProtocol.Control) =
            post { handle(connection, message) }

        // Fil de lecture : la console répond sans attendre l'image suivante.
        override fun onCore(connection: WirelessLinkConnection) = servicer.poke()

        override fun onClosed(connection: WirelessLinkConnection) = post {
            val waiting = pending.remove(connection)
            val linkedTo = current
            if (linkedTo != null && linkedTo.connection === connection) {
                unlink(Ending.CLOSED)
                cool(linkedTo.peer.id)
            } else if (waiting) {
                // Fermée avant la liaison : pas rappelé à chaque tour.
                val id = connection.peer?.id ?: connection.target
                if (id != null && id != linkedTo?.peer?.id) cool(id)
            }
            evaluate()
        }
    }

    private fun handle(connection: WirelessLinkConnection, message: WirelessLinkProtocol.Control) {
        val linkedTo = current
        when (message) {
            is WirelessLinkProtocol.Hello -> hello(connection, message)
            is WirelessLinkProtocol.Pause -> if (linkedTo?.connection === connection) {
                partnerPaused = message.paused
                publish()
            }
            is WirelessLinkProtocol.Bye -> {
                val peer = connection.peer?.id
                if (peer != null && message.refused) refusedBy += peer
                if (linkedTo?.connection === connection) {
                    unlink(Ending.CLOSED)
                    if (peer != null) cool(peer)
                } else {
                    pending -= connection
                    connection.close()
                    // Occupé ailleurs : pas rappelé tout de suite.
                    if (peer != null && linkedTo?.peer?.id != peer) cool(peer)
                }
                evaluate()
            }
            WirelessLinkProtocol.Ping -> Unit
        }
    }

    private fun hello(connection: WirelessLinkConnection, hello: WirelessLinkProtocol.Hello) {
        if (connection !in pending || connection.peer != null) return
        connection.peer = hello
        val compatible = hello.version == WirelessLinkProtocol.VERSION && hello.family == family
        if (!active || !WirelessLinkPolicy.accepts(hello, family, declined, busy = false)) {
            pending -= connection
            connection.leave(refused = !compatible || hello.id in declined)
            return
        }
        val linkedTo = current
        if (linkedTo != null) {
            if (linkedTo.peer.id != hello.id || !opensWinner(connection, hello)) {
                // Occupé, ou seconde connexion vers le même téléphone : les deux
                // côtés gardent celle qu'a ouverte le plus petit identifiant.
                pending -= connection
                connection.leave(refused = false)
                return
            }
            unlink(Ending.LEAVE, quiet = true)
        }
        link(connection, hello)
    }

    private fun opensWinner(connection: WirelessLinkConnection, hello: WirelessLinkProtocol.Hello): Boolean {
        val opener = if (connection.outgoing) ownId else hello.id
        val first = if (WirelessLinkProtocol.playerFor(ownId, hello.id) == 0) ownId else hello.id
        return opener == first
    }

    private fun link(connection: WirelessLinkConnection, hello: WirelessLinkProtocol.Hello) {
        val player = WirelessLinkProtocol.playerFor(ownId, hello.id)
        val native = session.attachRemoteLink(player) { message -> connection.sendCore(message) }
        pending -= connection
        if (native == null) {
            connection.leave(refused = true)
            return
        }
        current = Linked(connection, native, hello)
        native.setConnected(true)
        connection.bindCore { message -> native.receive(message) }
        servicer.poke()
        partnerPaused = hello.paused
        pauseSent = false
        sendPause()
        chosen = null
        stopSearch()
        closePending()
        runCatching { wifiLock?.acquire() }
        linked = true
        banner = "Câble relié à ${hello.device}"
        Log.i(TAG, "Wireless link cable plugged (player $player)")
    }

    private enum class Ending { LEAVE, REFUSE, CLOSED }

    private fun unlink(ending: Ending, quiet: Boolean = false) {
        val linkedTo = current ?: return
        current = null
        // Débrancher d'abord : une image qui attend l'autre console repart.
        session.detachRemoteLink(linkedTo.link)
        when (ending) {
            Ending.LEAVE -> linkedTo.connection.leave(refused = false)
            Ending.REFUSE -> linkedTo.connection.leave(refused = true)
            Ending.CLOSED -> linkedTo.connection.close()
        }
        partnerPaused = false
        pauseSent = false
        runCatching { wifiLock?.release() }
        linked = false
        if (!quiet) banner = "Câble débranché"
        Log.i(TAG, "Wireless link cable unplugged ($ending)")
    }

    // Une pause n'arrête l'autre que si le câble sert : sinon chacun joue de son côté.
    private fun sendPause() {
        val linkedTo = current ?: return
        val wanted = paused && wanted()
        if (wanted == pauseSent) return
        pauseSent = wanted
        linkedTo.connection.sendControl(WirelessLinkProtocol.Pause(wanted))
    }

    private fun cool(id: Long) {
        cooling[id] = System.nanoTime() + COOLDOWN_NANOS
    }

    private fun label(peer: WirelessLinkDiscovery.Found) = "${peer.device} · ${WirelessLinkPolicy.shortCode(peer.id)}"

    private fun publish() {
        val linkedTo = current
        view = WirelessLinkView(
            banner = banner,
            choices = choices.map { WirelessLinkChoice(it.id, it.device) },
            ownLabel = "$device · ${WirelessLinkPolicy.shortCode(ownId)}",
            partner = linkedTo?.peer?.let { peer ->
                if (peer.game.isBlank()) peer.device else "${peer.device} (${peer.game})"
            },
            partnerPaused = partnerPaused,
        )
    }

    /**
     * Fait lire à la console les messages arrivés entre deux images, ou
     * pendant une pause : l'autre console n'attend jamais l'image suivante.
     */
    private inner class Servicer : Thread("RavenEmu-LinkService") {
        private val signal = Semaphore(0)
        @Volatile private var running = true

        init {
            isDaemon = true
        }

        fun poke() = signal.release()

        fun finish() {
            running = false
            signal.release()
        }

        override fun run() {
            while (running) {
                signal.acquireUninterruptibly()
                signal.drainPermits()
                if (!running) break
                try {
                    session.serviceRemoteLink()
                } catch (error: Exception) {
                    Log.w(TAG, "Wireless link service failed", error)
                }
            }
        }
    }

    private companion object {
        const val TAG = "RavenEmuLink"
        const val TICK_MILLIS = 500L
        const val CONNECT_TIMEOUT_MILLIS = 3_000
        const val HELLO_TIMEOUT_NANOS = 5_000_000_000L
        // Le cœur garde le signal trois secondes ; la recherche, un peu plus.
        const val SEARCH_GRACE_NANOS = 10_000_000_000L
        const val COOLDOWN_NANOS = 3_000_000_000L
    }
}
