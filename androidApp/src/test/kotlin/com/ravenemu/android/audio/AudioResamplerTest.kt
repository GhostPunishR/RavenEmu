package com.ravenemu.android.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class AudioResamplerTest {
    @Test fun convertsGameBoyStereoToDeviceRateWithoutClipping() {
        val converter = BandLimitedResampler(32768, 48000)
        val input = ShortArray(1096) { index ->
            val frame = index / 2
            (sin(2.0 * PI * 440.0 * frame / 32768.0) * 12000).toInt().toShort()
        }
        val output = ShortArray(converter.maxOutput(input.size))
        val written = converter.resample(input, input.size, output)
        assertTrue(written in 1550..1650)
        assertEquals(0, written % 2)
        assertTrue(output.take(written).all { it.toInt() in -32768..32767 })
        // Keep the two channels separate, not alternating or misaligned.
        for (i in 0 until written step 2) assertEquals(output[i], output[i + 1])
    }

    @Test fun maintainsNativeRateAndStereoOrderWhenRatesMatch() {
        val converter = BandLimitedResampler(48000, 48000)
        val input = shortArrayOf(12, -12, 340, -340)
        val output = ShortArray(converter.maxOutput(input.size))
        assertEquals(4, converter.resample(input, input.size, output))
        assertTrue(input.contentEquals(output))
    }

    @Test fun audioClockCorrectionRemainsSmall() {
        val governor = AudioClockGovernor(4800)
        repeat(100) { governor.onQueuedFrames(6000) }
        assertTrue(governor.rateScale in 1.0..1.005)
        governor.reset()
        assertEquals(1.0, governor.rateScale, 0.0)
    }

    @Test fun sixFramePrimingDelaysFirstPlayback() {
        val primer = AudioBufferPrimer(9600)
        assertTrue(!primer.onSamplesQueued(8000))
        assertTrue(primer.onSamplesQueued(1600))
        assertTrue(!primer.onSamplesQueued(500))
        assertTrue(primer.playbackStarted)
    }
}
