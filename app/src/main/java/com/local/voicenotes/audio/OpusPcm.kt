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

    /** Mirrors the native MAX_INPUT_BYTES cap on confirmed Ogg Opus input. */
    const val MAX_INPUT_BYTES = 64L * 1024 * 1024

    /**
     * Length to hand to the native decoder. A declared length wins; otherwise the size of a
     * regular file ([statSize], -1 if the fd cannot be stat'ed as one) minus the asset
     * offset, so the native 64 MiB cap also applies when the provider reports no length.
     * -1 keeps the native aggregate read budget as the only bound.
     */
    fun nativeInputLength(declared: Long, startOffset: Long, statSize: Long): Long = when {
        declared >= 0 -> declared
        statSize >= 0 -> (statSize - startOffset).coerceAtLeast(0)
        else -> -1
    }

    /**
     * Applied after native opening identified Ogg Opus when no length reached native code:
     * a provider-reported [size] above the cap is rejected like a known oversize length.
     */
    fun checkReportedSize(size: Long?) {
        if (size != null && size > MAX_INPUT_BYTES) {
            throw IllegalArgumentException("Opus input exceeds native parser safety limits.")
        }
    }

    fun capacityForFrames(frames: Long): Int = if (frames > 0)
        ((frames + 1) / 3).coerceIn(1, 16_000L * 60 * 15).toInt() else 16_000 * 60

    fun decode(
        source: OpusPcmSource,
        onProgress: (Float) -> Unit,
        checkCancelled: () -> Unit,
        maxInputFrames: Long = MAX_INPUT_FRAMES,
        nowNanos: () -> Long = System::nanoTime
    ): FloatArray {
        val totalFrames = source.totalFrames
        require(totalFrames <= maxInputFrames) { "Audio is longer than the 15-minute limit." }
        val capacity = capacityForFrames(totalFrames)
        val output = FloatAccumulator(capacity, 16_000 * 60 * 15)
        val resampler = StreamingResampler(RATE, 16_000)
        val buffer = FloatArray(5760)
        var frames = 0L
        var progressAt = nowNanos()
        while (true) {
            checkCancelled()
            val n = source.read(buffer)
            checkNativeFailure(n, checkCancelled)
            if (n > buffer.size) throw NativeOpusException("Invalid Opus PCM frame count: $n")
            if (n == 0) break
            frames += n
            require(frames <= maxInputFrames) { "Audio is longer than the 15-minute limit." }
            output.append(resampler.process(buffer, 0, n))
            val now = nowNanos()
            if (totalFrames > 0 && now - progressAt >= 100_000_000L) {
                onProgress((frames.toFloat() / totalFrames).coerceIn(0f, 1f))
                progressAt = now
            }
        }
        checkCancelled()
        output.append(resampler.flush())
        onProgress(1f)
        return output.toArray()
    }
}
