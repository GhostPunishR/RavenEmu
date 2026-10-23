package com.ravenemu.android.retro

import com.ravenemu.shared.retro.RetroSync
import org.json.JSONObject

/** Presentation only. Never grants an achievement or constructs an award. SDK-thread owned. */
internal class AwardSyncTracker {
    data class Key(val id: String, val hardcore: Boolean)
    private val states = mutableMapOf<Key, RetroSync>()
    fun local(key: Key) { if (states[key] != RetroSync.CONFIRMED) states[key] = RetroSync.LOCAL }
    fun pending(key: Key) { if (states[key] != RetroSync.CONFIRMED) states[key] = RetroSync.PENDING }
    fun confirmed(key: Key) { states[key] = RetroSync.CONFIRMED }
    fun state(key: Key, unlocked: Boolean): RetroSync = states[key] ?:
        if (unlocked) RetroSync.CONFIRMED else RetroSync.NONE
    val pendingCount: Int get() = states.values.count { it == RetroSync.LOCAL || it == RetroSync.PENDING }

    companion object {
        fun accepted(status: Int, body: ByteArray): Boolean = status in 200..299 && runCatching {
            val reply = JSONObject(String(body, Charsets.UTF_8))
            // As in rcheevos (rc_api_runtime.c): the unlock is already on the
            // account, set by an earlier attempt whose reply was lost.
            reply.opt("Success") == true ||
                reply.opt("Success") == false && reply.optString("Error").startsWith("User already has")
        }.getOrDefault(false)

        fun requestKey(post: String): Key? {
            val parts = post.split('&')
            if (parts.firstOrNull() != "r=awardachievement") return null
            val ids = parts.filter { it.startsWith("a=") }
            val modes = parts.filter { it.startsWith("h=") }
            if (ids.size != 1 || modes.size != 1) return null
            val id = ids.single().substring(2).toIntOrNull()?.takeIf { it > 0 } ?: return null
            val mode = modes.single().substring(2).takeIf { it == "0" || it == "1" } ?: return null
            return Key(id.toString(), mode == "1")
        }
    }
}
