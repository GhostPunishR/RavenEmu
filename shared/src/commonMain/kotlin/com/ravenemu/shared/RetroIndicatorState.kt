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

/**
 * One RetroAchievements banner: a heading line over a larger title line. An
 * award (an unlock, a completed or mastered game) is drawn in gold and chimes.
 */
data class RetroBanner(val heading: String, val title: String, val award: Boolean = false)

/** One-off RetroAchievements messages for the banner, or null for other kinds. */
object RetroNoticeText {
    fun of(kind: String, title: String, detail: String, extra: String): RetroBanner? = when (kind) {
        "achievement" -> RetroBanner("Succès débloqué · ${points(extra)}", title, award = true)
        "completed" -> RetroBanner("Jeu terminé", title, award = true)
        "mastered" -> RetroBanner("Jeu maîtrisé", title, award = true)
        // A game that starts: its icon, its title and what it offers.
        "game" -> RetroBanner(available(extra), title)
        "leaderboard_started" -> RetroBanner("Classement commencé", title)
        "leaderboard_failed" -> RetroBanner("Classement annulé", title)
        "leaderboard_submitted" -> RetroBanner("Classement envoyé", title)
        "leaderboard_scoreboard" -> RetroBanner("Classement terminé · $title", detail)
        "notice", "offline", "online", "hardcore_reset", "hardcore_refused" -> RetroBanner(title, detail)
        else -> null
    }

    private fun points(extra: String): String {
        val count = extra.toIntOrNull() ?: return "$extra points"
        return if (count > 1) "$count points" else "$count point"
    }

    private fun available(extra: String): String = when (val count = extra.toIntOrNull()) {
        null -> "Succès disponibles"
        0 -> "Aucun succès disponible"
        1 -> "1 succès disponible"
        else -> "$count succès disponibles"
    }
}

/**
 * The banner's motion, as on the PC: the badge drops in, then the band opens
 * on both sides of it; at the end it folds back into the badge, which leaves.
 */
object RetroBannerMotion {
    const val DROP_MILLIS = 300
    const val UNFOLD_MILLIS = 350
    const val ENTER_MILLIS = DROP_MILLIS + UNFOLD_MILLIS
    const val EXIT_MILLIS = UNFOLD_MILLIS + DROP_MILLIS
}
