package com.ravenemu.android

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class UnlockChimeTest {
    @Test fun `le carillon est stereo, court, au quart de la pleine echelle et sans clic`() {
        for (rate in listOf(32768, 44100, 48000)) {
            val chime = unlockChime(rate)
            assertEquals(0, chime.size % 2)
            val seconds = chime.size / 2.0 / rate
            assertTrue("$rate Hz : $seconds s", seconds > 0.7 && seconds < 1.0)
            for (i in chime.indices step 2) assertEquals(chime[i], chime[i + 1])
            val peak = chime.maxOf { abs(it.toInt()) }
            assertTrue("$rate Hz : crête $peak", peak in 7900..8000)
            assertTrue(abs(chime.first().toInt()) < 200 && abs(chime.last().toInt()) < 200)
        }
        assertEquals(0, unlockChime(0).size)
    }

    @Test fun `le melangeur ajoute le son au jeu sur plusieurs tampons, sature puis s'arrete`() {
        val mixer = SoundMixer()
        mixer.play(shortArrayOf(1000, -1000, 2000, -2000, 30000, -30000))
        assertTrue(mixer.playing)
        val first = shortArrayOf(5, 5, 7, 7)
        mixer.mix(first, first.size)
        assertArrayEquals(shortArrayOf(1005, -995, 2007, -1993), first)
        val loud = shortArrayOf(10000, -10000, 7, 7)
        mixer.mix(loud, loud.size)
        assertArrayEquals(shortArrayOf(32767, -32768, 7, 7), loud)
        assertFalse(mixer.playing)
        val after = shortArrayOf(5, 5)
        mixer.mix(after, after.size)
        assertArrayEquals(shortArrayOf(5, 5), after)
        mixer.play(shortArrayOf(1, 1))
        assertTrue("Rejouer relance le son", mixer.playing)
    }
}
