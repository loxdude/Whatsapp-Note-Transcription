package com.local.voicenotes.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class StreamingResamplerTest {
    private fun tone(rate: Int, freq: Double, frames: Int, amp: Double = 0.5) =
        FloatArray(frames) { (amp * sin(2 * PI * freq * it / rate)).toFloat() }

    private fun resampleInChunks(r: StreamingResampler, input: FloatArray, chunkSizes: (Int) -> Int): FloatArray {
        val out = ArrayList<FloatArray>()
        var pos = 0
        var i = 0
        while (pos < input.size) {
            val n = chunkSizes(i++).coerceIn(1, input.size - pos)
            out += r.process(input, pos, n)
            pos += n
        }
        out += r.flush()
        val result = FloatArray(out.sumOf { it.size })
        var o = 0
        for (c in out) { c.copyInto(result, o); o += c.size }
        return result
    }

    /** RMS over the middle of the signal, avoiding filter edge transients. */
    private fun midRms(x: FloatArray): Double {
        val from = x.size / 4
        val to = x.size * 3 / 4
        var s = 0.0
        for (i in from until to) s += x[i].toDouble() * x[i]
        return sqrt(s / (to - from))
    }

    @Test
    fun totalLengthMatchesRoundedRatioForCommonRates() {
        for (rate in intArrayOf(44_100, 48_000, 22_050, 8_000, 11_025, 32_000, 47_999, 96_000)) {
            for (frames in intArrayOf(0, 1, 7, 1023, 1024 * 37 + 5, rate * 60)) {
                val r = StreamingResampler(rate, 16_000)
                val out = resampleInChunks(r, FloatArray(frames), { 1024 })
                val expected = (frames.toDouble() * 16_000 / rate).roundToLong()
                assertEquals("rate=$rate frames=$frames", expected, out.size.toLong())
            }
        }
    }

    @Test
    fun outputIsIndependentOfBufferSplit() {
        val rnd = Random(42)
        for (rate in intArrayOf(44_100, 48_000, 22_050, 8_000, 47_999)) {
            val input = FloatArray(rate * 2 + 333) { rnd.nextFloat() * 2 - 1 }
            val whole = resampleInChunks(StreamingResampler(rate, 16_000), input, { input.size })
            val small = resampleInChunks(StreamingResampler(rate, 16_000), input, { 1 + (it * 7919) % 97 })
            val codec = resampleInChunks(StreamingResampler(rate, 16_000), input, { 1024 })
            val rndSplit = Random(7)
            val random = resampleInChunks(StreamingResampler(rate, 16_000), input, { 1 + rndSplit.nextInt(5000) })
            assertArrayEquals("rate=$rate small", whole, small, 1e-6f)
            assertArrayEquals("rate=$rate 1024", whole, codec, 1e-6f)
            assertArrayEquals("rate=$rate random", whole, random, 1e-6f)
        }
    }

    @Test
    fun identityPathAt16kPassesSamplesThrough() {
        val input = FloatArray(5000) { it / 5000f }
        val out = resampleInChunks(StreamingResampler(16_000, 16_000), input, { 1024 })
        assertArrayEquals(input, out, 0f)
    }

    @Test
    fun tenKilohertzAt48kIsAttenuatedAtLeast40dB() {
        for (rate in intArrayOf(48_000, 44_100)) {
            val input = tone(rate, 10_000.0, rate * 2)
            val out = resampleInChunks(StreamingResampler(rate, 16_000), input, { 1024 })
            val attenuationDb = 20 * log10(midRms(out) / midRms(input))
            assertTrue("rate=$rate attenuation=$attenuationDb dB", attenuationDb <= -40.0)
        }
    }

    @Test
    fun oneKilohertzPassbandIsPreservedWithinHalfDecibel() {
        for (rate in intArrayOf(48_000, 44_100, 22_050, 8_000)) {
            val input = tone(rate, 1_000.0, rate * 2)
            val out = resampleInChunks(StreamingResampler(rate, 16_000), input, { 1024 })
            val gainDb = 20 * log10(midRms(out) / midRms(input))
            assertTrue("rate=$rate gain=$gainDb dB", abs(gainDb) <= 0.5)
            // Phase/timing: output should match the ideal 1 kHz tone at 16 kHz (no delay or drift).
            val ideal = tone(16_000, 1_000.0, out.size)
            var maxErr = 0.0
            for (i in out.size / 4 until out.size * 3 / 4) maxErr = maxOf(maxErr, abs(out[i] - ideal[i]).toDouble())
            assertTrue("rate=$rate max error vs ideal=$maxErr", maxErr < 0.03)
        }
    }


}
