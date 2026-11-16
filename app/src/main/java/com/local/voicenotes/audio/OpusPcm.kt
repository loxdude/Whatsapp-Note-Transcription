package com.local.voicenotes.audio

import java.util.concurrent.CancellationException

/** libopusfile supplies 48 kHz mono float PCM with pre-skip, gain and end trim applied. */
internal interface OpusPcmSource : AutoCloseable {
    val totalFrames: Long
    fun read(target: FloatArray): Int
}

internal class NativeOpusException(message: String) : Exception(message)

/** Platform-independent bounded/cancellable adapter, using the existing bandlimited resampler. */
internal object OpusPcm {
    const val RATE = 48_000
    const val MAX_INPUT_FRAMES = RATE * 60L * 15

    fun checkNativeFailure(code: Int, checkCancelled: () -> Unit) {
        checkCancelled()
        when (code) {
            -10001 -> throw IllegalArgumentException("Opus input exceeds native parser safety limits.")
            -10002 -> throw CancellationException("Opus decoding cancelled.")
            else -> if (code < 0) throw NativeOpusException("Opus decode failed ($code); retrying with Android.")
        }
    }

    fun decode(
        source: OpusPcmSource,
        onProgress: (Float) -> Unit,
        checkCancelled: () -> Unit,
        maxInputFrames: Long = MAX_INPUT_FRAMES
    ): FloatArray {
        require(source.totalFrames <= maxInputFrames) { "Audio is longer than the 15-minute limit." }
        val capacity = if (source.totalFrames > 0)
            (source.totalFrames / 3 + 4096).coerceAtMost(16_000L * 60 * 15).toInt()
        else 16_000 * 60
        val output = FloatAccumulator(capacity, 16_000 * 60 * 15)
        val resampler = StreamingResampler(RATE, 16_000)
        val buffer = FloatArray(5760)
        var frames = 0L
        var progressAt = System.nanoTime()
        while (true) {
            checkCancelled()
            val n = source.read(buffer)
            checkNativeFailure(n, checkCancelled)
            if (n > buffer.size) throw NativeOpusException("Invalid Opus PCM frame count: $n")
            if (n == 0) break
            frames += n
            require(frames <= maxInputFrames) { "Audio is longer than the 15-minute limit." }
            output.append(resampler.process(if (n == buffer.size) buffer else buffer.copyOf(n)))
            val now = System.nanoTime()
            if (source.totalFrames > 0 && now - progressAt >= 100_000_000L) {
                onProgress((frames.toFloat() / source.totalFrames).coerceIn(0f, 1f))
                progressAt = now
            }
        }
        checkCancelled()
        output.append(resampler.flush())
        onProgress(1f)
        return output.toArray()
    }
}
