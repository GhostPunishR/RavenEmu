package com.ravenemu.shared

/** Measured progress of one achievement, shown like an unlock popup. */
data class RetroProgress(val title: String, val detail: String, val badgeUrl: String)

/**
 * Persistent RetroAchievements status while playing: active challenges,
 * measured progress and leaderboard trackers. Unlike popups, each one stays
 * until the SDK sends its HIDE event. Same rules as RavenEmu 0.8.1's indicator state.
 */
class RetroIndicatorState {
    private val active = linkedMapOf<String, String>()

    /** Challenge and tracker lines to show, oldest first. */
    val labels: List<String> get() = active.values.toList()

    /**
     * The measured progress on screen, if any. rcheevos has one progress
     * indicator, which can switch achievements; its timer's HIDE carries no
     * achievement identifier.
     */
    var progress: RetroProgress? = null
        private set

    /** True for the event kinds this state handles. */
    fun handles(kind: String): Boolean = kind.substringBeforeLast('_') in FAMILIES &&
        (kind.endsWith("_show") || kind.endsWith("_hide"))

    fun update(kind: String, id: String, title: String, detail: String, badgeUrl: String = "") {
        if (!handles(kind)) return
        val family = kind.substringBeforeLast('_')
        val hide = kind.endsWith("_hide")
        if (family == "progress") {
            progress = if (hide) null else RetroProgress(title, detail, badgeUrl)
            return
        }
        val key = "$family:$id"
        if (hide) active.remove(key)
        else active[key] = listOf(title, detail).filter { it.isNotBlank() }.joinToString(" • ")
    }

    fun clear() {
        active.clear()
        progress = null
    }

    private companion object {
        val FAMILIES = setOf("challenge", "progress", "tracker")
    }
}

/** One-off RetroAchievements messages for the popup, or null for other kinds. */
object RetroNoticeText {
    fun of(kind: String, title: String, detail: String, extra: String): String? {
        val headline = when (kind) {
            "achievement" -> "Succès débloqué · $title ($extra pts)"
            "leaderboard_started" -> "Classement commencé · $title"
            "leaderboard_failed" -> "Classement annulé · $title"
            "leaderboard_submitted" -> "Classement envoyé · $title"
            "leaderboard_scoreboard" -> "Classement terminé · $title"
            "completed" -> "Jeu terminé · $title"
            "mastered" -> "Jeu maîtrisé · $title"
            "notice", "offline", "online", "hardcore_reset", "hardcore_refused" -> title
            else -> return null
        }
        // An unlock's description is long; the popup keeps it to the title.
        val body = if (kind == "achievement" || (kind.startsWith("leaderboard_") && kind != "leaderboard_scoreboard")) "" else detail
        return listOf(headline, body).filter { it.isNotBlank() }.joinToString("\n")
    }
}
