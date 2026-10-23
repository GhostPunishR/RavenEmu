package com.ravenemu.android.link

import com.ravenemu.android.engine.NativeCore

/**
 * Virtual link cable between two games running side by side (RavenCore's
 * ravenemu_link): two GB/GBC cores, or two GBA cores whose first one is the
 * parent of multiplayer games. While linked, both cores advance together
 * through [frameInto]; [NativeCore.frameInto] fails on them. Close the link
 * before its cores, from the same emulation thread.
 */
class NativeLink(first: NativeCore, second: NativeCore) : AutoCloseable {
    private var pointer: Long =
        nativeCreate(longArrayOf(first.nativeHandle, second.nativeHandle)).also {
            check(it != 0L) { nativeLastError() }
        }

    /** One frame of both games, into caller-owned ARGB8888 buffers of the same size. */
    fun frameInto(first: IntArray, second: IntArray) {
        require(first.size == second.size) { "Les deux images doivent avoir la même taille." }
        check(nativeFrame(requireOpen(), first, second)) { nativeLastError() }
    }

    private fun requireOpen(): Long = pointer.also {
        check(it != 0L) { "Câble link débranché" }
    }

    override fun close() {
        if (pointer != 0L) {
            nativeDestroy(pointer)
            pointer = 0L
        }
    }

    private external fun nativeCreate(handles: LongArray): Long
    private external fun nativeDestroy(pointer: Long)
    private external fun nativeFrame(pointer: Long, first: IntArray, second: IntArray): Boolean
    private external fun nativeLastError(): String

    private companion object {
        init { System.loadLibrary("ravenemu_android") }
    }
}
