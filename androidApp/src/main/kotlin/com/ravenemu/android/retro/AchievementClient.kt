// Port of RavenEmu 0.8.1 RA client transport, keeping native rcheevos as sole award authority.
package com.ravenemu.android.retro

import com.ravenemu.shared.retro.RetroSnapshot
import com.ravenemu.shared.retro.AchievementEntry
import com.ravenemu.shared.retro.RetroLeaderboard
import com.ravenemu.shared.retro.RetroLeaderboardEntry
import com.ravenemu.shared.retro.RetroLeaderboardView
import com.ravenemu.shared.retro.RetroScoreboard
import java.text.DateFormat
import java.util.Date

import java.net.URL
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection

/** Owned by the session thread (or the account dialog worker). Never by an HTTP worker. */
internal class AchievementClient(
    private val readMemory: ((Int, Int) -> ByteArray)? = null,
    /** Durable journal for unlocks earned offline; null in tests. */
    private val journal: AwardJournal? = null,
    /** The account this client signs in, owner of the journaled awards. */
    private val account: String = "",
    /** See [RetroUserAgent]. */
    private val userAgent: String = "",
    private val onEvent: (Event) -> Unit,
) : AutoCloseable {
    data class Event(val kind: String, val title: String, val detail: String, val extra: String, val image: String, val id: String = "")
    private data class Response(val id: Int, val body: ByteArray, val status: Int)
    private val handle = NativeAchievementsBridge.create().also { check(it != 0L) }
    private val closed = AtomicBoolean(false)
    private val responses = ConcurrentLinkedQueue<Response>()
    private val awardUpdates = ConcurrentLinkedQueue<AwardSyncTracker.Key>()
    private val sync = AwardSyncTracker()
    private var syncWarning = false
    private val dates = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    private fun date(seconds: Long): String = if (seconds > 0) dates.format(Date(seconds * 1000)) else ""
    private val http = Executors.newFixedThreadPool(2) { Thread(it, "RavenEmu-RA-HTTP").apply { isDaemon = true } }
    private val replayWorker = Executors.newSingleThreadScheduledExecutor {
        Thread(it, "RavenEmu-RA-Replay").apply { isDaemon = true }
    }
    private var replayStarted = false
    private var warnedAboutJournal = false
    private val memory = readMemory?.let { reader ->
        NativeAchievementsBridge.Memory { address, count -> reader(address, count) }
    }

    private var status = "Connexion en cours…"
    fun snapshot(): RetroSnapshot {
        val summary = NativeAchievementsBridge.summary(handle).toList()
        val hardcore = hardcore()
        val achievements = NativeAchievementsBridge.achievements(handle).toList()
            .chunked(10).filter { it.size == 10 }.mapNotNull { a ->
                runCatching {
                    AchievementEntry(
                        id = a[0], title = a[1], description = a[2],
                        points = a[3].toInt(), state = a[4].toInt(),
                        progress = a[5], unlockedAt = a[6].toLong(),
                        bucket = a[7].toInt(), badgeUrl = a[8],
                        measuredPercent = a[9].toFloatOrNull() ?: 0f,
                        sync = sync.state(AwardSyncTracker.Key(a[0], hardcore), a[4] == "2"),
                        unlockedDate = date(a[6].toLongOrNull() ?: 0),
                    )
                }.getOrNull()
            }
        return RetroSnapshot(
            status = status,
            displayName = summary.getOrElse(0) { "" },
            softcorePoints = summary.getOrElse(1) { "0" }.toIntOrNull() ?: 0,
            hardcorePoints = summary.getOrElse(2) { "0" }.toIntOrNull() ?: 0,
            gameTitle = summary.getOrElse(3) { "" },
            gameId = summary.getOrElse(4) { "0" }.toIntOrNull() ?: 0,
            unlockedCount = summary.getOrElse(5) { "0" }.toIntOrNull() ?: 0,
            totalCount = summary.getOrElse(6) { "0" }.toIntOrNull() ?: 0,
            unlockedPoints = summary.getOrElse(7) { "0" }.toIntOrNull() ?: 0,
            totalPoints = summary.getOrElse(8) { "0" }.toIntOrNull() ?: 0,
            username = summary.getOrElse(9) { "" },
            avatarUrl = summary.getOrElse(10) { "" },
            entries = achievements,
            hardcore = hardcore,
            gameImageUrl = summary.getOrElse(11) { "" },
            richPresence = NativeAchievementsBridge.richPresence(handle),
            pendingAwards = sync.pendingCount,
            syncWarning = syncWarning,
            leaderboards = NativeAchievementsBridge.leaderboards(handle).toList().chunked(6)
                .filter { it.size == 6 }.map { a ->
                    RetroLeaderboard(a[0].toInt(), a[1], a[2], a[3], a[4].toInt(), a[5] == "1")
                },
            leaderboardView = leaderboardView(),
            scoreboard = NativeAchievementsBridge.scoreboard(handle).takeIf { it.size == 6 }?.let { a ->
                RetroScoreboard(a[0].toInt(), a[1], a[2], a[3], a[4].toInt(), a[5].toInt())
            },
        )
    }

    private fun leaderboardView(): RetroLeaderboardView {
        val data = NativeAchievementsBridge.leaderboardView(handle).toList()
        if (data.size < 5) return RetroLeaderboardView()
        return RetroLeaderboardView(data[0].toInt(), data[1] == "1", data[2], data[3].toInt(), data[4] == "1",
            data.drop(5).chunked(5).filter { it.size == 5 }.map { a ->
                RetroLeaderboardEntry(a[0].toInt(), a[1], a[2], date(a[3].toLongOrNull() ?: 0), a[4] == "1")
            })
    }

    fun fetchLeaderboard(id: Int, aroundUser: Boolean) =
        NativeAchievementsBridge.fetchLeaderboard(handle, id, aroundUser)

    fun login(username: String, secret: String, token: Boolean) = NativeAchievementsBridge.login(handle, username, secret, token)
    fun load(console: Int, rom: ByteArray) = NativeAchievementsBridge.load(handle, console, rom)
    fun unload() = NativeAchievementsBridge.unload(handle)
    fun reset() = NativeAchievementsBridge.reset(handle)
    fun setHardcore(enabled: Boolean) = NativeAchievementsBridge.setHardcore(handle, enabled)
    fun hardcore(): Boolean = NativeAchievementsBridge.hardcore(handle)
    fun canPause(): Boolean = NativeAchievementsBridge.canPause(handle)

    fun tick(frame: Boolean) {
        if (closed.get()) return
        while (true) {
            val key = awardUpdates.poll() ?: break
            sync.confirmed(key)
        }
        while (true) {
            val response = responses.poll() ?: break
            NativeAchievementsBridge.respond(handle, response.id, response.body, response.status, memory)
        }
        NativeAchievementsBridge.tick(handle, memory, frame)
        NativeAchievementsBridge.events(handle).asList().chunked(6).filter { it.size == 6 }.forEach {
            when (it[0]) {
                "login" -> {
                    status = "Chargement des succès…"
                    resendStoredAwards(it[1], it[3])
                }
                "game", "online" -> status = if (NativeAchievementsBridge.hardcore(handle))
                    "Connecté • Mode Hardcore" else "Connecté • Mode normal (Softcore)"
                "login_error", "game_unknown", "game_offline", "game_error", "offline" -> status = it[1]
            }
            if (it[0] == "achievement" && it[5].isNotBlank())
                sync.local(AwardSyncTracker.Key(it[5], hardcore()))
            onEvent(Event(it[0], it[1], it[2], it[3], it[4], it[5]))
        }
        NativeAchievementsBridge.requests(handle).asList().chunked(4).forEach { record ->
            // Journal BEFORE dispatch: the app closing, or the network being
            // gone, must not lose a genuine SDK unlock. The SDK stays its owner
            // for this session; the journal only serves the next one.
            val stored = if (journal != null && account.isNotBlank() &&
                AwardPolicy.isSdkAward(record[1], record[2])
            ) AwardJournal.Award(account, record[1], record[2], record[3]) else null
            val awardKey = if (AwardPolicy.isSdkAward(record[1], record[2]))
                AwardSyncTracker.requestKey(record[2]) else null
            awardKey?.let(sync::pending)
            if (stored != null) {
                val kept = runCatching { journal?.remember(stored) == true }.getOrDefault(false)
                if (!kept) syncWarning = true
                if (!kept && !warnedAboutJournal) {
                    warnedAboutJournal = true
                    onEvent(Event("notice", "Succès hors ligne",
                        "Le stockage sécurisé est indisponible ou plein : gardez le jeu ouvert jusqu'à la synchronisation.",
                        "", ""))
                }
            }
            http.execute {
                val response = request(record)
                if (awardKey != null && awardAccepted(response)) {
                    if (stored != null) runCatching { journal?.acknowledge(stored) }
                    if (!closed.get()) awardUpdates.add(awardKey)
                }
                if (!closed.get()) responses.add(response)
            }
        }
    }

    /** Resend unlocks journaled by an earlier session, once the same account is signed in. */
    private fun resendStoredAwards(user: String, token: String) {
        val queue = journal ?: return
        if (replayStarted || token.isBlank()) return
        replayStarted = true
        // Captured before this session's game loads, so the SDK and the replay
        // worker never both retry the same new award.
        val pending = runCatching { queue.forAccount(user) }.getOrDefault(emptyList())
        pending.mapNotNull { AwardSyncTracker.requestKey(it.post) }.forEach(sync::pending)
        if (pending.isEmpty()) return
        val replay = AwardReplay(pending)
        replayWorker.execute(object : Runnable {
            override fun run() {
                val delay = replay.attempt({ !closed.get() && queue.isCurrent() }) { entry ->
                    val post = AwardPolicy.authenticatedReplay(entry.url, entry.post, user, token)
                    if (post == null) false
                    else {
                        val result = send(entry.url, post, entry.contentType)
                        if (awardAccepted(result)) {
                            if (!closed.get()) AwardSyncTracker.requestKey(entry.post)?.let(awardUpdates::add)
                            runCatching { queue.acknowledge(entry); true }.getOrDefault(false)
                        } else false
                    }
                }
                if (delay != null && !closed.get() && queue.isCurrent()) {
                    // Shutdown can race this scheduling; it must not escape the worker.
                    runCatching { replayWorker.schedule(this, delay, TimeUnit.MILLISECONDS) }
                }
            }
        })
    }

    private fun awardAccepted(response: Response): Boolean =
        AwardSyncTracker.accepted(response.status, response.body)

    private fun request(record: List<String>): Response =
        send(record[1], record[2], record[3]).copy(id = record[0].toInt())

    private fun send(address: String, post: String, contentType: String): Response {
        var connection: HttpsURLConnection? = null
        return try {
            val url = URL(address)
            require(url.protocol == "https" && url.host == "retroachievements.org" && url.port in listOf(-1, 443))
            connection = url.openConnection() as HttpsURLConnection
            connection.instanceFollowRedirects = false // Never forward credentials to another host.
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            if (userAgent.isNotEmpty()) connection.setRequestProperty("User-Agent", userAgent)
            if (post.isNotEmpty()) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", contentType)
                connection.outputStream.use { it.write(post.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    check(output.size() + read <= 8 * 1024 * 1024)
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            } ?: ByteArray(0)
            Response(0, body, status)
        } catch (_: Exception) {
            // The SDK retries transient unlock failures. No credentials or request URLs in logs.
            Response(0, ByteArray(0), -2)
        } finally {
            connection?.disconnect()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        replayWorker.shutdownNow()
        http.shutdownNow()
        responses.clear()
        awardUpdates.clear()
        NativeAchievementsBridge.destroy(handle)
    }
}
