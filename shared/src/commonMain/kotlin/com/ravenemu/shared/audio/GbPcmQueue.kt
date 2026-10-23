package com.ravenemu.shared.audio

/**
 * Bounded interleaved stereo PCM16 FIFO between RavenGB and the Android audio
 * device. Both append and consume operate in sample counts, not byte counts.
 * Oldest audio is discarded when the device cannot keep up; it must never cause
 * an unbounded delay or stall the game.
 *
 * Not thread-safe: the Android sink serializes all access to this queue.
 */
class GbPcmQueue(capacitySamples: Int) {
    init { require(capacitySamples >= 2 && capacitySamples % 2 == 0) }

    private val data = ShortArray(capacitySamples)
    private var head = 0

    var size: Int = 0
        private set

    fun append(samples: ShortArray, count: Int): Int {
        require(count in 0..samples.size && count % 2 == 0)
        var start = 0
        if (count > data.size) start = count - data.size
        val stored = count - start
        val overflow = (size + stored - data.size).coerceAtLeast(0)
        discard(overflow)
        for (i in 0 until stored) {
            data[(head + size + i) % data.size] = samples[start + i]
        }
        size += stored
        return start + overflow
    }

    fun copyHead(output: ShortArray): Int {
        val count = minOf(size, output.size)
        for (i in 0 until count) output[i] = data[(head + i) % data.size]
        return count
    }

    fun discard(count: Int) {
        require(count in 0..size)
        head = (head + count) % data.size
        size -= count
    }

    fun clear() {
        head = 0
        size = 0
    }
}
