package com.ravenemu.android.wireless

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/**
 * Annonce de ce téléphone et recherche des autres sur le réseau local
 * (DNS-SD, par le service NsdManager d'Android), le temps d'une recherche.
 *
 * L'annonce porte la version du protocole, la famille de consoles,
 * l'identifiant tiré au lancement de l'application et le modèle du
 * téléphone ; jamais le jeu. Tous les rappels passent par [executor].
 */
internal class WirelessLinkDiscovery(
    context: Context,
    private val executor: Executor,
    private val listener: Listener,
) {
    data class Found(val id: Long, val device: String, val host: InetAddress, val port: Int)

    interface Listener {
        /** Sur [executor] : un téléphone compatible trouvé, ou parti ([found] nul). */
        fun onPeer(name: String, found: Found?)
    }

    private val nsd = context.getSystemService(NsdManager::class.java)
    private var family = 0
    private var ownId = 0L
    private var ownName: String? = null
    private var registered = false
    private var discovering = false
    private var stopped = false
    // Avant Android 14 : une file, NsdManager ne résolvant qu'un service à la fois.
    private val toResolve = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    // Android 14 et plus : NsdManager suit chaque téléphone trouvé (adresses,
    // attributs, départ). Par nom de service ; des NsdManager.ServiceInfoCallback.
    private val watched = HashMap<String, Any>()

    private val registration = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(info: NsdServiceInfo) = later {
            registered = true
            ownName = info.serviceName
            if (stopped) unregister()
        }
        override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) {
            Log.w(TAG, "Wireless link announcement failed ($error)")
        }
        override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
        override fun onUnregistrationFailed(info: NsdServiceInfo, error: Int) = Unit
    }

    private val discovery = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(type: String) = later {
            discovering = true
            if (stopped) stopDiscovery()
        }
        override fun onStartDiscoveryFailed(type: String, error: Int) {
            Log.w(TAG, "Wireless link discovery failed ($error)")
        }
        override fun onDiscoveryStopped(type: String) = Unit
        override fun onStopDiscoveryFailed(type: String, error: Int) = Unit
        override fun onServiceFound(info: NsdServiceInfo) = later {
            if (stopped || info.serviceName == ownName) return@later
            if (Build.VERSION.SDK_INT >= 34) {
                watch(info)
            } else {
                toResolve.removeAll { it.serviceName == info.serviceName }
                toResolve.addLast(info)
                resolveNext()
            }
        }
        override fun onServiceLost(info: NsdServiceInfo) = later {
            toResolve.removeAll { it.serviceName == info.serviceName }
            if (Build.VERSION.SDK_INT >= 34) unwatch(info.serviceName)
            if (!stopped) listener.onPeer(info.serviceName, null)
        }
    }

    // Un rappel tardif, après la fermeture du câble, est ignoré : une
    // exception sur le fil de NsdManager arrêterait l'application.
    private fun later(action: () -> Unit) {
        try {
            executor.execute(action)
        } catch (_: RejectedExecutionException) {
        }
    }

    /** Annonce [port] et cherche les téléphones de la même [family]. */
    fun start(family: Int, ownId: Long, device: String, port: Int) {
        val manager = nsd ?: return
        this.family = family
        this.ownId = ownId
        val info = NsdServiceInfo().apply {
            serviceName = "RavenEmu ${WirelessLinkPolicy.shortCode(ownId)}"
            serviceType = WirelessLinkProtocol.SERVICE_TYPE
            this.port = port
            setAttribute("v", WirelessLinkProtocol.VERSION.toString())
            setAttribute("f", family.toString())
            setAttribute("id", java.lang.Long.toHexString(ownId))
            setAttribute("n", device.take(40))
        }
        try {
            manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration)
            manager.discoverServices(WirelessLinkProtocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery)
        } catch (error: RuntimeException) {
            Log.w(TAG, "Wireless link discovery unavailable", error)
        }
    }

    /** Sur [executor] : plus d'annonce ni de recherche. */
    fun stop() {
        if (stopped) return
        stopped = true
        toResolve.clear()
        if (Build.VERSION.SDK_INT >= 34) for (name in watched.keys.toList()) unwatch(name)
        // Pas encore confirmées : retirées à leur confirmation.
        if (registered) unregister()
        if (discovering) stopDiscovery()
    }

    private fun unregister() {
        registered = false
        runCatching { nsd?.unregisterService(registration) }
    }

    private fun stopDiscovery() {
        discovering = false
        runCatching { nsd?.stopServiceDiscovery(discovery) }
    }

    // Android 14 et plus.
    private fun watch(info: NsdServiceInfo) {
        if (Build.VERSION.SDK_INT < 34) return
        val manager = nsd ?: return
        val name = info.serviceName
        if (name in watched) return
        val callback = object : NsdManager.ServiceInfoCallback {
            override fun onServiceInfoCallbackRegistrationFailed(error: Int) = later {
                if (watched[name] === this) watched.remove(name)
            }
            override fun onServiceUpdated(info: NsdServiceInfo) = later {
                if (!stopped && watched[name] === this) found(info)?.let { listener.onPeer(name, it) }
            }
            override fun onServiceLost() = later {
                if (!stopped && watched[name] === this) listener.onPeer(name, null)
            }
            override fun onServiceInfoCallbackUnregistered() = Unit
        }
        watched[name] = callback
        try {
            // Les rappels repassent par later() : ils peuvent partir du fil de NsdManager.
            manager.registerServiceInfoCallback(info, { it.run() }, callback)
        } catch (error: RuntimeException) {
            watched.remove(name)
        }
    }

    // Android 14 et plus.
    private fun unwatch(name: String) {
        if (Build.VERSION.SDK_INT < 34) return
        val callback = watched.remove(name) as? NsdManager.ServiceInfoCallback ?: return
        runCatching { nsd?.unregisterServiceInfoCallback(callback) }
    }

    // Avant Android 14 : resolveService, une résolution à la fois.
    private fun resolveNext() {
        val manager = nsd ?: return
        if (resolving || stopped) return
        val next = toResolve.removeFirstOrNull() ?: return
        resolving = true
        val callback = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, error: Int) = later {
                resolving = false
                resolveNext()
            }
            override fun onServiceResolved(info: NsdServiceInfo) = later {
                resolving = false
                if (!stopped) found(info)?.let { listener.onPeer(info.serviceName, it) }
                resolveNext()
            }
        }
        try {
            @Suppress("DEPRECATION") // Remplacé par registerServiceInfoCallback à partir d'Android 14.
            manager.resolveService(next, callback)
        } catch (error: RuntimeException) {
            resolving = false
        }
    }

    // Un téléphone compatible, ou null (autre version, autre console, soi-même).
    private fun found(info: NsdServiceInfo): Found? {
        val attributes = info.attributes
        fun text(key: String) = attributes[key]?.toString(Charsets.UTF_8)
        if (text("v") != WirelessLinkProtocol.VERSION.toString()) return null
        if (text("f") != family.toString()) return null
        val id = text("id")?.let { runCatching { java.lang.Long.parseUnsignedLong(it, 16) }.getOrNull() } ?: return null
        if (id == ownId) return null
        val host = hostOf(info) ?: return null
        if (info.port !in 1..65535) return null
        return Found(id, text("n")?.takeIf { it.isNotBlank() } ?: "Téléphone", host, info.port)
    }

    private fun hostOf(info: NsdServiceInfo): InetAddress? =
        if (Build.VERSION.SDK_INT >= 34) {
            // IPv4 d'abord : une adresse IPv6 locale sans interface ne se joint pas.
            info.hostAddresses.firstOrNull { it is Inet4Address } ?: info.hostAddresses.firstOrNull()
        } else {
            @Suppress("DEPRECATION")
            info.host
        }

    private companion object {
        const val TAG = "RavenEmuLink"
    }
}
