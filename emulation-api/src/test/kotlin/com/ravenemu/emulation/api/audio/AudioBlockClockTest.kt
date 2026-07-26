package com.ravenemu.emulation.api.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AudioBlockClockTest {

    @Test
    fun `le premier bloc utilise la periode native`() {
        val clock = AudioBlockClock(minimumPeriodNanos = 16_742_000L)

        assertEquals(16_742_000L, clock.mark(1_000_000_000L))
    }

    @Test
    fun `la duree couvre tout l'intervalle entre deux livraisons`() {
        val clock = AudioBlockClock(minimumPeriodNanos = 16_742_000L)
        clock.mark(1_000_000_000L)

        assertEquals(19_531_250L, clock.mark(1_019_531_250L))
    }

    @Test
    fun `une livraison rapide ne raccourcit pas la periode native`() {
        val clock = AudioBlockClock(minimumPeriodNanos = 16_742_000L)
        clock.mark(100_000_000L)

        assertEquals(16_742_000L, clock.mark(110_000_000L))
    }

    @Test
    fun `un retard exceptionnel reste borne`() {
        val clock = AudioBlockClock(
            minimumPeriodNanos = 10_000_000L,
            maximumPeriodMultiplier = 3L,
        )
        clock.mark(100_000_000L)

        assertEquals(30_000_000L, clock.mark(500_000_000L))
    }

    @Test
    fun `reset oublie l'ancien intervalle`() {
        val clock = AudioBlockClock(minimumPeriodNanos = 16_742_000L)
        clock.mark(100_000_000L)
        clock.reset()

        assertEquals(16_742_000L, clock.mark(900_000_000L))
    }

    @Test
    fun `les parametres invalides sont refuses`() {
        assertFailsWith<IllegalArgumentException> {
            AudioBlockClock(minimumPeriodNanos = 0L)
        }
        assertFailsWith<IllegalArgumentException> {
            AudioBlockClock(minimumPeriodNanos = 1L, maximumPeriodMultiplier = 0L)
        }
    }
}
