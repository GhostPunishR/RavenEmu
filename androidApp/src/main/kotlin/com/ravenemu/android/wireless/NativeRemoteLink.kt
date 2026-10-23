package com.ravenemu.android.wireless

import com.ravenemu.android.engine.NativeCore

/**
 * Câble link vers la console d'un autre appareil (RavenCore,
 * docs/remote_link.md). [sink] reçoit chaque message à envoyer à l'autre
 * appareil ; ceux qu'il renvoie passent par [receive]. Le câble suit le cœur
 * de la partie en cours : [AndroidGbSession] le crée et le détruit, jamais
 * pendant une image.
 *
 * [receive], [setConnected] et [state] s'appellent de n'importe quel fil ;
 * [service] du fil d'émulation. Après [close], tout devient sans effet.
 */
internal class NativeRemoteLink(core: NativeCore, player: Int, sink: Sink) : AutoCloseable {
    fun interface Sink {
        /** Appelé par le fil d'émulation ou celui qui branche : ne doit pas bloquer. */
        fun send(message: ByteArray)
    }

    enum class State { UNPLUGGED, WAITING, PLUGGED, INCOMPATIBLE }

    private var pointer: Long = nativeCreate(core.nativeHandle, player, sink).also {
        check(it != 0L) { "Câble sans fil indisponible pour cette console." }
    }

    val open: Boolean get() = synchronized(this) { pointer != 0L }

    /** Un message de l'autre appareil ; faux s'il est mal formé. */
    fun receive(message: ByteArray): Boolean = synchronized(this) {
        pointer != 0L && nativeReceive(pointer, message)
    }

    /** Débrancher termine sur-le-champ une image qui attend l'autre console. */
    fun setConnected(connected: Boolean) = synchronized(this) {
        if (pointer != 0L) nativeSetConnected(pointer, connected)
    }

    val state: State get() = synchronized(this) {
        if (pointer == 0L) State.UNPLUGGED else State.entries.getOrElse(nativeState(pointer)) { State.UNPLUGGED }
    }

    /** Entre deux images : répond aux transferts que l'autre console a cadencés. */
    fun service() = synchronized(this) {
        if (pointer != 0L) nativeService(pointer)
    }

    override fun close() = synchronized(this) {
        if (pointer != 0L) {
            nativeDestroy(pointer)
            pointer = 0L
        }
    }

    private external fun nativeCreate(core: Long, player: Int, sink: Sink): Long
    private external fun nativeDestroy(pointer: Long)
    private external fun nativeReceive(pointer: Long, message: ByteArray): Boolean
    private external fun nativeSetConnected(pointer: Long, connected: Boolean)
    private external fun nativeState(pointer: Long): Int
    private external fun nativeService(pointer: Long): Boolean

    private companion object {
        init { System.loadLibrary("ravenemu_android") }
    }
}
