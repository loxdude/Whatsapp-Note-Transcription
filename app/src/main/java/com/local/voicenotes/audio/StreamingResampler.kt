package com.local.voicenotes.audio

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Streaming mono resampler using a Kaiser-windowed sinc low-pass (band-limited interpolation).
 *
 * Pure Kotlin, no Android dependencies. State (fractional output position and filter history)
 * is carried across [process] calls, so the output does not depend on how the input is split
 * into chunks (results are bitwise identical for any split). Call [flush] once at end of stream;
 * the total number of emitted samples is then `round(inputFrames * outRate / inRate)`.
 *
 * Output sample n is evaluated at input time `n * inRate / outRate` using exact rational phase
 * tracking (no drift). Filter coefficients are precomputed per phase (exact rational phases when
 * the reduced output factor is small, otherwise a 1024-phase table with linear interpolation).
 *
 * When `inRate == outRate` the input is passed through unchanged.
 */
class StreamingResampler(
    val inRate: Int,
    val outRate: Int,
    /** Low-pass cutoff in Hz; clamped below the smaller Nyquist frequency. */
    cutoffHz: Double = DEFAULT_CUTOFF_HZ,
    /** Number of sinc zero crossings on each side of the kernel centre. */
    zeroCrossings: Int = DEFAULT_ZERO_CROSSINGS,
    kaiserBeta: Double = DEFAULT_KAISER_BETA
) {
    companion object {
        const val DEFAULT_CUTOFF_HZ = 7_400.0
        const val DEFAULT_ZERO_CROSSINGS = 20
        const val DEFAULT_KAISER_BETA = 8.0
        private const val MAX_EXACT_PHASES = 1024
        private const val INTERP_PHASES = 1024

        private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

        private fun besselI0(x: Double): Double {
            var sum = 1.0
            var term = 1.0
            val half = x / 2.0
            var k = 1
            while (k < 200) {
                term *= (half / k) * (half / k)
                sum += term
                if (term < sum * 1e-17) break
                k++
            }
            return sum
        }
    }

    private val identity = inRate == outRate

    /** outRate / inRate == up / down (reduced). */
    private val up: Int
    private val down: Int

    /** Kernel half-width in input samples; each output uses 2*halfWidth taps. */
    private val halfWidth: Int
    private val taps: Int
    private val phases: Int
    private val exactPhases: Boolean
    /** (phases + 1) rows of [taps] coefficients, row-major. */
    private val table: FloatArray

    private var history = FloatArray(0)
    /** Global input index of history[0]. */
    private var historyStart = 0L
    private var historyLength = 0
    private var inputCount = 0L
    private var producedCount = 0L
    /** Integer input index of the next output sample, and remainder in units of 1/up. */
    private var centre = 0L
    private var remainder = 0
    private var finished = false

    init {
        require(inRate > 0 && outRate > 0) { "Sample rates must be positive: $inRate -> $outRate" }
        require(zeroCrossings > 0) { "zeroCrossings must be positive" }
        val g = gcd(inRate, outRate)
        up = outRate / g
        down = inRate / g
        if (identity) {
            halfWidth = 0; taps = 0; phases = 0; exactPhases = true; table = FloatArray(0)
        } else {
            val nyquist = min(inRate, outRate) / 2.0
            val cutoff = min(cutoffHz, nyquist * 0.95)
            // Normalised cutoff in cycles per input sample * 2 (so 1.0 == input Nyquist).
            val fc = 2.0 * cutoff / inRate
            halfWidth = ceil(zeroCrossings / fc).toInt().coerceAtLeast(1)
            taps = 2 * halfWidth
            exactPhases = up <= MAX_EXACT_PHASES
            phases = if (exactPhases) up else INTERP_PHASES
            table = FloatArray((phases + 1) * taps)
            val i0Beta = besselI0(kaiserBeta)
            for (p in 0..phases) {
                val frac = p.toDouble() / phases
                val base = p * taps
                for (j in 0 until taps) {
                    // Tap j multiplies input sample (centre - halfWidth + 1 + j).
                    val d = frac + (halfWidth - 1 - j)
                    val x = d / halfWidth
                    val window = if (x <= -1.0 || x >= 1.0) 0.0 else besselI0(kaiserBeta * sqrt(1.0 - x * x)) / i0Beta
                    val arg = PI * fc * d
                    val sinc = if (d == 0.0) 1.0 else sin(arg) / arg
                    table[base + j] = (fc * sinc * window).toFloat()
                }
            }
            // Zero pre-history so the kernel can be centred on input index 0.
            history = FloatArray(maxOf(4096, taps * 4))
            historyLength = halfWidth - 1
            historyStart = -(halfWidth - 1).toLong()
        }
    }

    /** Total output length after flush for [inputFrames] input frames. */
    fun expectedOutputLength(inputFrames: Long): Long =
        if (identity) inputFrames else (inputFrames * up * 2 + down) / (2L * down)

    fun process(input: FloatArray, offset: Int = 0, length: Int = input.size - offset): FloatArray {
        check(!finished) { "Resampler already flushed." }
        require(offset >= 0 && length >= 0 && offset + length <= input.size)
        if (length == 0) return FloatArray(0)
        inputCount += length
        if (identity) return input.copyOfRange(offset, offset + length)
        appendHistory(input, offset, length)
        return produce(Long.MAX_VALUE)
    }

    /** Ends the stream and returns the remaining samples. */
    fun flush(): FloatArray {
        check(!finished) { "Resampler already flushed." }
        finished = true
        if (identity) return FloatArray(0)
        val total = expectedOutputLength(inputCount)
        // Zero padding past the end so the last kernels are fully supported.
        val pad = FloatArray(halfWidth + 2)
        appendHistory(pad, 0, pad.size)
        return produce(total)
    }

    private fun appendHistory(src: FloatArray, offset: Int, length: Int) {
        // Drop samples no longer needed by any future output.
        val firstNeeded = centre - halfWidth + 1
        val dead = (firstNeeded - historyStart).toInt().coerceIn(0, historyLength)
        if (dead > 0 && historyLength + length > history.size) {
            history.copyInto(history, 0, dead, historyLength)
            historyLength -= dead
            historyStart += dead
        }
        if (historyLength + length > history.size) {
            history = history.copyOf(maxOf(history.size * 2, historyLength + length))
        }
        src.copyInto(history, historyLength, offset, offset + length)
        historyLength += length
    }

    private fun produce(limit: Long): FloatArray {
        val available = historyStart + historyLength // exclusive global end
        val stepWhole = (down / up).toLong()
        val stepRem = down % up
        // Count outputs that can be produced: need centre + halfWidth < available.
        var count = 0
        var c = centre
        var r = remainder
        var n = producedCount
        while (c + halfWidth < available && n < limit) {
            count++; n++
            c += stepWhole; r += stepRem
            if (r >= up) { r -= up; c++ }
        }
        val out = FloatArray(count)
        val h = history
        val t = table
        val tapCount = taps
        for (i in 0 until count) {
            val start = (centre - halfWidth + 1 - historyStart).toInt()
            if (exactPhases) {
                val base = remainder * tapCount
                var acc = 0f
                for (j in 0 until tapCount) acc += h[start + j] * t[base + j]
                out[i] = acc
            } else {
                val pos = remainder.toDouble() * phases / up
                val p = pos.toInt()
                val w = (pos - p).toFloat()
                val b0 = p * tapCount
                val b1 = b0 + tapCount
                var a0 = 0f
                var a1 = 0f
                for (j in 0 until tapCount) {
                    val x = h[start + j]
                    a0 += x * t[b0 + j]
                    a1 += x * t[b1 + j]
                }
                out[i] = a0 + (a1 - a0) * w
            }
            centre += stepWhole; remainder += stepRem
            if (remainder >= up) { remainder -= up; centre++ }
        }
        producedCount += count
        return out
    }
}
