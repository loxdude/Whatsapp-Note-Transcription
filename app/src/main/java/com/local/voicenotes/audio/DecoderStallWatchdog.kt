package com.local.voicenotes.audio

import java.io.IOException

/**
 * Fails a MediaCodec loop that makes no progress (no input queued, no output buffer or
 * format change) for [timeoutNanos], e.g. a codec that keeps answering
 * INFO_TRY_AGAIN_LATER after input end of stream and never signals output end of stream.
 */
internal class DecoderStallWatchdog(
    private val timeoutNanos: Long = DEFAULT_TIMEOUT_NANOS,
    private val nowNanos: () -> Long = System::nanoTime
) {
    companion object {
        const val DEFAULT_TIMEOUT_NANOS = 10_000_000_000L
    }

    private var lastProgress = nowNanos()

    fun progressed() {
        lastProgress = nowNanos()
    }

    @Throws(IOException::class)
    fun check() {
        if (nowNanos() - lastProgress >= timeoutNanos) throw IOException("The audio decoder stopped responding.")
    }
}
