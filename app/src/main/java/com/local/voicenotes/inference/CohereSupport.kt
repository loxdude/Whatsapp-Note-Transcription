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
