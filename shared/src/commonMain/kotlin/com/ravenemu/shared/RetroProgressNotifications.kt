package com.ravenemu.shared

/**
 * Fair, one-at-a-time notifications from the SDK's complete measured list.
 * Its progress event only reports the closest achievement for a frame:
 * https://github.com/RetroAchievements/rcheevos/wiki/rc_client-integration#progress-indicator
 * Measurements and achievement eligibility remain entirely owned by rcheevos.
 */
class RetroProgressNotifications {
    var displayMillis: Long = 2_000L
        set(value) { field = value.coerceIn(1_000L, 5_000L) }
    private data class Item(val id: String, val progress: RetroProgress, val changedAt: Long)
    private data class Measurement(val percent: Float, val text: String)

    private val best = mutableMapOf<String, Measurement>()
    private val pending = linkedMapOf<String, Item>()
    private var initialized = false
    private var current: Item? = null
    var nextTransitionAt: Long? = null
        private set
    val progress: RetroProgress? get() = current?.progress

    fun update(entries: List<AchievementEntry>, nowMillis: Long): RetroProgress? {
        val eligible = entries.filter {
            it.state == 1 && it.progress.isNotBlank() &&
                it.measuredPercent.isFinite() && it.measuredPercent > 0f && it.measuredPercent <= 100f
        }.associateBy { it.id }
        pending.keys.retainAll(eligible.keys)
        if (current?.let { it.id !in eligible } == true) {
            current = null
            nextTransitionAt = null
        }
        val baseline = !initialized
        if (entries.isNotEmpty()) initialized = true
        for (entry in entries) {
            val percent = entry.measuredPercent
            if (!percent.isFinite() || percent !in 0f..100f) continue
            val previous = best[entry.id]
            val previousPercent = previous?.percent ?: 0f
            if (previous == null || percent > previousPercent) {
                best[entry.id] = Measurement(percent, entry.progress)
            }
            if (entry.id !in eligible) continue
            val item = Item(entry.id, RetroProgress(entry.title, entry.progress, entry.badgeUrl), nowMillis)
            if (current?.id == entry.id && nowMillis < (nextTransitionAt ?: 0L)) {
                // Refresh the number without extending the turn. A frequently
                // changing achievement must not keep other achievements waiting.
                current = item
            } else if (percent < previousPercent) {
                // A counter can reset or decrease (MeasuredIf, a new attempt).
                // Do not later announce an outdated, higher value.
                pending.remove(entry.id)
            } else if (!baseline && percent > previousPercent && entry.progress != previous?.text &&
                (entry.id in pending || pending.size < MAX_PENDING)) {
                // Replacing an entry keeps its original place in the queue.
                pending[entry.id] = item
            }
        }
        return advance(nowMillis)
    }

    fun advance(nowMillis: Long): RetroProgress? {
        if (current != null && nowMillis < (nextTransitionAt ?: 0L)) return progress
        current = null
        nextTransitionAt = null
        while (pending.isNotEmpty()) {
            val id = pending.keys.first()
            val next = pending.remove(id) ?: continue
            if (nowMillis - next.changedAt > MAX_AGE_MS) continue
            current = next
            // Match the SDK's two-second progress-indicator timer.
            nextTransitionAt = nowMillis + displayMillis
            break
        }
        return progress
    }

    /** Hide notifications on leaving the player without announcing them again. */
    fun dismiss() {
        pending.clear()
        current = null
        nextTransitionAt = null
    }

    fun clear() {
        dismiss()
        best.clear()
        initialized = false
    }

    private companion object {
        const val MAX_AGE_MS = 10_000L
        const val MAX_PENDING = 20
    }
}
