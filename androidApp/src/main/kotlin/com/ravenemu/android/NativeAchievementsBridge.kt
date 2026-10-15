package com.ravenemu.android

/**
 * RavenEmu 0.8.1's official rcheevos JNI adapter, linked into the RavenEmu native library.
 * One client handle belongs to exactly one worker thread.
 */
internal object NativeAchievementsBridge {
    fun interface Memory {
        fun read(address: Int, count: Int): ByteArray
    }

    init { System.loadLibrary("ravenemu_android") }

    external fun create(): Long
    external fun destroy(handle: Long)
    external fun login(handle: Long, username: String, secret: String, token: Boolean)
    external fun load(handle: Long, console: Int, rom: ByteArray)
    external fun tick(handle: Long, memory: Memory?, frame: Boolean)
    external fun reset(handle: Long)
    external fun requests(handle: Long): Array<String>
    external fun respond(
            handle: Long, id: Int, body: ByteArray, status: Int, memory: Memory?,
        )
    external fun events(handle: Long): Array<String>
    external fun summary(handle: Long): Array<String>
    external fun achievements(handle: Long): Array<String>
    external fun setHardcore(handle: Long, enabled: Boolean)
    external fun hardcore(handle: Long): Boolean
    /** Records a pause when it returns true (Hardcore pause limits). */
    external fun canPause(handle: Long): Boolean
}
