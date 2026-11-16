package com.local.voicenotes.audio

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.roundToLong

/** Optional host timing probe; excluded from ordinary unit tests. No hardware-dependent deadline. */
class StreamingResamplerBenchmark {
    @Test fun fifteenMinutesAt48kReportsHostTiming() {
        val rate = 48_000
        val buffer = FloatArray(1024) { kotlin.math.sin(2 * Math.PI * 440 * it / rate).toFloat() * 0.5f }
        val resampler = StreamingResampler(rate, 16_000)
        val iterations = rate * 60 * 15 / buffer.size
        val start = System.nanoTime()
        var total = 0L
        repeat(iterations) { total += resampler.process(buffer).size }
        total += resampler.flush().size
        val seconds = (System.nanoTime() - start) / 1e9
        println("15 min @48k resampled in %.2f s (JVM host)".format(seconds))
        assertEquals((iterations.toLong() * buffer.size * 16_000.0 / rate).roundToLong(), total)
    }
}
