package com.ravenemu.android

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * RetroAchievements Hardcore needs the approval of the RetroAchievements team
 * (request to RAdmin). Until then this stays false: no Hardcore switch is
 * shown, and Hardcore award requests are never journaled or replayed.
 */
internal object HardcoreAvailability {
    const val APPROVED = false
}

/**
 * Which requests may enter the offline journal: only genuine award requests
 * the SDK built, sent to retroachievements.org over HTTPS. Never manufacture
 * awards from a badge, an achievement id or a save state. Ported from RavenEmu 0.8.1.
 */
internal object AwardPolicy {
    fun isSdkAward(url: String, post: String, allowHardcore: Boolean = HardcoreAvailability.APPROVED): Boolean {
        if (post.length !in 1..8192 || !post.startsWith("r=awardachievement&")) return false
        val fields = post.split('&')
        val softcore = fields.any { it == "h=0" }
        val hardcore = fields.any { it == "h=1" }
        if (!softcore && !(allowHardcore && hardcore)) return false
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        return uri.scheme == "https" && uri.host == "retroachievements.org" &&
            uri.port == -1 && uri.rawUserInfo == null && uri.rawFragment == null
    }

    /**
     * Replay only refreshes the token. The SDK's username, hash, timing and
     * signature stay intact (rcheevos 12.5.0 signs the award without the API
     * token, see rc_api_runtime.c), and the account must be the same one.
     */
    fun authenticatedReplay(
        url: String, post: String, account: String, token: String,
        allowHardcore: Boolean = HardcoreAvailability.APPROVED,
    ): String? {
        if (!isSdkAward(url, post, allowHardcore) || token.isBlank()) return null
        val fields = post.split('&')
        if (fields.count { it.startsWith("t=") } != 1 ||
            fields.count { it.startsWith("u=") } != 1 ||
            fields.count { it.startsWith("h=") } != 1) return null
        val user = runCatching {
            URLDecoder.decode(fields.single { it.startsWith("u=") }.substring(2), "UTF-8")
        }.getOrNull() ?: return null
        if (!user.equals(account, ignoreCase = true)) return null
        val encoded = URLEncoder.encode(token, "UTF-8")
        return fields.joinToString("&") { if (it.startsWith("t=")) "t=$encoded" else it }
    }
}

/** One transaction lock per journal file, including explicit sign-out. */
internal class AwardJournalGuard {
    private var generation = 0L

    @Synchronized fun open(): Lease = Lease(generation)

    @Synchronized fun clear(delete: () -> Unit) {
        ++generation // Revoke every old client, including its late HTTP callbacks.
        delete()
    }

    inner class Lease internal constructor(private val openedAt: Long) {
        fun <T> access(fallback: T, action: () -> T): T = synchronized(this@AwardJournalGuard) {
            if (openedAt != generation) fallback else action()
        }
    }

    companion object {
        private val guards = mutableMapOf<String, AwardJournalGuard>()
        @Synchronized fun forFile(path: String): AwardJournalGuard =
            guards.getOrPut(path) { AwardJournalGuard() }
    }
}

/** Awards inherited from an earlier session; live awards stay owned by rcheevos. */
internal class AwardReplay<T>(entries: List<T>) {
    private val pending = entries.toMutableList()
    private var delay = 5_000L

    /** Called by one scheduled worker. A failed attempt stays pending, with bounded backoff. */
    fun attempt(active: () -> Boolean, sendAndAcknowledge: (T) -> Boolean): Long? {
        val iterator = pending.iterator()
        while (iterator.hasNext()) {
            if (!active()) return null
            if (sendAndAcknowledge(iterator.next())) iterator.remove()
        }
        if (pending.isEmpty() || !active()) return null
        return delay.also { delay = (delay * 2).coerceAtMost(120_000L) }
    }
}
