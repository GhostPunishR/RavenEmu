package com.ravenemu.android.library

/** A lease covers a launch/transfer from admission through its final UI update. */
internal class LibraryOperationGate {
    private var owner: Lease? = null

    @Synchronized fun tryAcquire(): Lease? {
        if (owner != null) return null
        return Lease().also { owner = it }
    }

    @Synchronized fun isBusy(): Boolean = owner != null

    inner class Lease internal constructor() : AutoCloseable {
        override fun close() = synchronized(this@LibraryOperationGate) {
            // A duplicate/late completion cannot release the next operation.
            if (owner === this) owner = null
        }
    }
}
