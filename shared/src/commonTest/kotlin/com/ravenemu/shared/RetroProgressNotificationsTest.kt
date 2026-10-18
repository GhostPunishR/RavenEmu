package com.ravenemu.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RetroProgressNotificationsTest {
    private val notifications = RetroProgressNotifications()

    @Test
    fun simultaneousImprovementsEachGetTheirTurn() {
        notifications.update(listOf(entry("a", 70), entry("b", 10)), 0)
        assertEquals("a", notifications.update(listOf(entry("a", 80), entry("b", 20)), 100)?.title)
        assertEquals("b", notifications.advance(2_100)?.title)
        assertNull(notifications.advance(4_100))
    }

    @Test
    fun continuousUpdatesDoNotPostponeAnotherAchievement() {
        notifications.update(listOf(entry("a", 0), entry("b", 0)), 0)
        notifications.update(listOf(entry("a", 80), entry("b", 20)), 100)
        assertEquals("81/100", notifications.update(listOf(entry("a", 81), entry("b", 20)), 1_000)?.detail)
        assertEquals(2_100L, notifications.nextTransitionAt)
        assertEquals("b", notifications.update(listOf(entry("a", 82), entry("b", 20)), 2_100)?.title)
        assertEquals("82/100", notifications.advance(4_100)?.detail)
    }

    @Test
    fun unchangedValuesAndOscillatingCountersDoNotSpawnTheSamePopup() {
        notifications.update(listOf(entry("a", 70)), 0)
        notifications.update(listOf(entry("a", 80)), 100)
        assertNull(notifications.advance(2_100))
        for ((time, percent) in listOf(3_000L to 80, 4_000L to 79, 5_000L to 80)) {
            assertNull(notifications.update(listOf(entry("a", percent)), time))
        }
        assertEquals("81/100", notifications.update(listOf(entry("a", 81)), 6_000)?.detail)
    }

    @Test
    fun queuedAchievementShowsItsLatestValueWithoutLosingItsPlace() {
        val initial = listOf(entry("a", 0), entry("b", 0), entry("c", 0))
        notifications.update(initial, 0)
        notifications.update(listOf(entry("a", 10), entry("b", 10), entry("c", 10)), 100)
        notifications.update(listOf(entry("c", 20), entry("b", 30), entry("a", 10)), 1_000)
        assertEquals(RetroProgress("b", "30/100", "b.png"), notifications.advance(2_100))
        assertEquals("c", notifications.advance(4_100)?.title)
    }

    @Test
    fun unlockedAndResetMeasurementsAreNotShownFromTheQueue() {
        notifications.update(listOf(entry("a", 0), entry("b", 0), entry("c", 0)), 0)
        notifications.update(listOf(entry("a", 10), entry("b", 20), entry("c", 30)), 100)
        assertNull(notifications.update(listOf(
            entry("a", 100).copy(state = 2), entry("b", 0).copy(progress = ""), entry("c", 10),
        ), 200))
        assertNull(notifications.advance(2_100))
    }

    @Test
    fun staleNotificationsAreDropped() {
        notifications.update(listOf(entry("a", 0), entry("b", 0)), 0)
        notifications.update(listOf(entry("a", 10), entry("b", 20)), 100)
        assertNull(notifications.advance(10_101))
    }

    @Test
    fun restoringABaselineAndDismissingThePlayerDoNotReplayProgress() {
        assertNull(notifications.update(listOf(entry("a", 80)), 0))
        notifications.update(listOf(entry("a", 81)), 100)
        notifications.dismiss()
        assertNull(notifications.update(listOf(entry("a", 81)), 200))
        notifications.clear()
        assertNull(notifications.update(listOf(entry("a", 10)), 300))
        assertEquals("11/100", notifications.update(listOf(entry("a", 11)), 400)?.detail)
    }

    @Test
    fun invalidOrUnmeasuredValuesDoNotCreateNotifications() {
        notifications.update(listOf(entry("a", 0)), 0)
        for (invalid in listOf(
            entry("a", 10).copy(progress = ""), entry("a", 20).copy(state = 3),
            entry("a", 30).copy(measuredPercent = Float.NaN),
            entry("a", 40).copy(measuredPercent = Float.POSITIVE_INFINITY),
        )) assertNull(notifications.update(listOf(invalid), 100))
    }

    private fun entry(id: String, percent: Int) = AchievementEntry(
        id, id, "", 5, 1, "$percent/100", 0L, 1, "$id.png", percent.toFloat(),
    )
}
