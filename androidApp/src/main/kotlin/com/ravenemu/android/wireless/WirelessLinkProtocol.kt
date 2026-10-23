package com.ravenemu.android.wireless

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException

/**
 * Ce que deux téléphones s'échangent sur le câble sans fil (ARCHITECTURE.md, « Câble link sans fil »).
 *
 * Une connexion TCP porte des trames [canal u8][taille u8][contenu]. Le canal
 * [CORE] transporte tels quels les messages de RavenCore ; le canal [CONTROL]
 * ceux de l'application : présentation, pause, battement de cœur et départ.
 */
internal object WirelessLinkProtocol {
    const val VERSION = 1
    const val CORE = 0
    const val CONTROL = 1
    const val MAX_PAYLOAD = 255

    /** Type de service annoncé sur le réseau local (DNS-SD). */
    const val SERVICE_TYPE = "_ravenemulink._tcp"

    // Familles de consoles reliables, celles de RavenCore.
    const val FAMILY_GB = 1
    const val FAMILY_GBA = 2

    private const val HELLO = 1
    private const val PAUSE = 2
    private const val PING = 3
    private const val BYE = 4
    private const val MAX_TEXT = 60

    sealed interface Control

    /** Première trame de chaque côté : qui parle, avec quelle console. */
    data class Hello(
        val version: Int,
        val family: Int,
        val id: Long,
        val paused: Boolean,
        val device: String,
        val game: String,
    ) : Control

    /** Le menu du jeu (ou une interruption) arrête cette console, ou la relance. */
    data class Pause(val paused: Boolean) : Control

    /** Rien d'autre à dire : prouve que la connexion vit. */
    data object Ping : Control

    /** Départ volontaire. [refused] : ce téléphone ne veut pas de ce partenaire. */
    data class Bye(val refused: Boolean) : Control

    class Frame(val channel: Int, val payload: ByteArray)

    fun frame(channel: Int, payload: ByteArray): ByteArray {
        require(channel == CORE || channel == CONTROL) { "Canal inconnu." }
        require(payload.size in 1..MAX_PAYLOAD) { "Trame de ${payload.size} octets." }
        return byteArrayOf(channel.toByte(), payload.size.toByte()) + payload
    }

    /** La trame suivante ; [EOFException] à la fin de la connexion, [IOException] si elle est invalide. */
    fun readFrame(input: DataInputStream): Frame {
        val channel = input.readUnsignedByte()
        val size = input.readUnsignedByte()
        if (channel != CORE && channel != CONTROL) throw IOException("Canal inconnu : $channel")
        if (size == 0) throw IOException("Trame vide.")
        val payload = ByteArray(size)
        input.readFully(payload)
        return Frame(channel, payload)
    }

    fun encode(message: Control): ByteArray = when (message) {
        is Hello -> {
            val device = text(message.device)
            val game = text(message.game)
            val out = ArrayList<Byte>(13 + device.size + game.size)
            out += HELLO.toByte()
            out += message.version.toByte()
            out += message.family.toByte()
            for (shift in 0 until 64 step 8) out += (message.id ushr shift).toByte()
            out += (if (message.paused) 1 else 0).toByte()
            out += device.size.toByte()
            out.addAll(device.toList())
            out += game.size.toByte()
            out.addAll(game.toList())
            out.toByteArray()
        }
        is Pause -> byteArrayOf(PAUSE.toByte(), if (message.paused) 1 else 0)
        Ping -> byteArrayOf(PING.toByte())
        is Bye -> byteArrayOf(BYE.toByte(), if (message.refused) 1 else 0)
    }

    /** Null pour un message inconnu ou mal formé. */
    fun decode(payload: ByteArray): Control? {
        if (payload.isEmpty()) return null
        return when (payload[0].toInt()) {
            HELLO -> decodeHello(payload)
            PAUSE -> if (payload.size == 2) Pause(payload[1].toInt() != 0) else null
            PING -> if (payload.size == 1) Ping else null
            BYE -> if (payload.size == 2) Bye(payload[1].toInt() != 0) else null
            else -> null
        }
    }

    private fun decodeHello(payload: ByteArray): Hello? {
        if (payload.size < 14) return null
        var id = 0L
        for (index in 0 until 8) id = id or ((payload[3 + index].toLong() and 0xff) shl (8 * index))
        var at = 12
        val deviceSize = payload[at++].toInt() and 0xff
        if (at + deviceSize >= payload.size) return null
        val device = String(payload, at, deviceSize, Charsets.UTF_8)
        at += deviceSize
        val gameSize = payload[at++].toInt() and 0xff
        if (at + gameSize != payload.size) return null
        val game = String(payload, at, gameSize, Charsets.UTF_8)
        return Hello(
            version = payload[1].toInt() and 0xff,
            family = payload[2].toInt() and 0xff,
            id = id,
            paused = payload[11].toInt() != 0,
            device = device,
            game = game,
        )
    }

    // Un nom tronqué sans couper un caractère.
    private fun text(value: String): ByteArray {
        var end = value.length
        while (value.substring(0, end).toByteArray(Charsets.UTF_8).size > MAX_TEXT) {
            end--
            if (end > 0 && Character.isLowSurrogate(value[end])) end--
        }
        return value.substring(0, end).toByteArray(Charsets.UTF_8)
    }

    /** Joueur 0, le parent GBA : le téléphone au plus petit identifiant. */
    fun playerFor(ownId: Long, peerId: Long): Int = if (java.lang.Long.compareUnsigned(ownId, peerId) < 0) 0 else 1
}

/**
 * Qui relier, d'après les téléphones visibles sur le réseau : décision pure,
 * reprise à chaque changement. Un seul partenaire possible se relie seul ;
 * plusieurs demandent un choix à l'utilisateur.
 */
internal object WirelessLinkPolicy {
    data class Peer(val id: Long, val device: String)

    sealed interface Decision {
        /** Rien à faire : aucun partenaire, ou l'autre téléphone appelle. */
        data object Wait : Decision
        data class Connect(val peer: Peer) : Decision
        data class Choose(val peers: List<Peer>) : Decision
    }

    /**
     * [chosen] : le partenaire désigné par l'utilisateur. [avoided] : ceux
     * qu'il a débranchés, ou qui viennent de partir. Seul le plus petit
     * identifiant appelle de lui-même, pour qu'une seule connexion naisse.
     */
    fun decide(ownId: Long, peers: Collection<Peer>, avoided: Set<Long>, chosen: Long?): Decision {
        val candidates = peers.filter { it.id != ownId && it.id !in avoided }.sortedBy { it.device }
        if (chosen != null) {
            return candidates.firstOrNull { it.id == chosen }?.let { Decision.Connect(it) } ?: Decision.Wait
        }
        return when (candidates.size) {
            0 -> Decision.Wait
            1 -> {
                val peer = candidates.single()
                if (WirelessLinkProtocol.playerFor(ownId, peer.id) == 0) Decision.Connect(peer) else Decision.Wait
            }
            else -> Decision.Choose(candidates)
        }
    }

    /** Une connexion entrante : acceptée si ce téléphone est libre et la console la même. */
    fun accepts(hello: WirelessLinkProtocol.Hello, ownFamily: Int, avoided: Set<Long>, busy: Boolean): Boolean =
        hello.version == WirelessLinkProtocol.VERSION && hello.family == ownFamily &&
            hello.id !in avoided && !busy

    /** Code court qui distingue deux téléphones du même modèle. */
    fun shortCode(id: Long): String = "%04X".format((id ushr 48).toInt() and 0xffff)
}
