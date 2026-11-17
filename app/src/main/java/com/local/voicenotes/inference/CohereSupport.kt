package com.local.voicenotes.inference

import java.io.File

/** Called from native code between 30 s windows; returning false stops the transcription. */
internal fun interface CohereControl {
    fun onChunk(done: Int, total: Int): Boolean
}

/** JNI surface of libvoicenotes_cohere.so (CrispASR Cohere backend). Names are kept by R8. */
internal object NativeCohere {
    val loadError: Throwable? by lazy {
        try { System.loadLibrary("voicenotes_cohere"); null }
        catch (error: LinkageError) { error }
        catch (error: SecurityException) { error }
    }

    /** Returns a handle, or 0 if the model could not be loaded. */
    external fun open(modelPath: String, threads: Int): Long
    /** UTF-8 transcript, or null when [control] asked to stop. */
    external fun transcribe(handle: Long, pcm: FloatArray, language: String, control: CohereControl): ByteArray?
    external fun close(handle: Long)
}

/** Pure helpers for the Cohere backend. */
object CohereText {
    // Non-speech markers the model emits, e.g. <hesitation>; never longer than a short label.
    private val tag = Regex("<[^<>\\n]{1,40}>")
    private val spaces = Regex("[ \\t]{2,}")

    /** Removes `<...>` markers and the double spaces they leave behind. */
    fun clean(text: String): String =
        text.replace(tag, " ").replace(spaces, " ").lines().joinToString("\n") { it.trim() }.trim()
}

object CpuCores {
    /**
     * Threads for ggml on a big.LITTLE phone: the cores faster than the slowest cluster, clamped
     * to 2..6. Using the little cores as well makes every step wait for them (8 threads on a
     * Snapdragon 8 Gen 3 run about 6x slower than 6). [maxFreqsKhz] has one entry per core.
     */
    fun inferenceThreads(maxFreqsKhz: List<Long>): Int {
        val known = maxFreqsKhz.filter { it > 0 }
        if (known.isEmpty()) return 4
        val slowest = known.min()
        val fast = known.count { it > slowest }
        return (if (fast == 0) known.size else fast).coerceIn(2, 6)
    }

    /** Reads cpuinfo_max_freq of every core; cores without the file are skipped. */
    fun readMaxFreqsKhz(root: File = File("/sys/devices/system/cpu")): List<Long> =
        (root.listFiles { file -> file.name.matches(Regex("cpu\\d+")) } ?: emptyArray())
            .mapNotNull { core -> runCatching { File(core, "cpufreq/cpuinfo_max_freq").readText().trim().toLong() }.getOrNull() }
}

/**
 * Smooth progress for Cohere, whose native loop reports only after each window. Between
 * reports the fraction grows with elapsed time at [msPerSecond] of compute per second of
 * audio, but never past 95 % of the running window, so a slow window waits instead of
 * overshooting. Thread-safe: native callbacks and the UI ticker run on different threads.
 */
class CohereProgressEstimate(private val samples: Int, private val msPerSecond: Long) {
    companion object {
        const val DEFAULT_MS_PER_SECOND = 600L
        private const val SAMPLE_RATE = 16_000
    }

    private var startedAt = -1L
    private var windowStartedAt = -1L
    private var done = 0
    private var total = 0

    @Synchronized fun start(nowMs: Long) { startedAt = nowMs; windowStartedAt = nowMs }

    @Synchronized fun window(done: Int, total: Int, nowMs: Long) {
        if (total <= 0) return
        if (done != this.done || this.total == 0) windowStartedAt = nowMs
        this.done = done
        this.total = total
    }

    @Synchronized fun fraction(nowMs: Long): Float {
        if (startedAt < 0) return 0f
        val windows = if (total > 0) total else maxOf(1, (samples + 30 * SAMPLE_RATE - 1) / (30 * SAMPLE_RATE))
        if (done >= windows) return 1f
        // Windows are 30 s except the last one, so weight each by its audio length.
        val window = 30f * SAMPLE_RATE
        val doneSamples = minOf(samples.toFloat(), done * window)
        val currentSamples = minOf(window, samples - doneSamples).coerceAtLeast(1f)
        val expectedMs = (currentSamples / SAMPLE_RATE * msPerSecond).coerceAtLeast(500f)
        val inWindow = creep((nowMs - windowStartedAt) / expectedMs)
        return ((doneSamples + inWindow * currentSamples) / samples).coerceIn(0.01f, 1f)
    }

    /**
     * Linear up to 80 % of the expected window time, then approaches 95 % ever more slowly, so
     * a phone slower than the estimate still sees the bar move instead of a frozen value.
     */
    private fun creep(ratio: Float): Float {
        if (ratio <= 0f) return 0f
        if (ratio <= 0.8f) return ratio
        return 0.95f - 0.15f * kotlin.math.exp(-(ratio - 0.8f) / 1.5f)
    }

    /** Compute ms per audio second of the finished run, or null if it did not finish. */
    @Synchronized fun measured(): Long? {
        if (total == 0 || done < total || startedAt < 0 || samples < SAMPLE_RATE) return null
        return ((windowStartedAt - startedAt) * SAMPLE_RATE / samples).coerceIn(50L, 5_000L)
    }
}
