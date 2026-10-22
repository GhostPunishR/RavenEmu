package com.ravenemu.android

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The short chime of an unlocked achievement or a completed game, as
 * interleaved stereo PCM16 at [rate] Hz. It is synthesized here, the same as on
 * the PC: four rising bell-like notes, under a second, no recording shipped.
 */
internal fun unlockChime(rate: Int): ShortArray {
    if (rate <= 0) return ShortArray(0)
    // G5, C6, E6 then G6, each struck 80 ms after the previous one.
    val notes = doubleArrayOf(783.99, 1046.50, 1318.51, 1567.98)
    val step = 0.08
    val ring = 0.55
    val attack = 0.004
    val release = 0.05
    val frames = ((step * 3 + ring) * rate).toInt()
    val mono = DoubleArray(frames)
    for ((n, f) in notes.withIndex()) {
        val start = (n * step * rate).toInt()
        for (i in start until frames) {
            val t = (i - start).toDouble() / rate
            if (t >= ring) break
            // Struck, then ringing out; a short release so it ends without a click.
            val envelope = min(1.0, t / attack) * exp(-t / 0.16) * min(1.0, (ring - t) / release)
            mono[i] += envelope * (sin(2 * PI * f * t) + 0.35 * sin(2 * PI * 2 * f * t) +
                0.12 * sin(2 * PI * 3 * f * t))
        }
    }
    val peak = mono.maxOfOrNull { abs(it) } ?: 0.0
    // A quarter of full scale: heard over the game, never harsh.
    val gain = if (peak > 0.0) 8000.0 / peak else 0.0
    val pcm = ShortArray(frames * 2)
    for (i in 0 until frames) {
        val sample = (mono[i] * gain).roundToInt().toShort()
        pcm[2 * i] = sample
        pcm[2 * i + 1] = sample
    }
    return pcm
}

/**
 * Adds one sound at a time to the game's own samples, so it plays through the
 * game's audio track, at its volume, and pauses with the game.
 */
internal class SoundMixer {
    private var sound: ShortArray? = null
    private var position = 0

    /** Playing again restarts the sound. */
    fun play(pcm: ShortArray) {
        sound = pcm
        position = 0
    }

    val playing: Boolean get() = sound?.let { position < it.size } ?: false

    /** Mixes the next [count] samples into [out], saturating at the PCM16 range. */
    fun mix(out: ShortArray, count: Int) {
        val pcm = sound ?: return
        val n = min(count, pcm.size - position)
        for (i in 0 until n) {
            out[i] = (out[i] + pcm[position + i]).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        position += maxOf(n, 0)
        if (position >= pcm.size) sound = null
    }
}
