package com.ravenemu.shared

/**
 * Core-produced frames per wall-clock second, not compositor/presented FPS.
 * Keep rendering and native execution timings separate for phone diagnostics.
 */
data class GbVideoMetrics(
    val actualFps: Double,
    val targetFps: Double,
    val coreMs: Double,
    val conversionMs: Double,
)

class GbVideoMetricsAccumulator(private val targetFps: Double) {
    init { require(targetFps > 0.0) }

    private var startedAt: Long? = null
    private var frameCount = 0
    private var coreSumNanos = 0L
    private var conversionSumNanos = 0L

    /** One update roughly every second; avoid recomposing FPS text every frame. */
    fun record(
        timestampNanos: Long,
        coreNanos: Long,
        conversionNanos: Long,
    ): GbVideoMetrics? {
        require(coreNanos >= 0L && conversionNanos >= 0L)
        val start = startedAt ?: timestampNanos.also { startedAt = it }
        frameCount++
        coreSumNanos += coreNanos
        conversionSumNanos += conversionNanos
        val elapsed = timestampNanos - start
        if (elapsed < 1_000_000_000L || frameCount == 0) return null
        val result = GbVideoMetrics(
            actualFps = frameCount.toDouble() * 1_000_000_000.0 / elapsed,
            targetFps = targetFps,
            coreMs = coreSumNanos.toDouble() / frameCount / 1_000_000.0,
            conversionMs = conversionSumNanos.toDouble() / frameCount / 1_000_000.0,
        )
        startedAt = timestampNanos
        frameCount = 0
        coreSumNanos = 0L
        conversionSumNanos = 0L
        return result
    }
}
