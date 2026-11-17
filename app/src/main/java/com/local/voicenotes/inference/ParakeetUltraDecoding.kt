package com.local.voicenotes.inference

/*
 * Pure Kotlin parts of the Parakeet Ultra pipeline (litert-community/parakeet-ultra): window
 * planning, overlap stitching and the greedy TDT loop. No Android or LiteRT types, so the exact
 * semantics of the reference implementation can be unit tested on the JVM.
 */

/** One token emitted by the TDT decoder at encoder [frame] (80 ms per frame) of its window. */
internal data class UltraEmission(val frame: Int, val token: Int)

/** The prediction and joint networks of the decoder graph, injected so the loop is testable. */
internal interface UltraTdtNetwork<S> {
    /** predict(BLANK, h = 0, c = 0): the state every window starts from. */
    fun start(): S

    /** predict(token, h_out, c_out of [state]). */
    fun predict(token: Int, state: S): S

    /** joint(enc row [frame], dec_out of [state]): 8,193 token logits (8192 = blank) + 5 duration logits. */
    fun joint(frame: Int, state: S): FloatArray
}

internal object UltraTdt {
    const val BLANK = 8192
    const val EOS = 3
    const val TOKEN_LOGITS = 8193
    const val DURATION_LOGITS = 5
    const val LOGITS = TOKEN_LOGITS + DURATION_LOGITS
    const val MAX_STEPS = 3750
    const val MAX_SYMBOLS_PER_FRAME = 10

    /**
     * Greedy TDT decoding of one window, as the model card and the reference runner define it:
     * start from blank with zero state; per step take the arg-max token and duration; a blank
     * with duration 0 moves one frame; a non-blank token is emitted and fed to predict; token 3
     * ends the window; ten tokens on one frame move one frame; stop at [validFrames] (the sum of
     * the mel graph's frame_mask) or after [MAX_STEPS] steps.
     */
    fun <S> decode(
        validFrames: Int,
        network: UltraTdtNetwork<S>,
        checkCancelled: () -> Unit = {}
    ): List<UltraEmission> {
        val out = ArrayList<UltraEmission>()
        var state = network.start()
        var frame = 0
        var steps = 0
        var run = 0
        while (frame < validFrames && steps < MAX_STEPS) {
            checkCancelled()
            steps++
            val logits = network.joint(frame, state)
            require(logits.size >= LOGITS) { "Unexpected Parakeet Ultra joint output size." }
            val token = argmax(logits, 0, TOKEN_LOGITS)
            var duration = argmax(logits, TOKEN_LOGITS, DURATION_LOGITS)
            if (token == BLANK && duration == 0) duration = 1
            if (token != BLANK) {
                if (token == EOS) break
                out += UltraEmission(frame, token)
                state = network.predict(token, state)
            }
            if (duration == 0) {
                run++
                if (run >= MAX_SYMBOLS_PER_FRAME) duration = 1
            }
            if (duration > 0) run = 0
            frame += duration
        }
        return out
    }

    /** First index of the maximum in logits[from, from + count), like numpy.argmax. */
    private fun argmax(logits: FloatArray, from: Int, count: Int): Int {
        var best = 0
        var bestValue = logits[from]
        for (index in 1 until count) {
            val value = logits[from + index]
            if (value > bestValue) {
                bestValue = value
                best = index
            }
        }
        return best
    }
}

/**
 * A window of the input audio and the time range (seconds, absolute) whose tokens it keeps.
 * The first window keeps from 0, the last one up to the end; in between, each overlap is split
 * in its middle.
 */
internal data class UltraWindow(
    val index: Int,
    val startSample: Int,
    val lengthSamples: Int,
    val keepFromSeconds: Double,
    val keepUntilSeconds: Double
) {
    val startSeconds: Double get() = startSample / UltraWindows.SAMPLE_RATE.toDouble()

    /** True if a token at [frame] of this window lies in the window's own region. */
    fun keeps(frame: Int): Boolean {
        val time = startSeconds + frame * UltraWindows.FRAME_SECONDS
        return keepFromSeconds <= time && time < keepUntilSeconds
    }
}

internal object UltraWindows {
    const val SAMPLE_RATE = 16_000
    const val FRAME_SECONDS = 0.08
    /** Overlap of consecutive windows: hop = window - 2 s. */
    const val OVERLAP_SAMPLES = 2 * SAMPLE_RATE
    /** A window under two mel frames has no text (model card); only possible for tiny inputs. */
    const val MIN_WINDOW_SAMPLES = 320
    /**
     * Only the 30 s graphs are supported: shorter windows cost more overlap compute and
     * accuracy for no practical speed gain on the tested phones.
     */
    val SUPPORTED_SECONDS = listOf(30)

    /** Samples per window of a graph: the 30 s graph uses 479,840 samples (model card). */
    fun windowSamples(seconds: Int): Int = when (seconds) {
        30 -> 479_840
        else -> throw IllegalArgumentException("Unsupported Parakeet Ultra window: $seconds s")
    }

    /** Input length of the mel graph of a bucket (audio [1, seconds * 16000]). */
    fun graphSamples(seconds: Int): Int {
        windowSamples(seconds)
        return seconds * SAMPLE_RATE
    }

    fun hopSamples(seconds: Int): Int = seconds * SAMPLE_RATE - OVERLAP_SAMPLES

    /**
     * Audio up to one window: a single (padded) window. Longer audio: windows every hop, the
     * last one ending at or after the end of the audio. Each window keeps the tokens of its own
     * region, bounded by the middle of the overlap on each side.
     */
    fun plan(totalSamples: Int, seconds: Int): List<UltraWindow> {
        require(totalSamples >= 0) { "Negative audio length." }
        val window = windowSamples(seconds)
        val hop = hopSamples(seconds)
        val starts = arrayListOf(0)
        while (starts.last().toLong() + window < totalSamples) starts += starts.last() + hop
        val margin = (window - hop) / 2.0 / SAMPLE_RATE
        return starts.mapIndexed { index, start ->
            val startSeconds = start / SAMPLE_RATE.toDouble()
            UltraWindow(
                index = index,
                startSample = start,
                lengthSamples = minOf(window, totalSamples - start),
                keepFromSeconds = if (index == 0) 0.0 else startSeconds + margin,
                keepUntilSeconds = if (index == starts.lastIndex) Double.POSITIVE_INFINITY
                else startSeconds + window / SAMPLE_RATE.toDouble() - margin
            )
        }
    }

    /** Concatenates the tokens each window keeps, in window order. */
    fun stitch(windows: List<UltraWindow>, emissions: List<List<UltraEmission>>): List<Int> {
        require(windows.size == emissions.size) { "One emission list per window." }
        return windows.indices.flatMap { index ->
            emissions[index].filter { windows[index].keeps(it.frame) }.map { it.token }
        }
    }
}
