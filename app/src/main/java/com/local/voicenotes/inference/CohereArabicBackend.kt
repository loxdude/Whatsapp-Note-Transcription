package com.local.voicenotes.inference

import android.os.SystemClock
import android.util.Log
import com.local.voicenotes.concurrent.runCatchingCancellable
import com.local.voicenotes.domain.ImportedModel
import com.local.voicenotes.domain.LanguageOption
import com.local.voicenotes.model.CohereArabicModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import kotlin.coroutines.coroutineContext

/**
 * Cohere Transcribe Arabic on the CPU through CrispASR/ggml (libvoicenotes_cohere.so). The
 * model is loaded once by [prepare] and kept like the Parakeet session; every native call runs
 * on one thread, so [close] never frees the model under a running transcription.
 *
 * Cancellation is checked between 30 s windows, so a cancelled run may keep the CPU busy for
 * up to one window; its result is discarded.
 */
class CohereArabicBackend(private val threads: () -> Int) : TranscriptionBackend {
    private companion object { const val TAG = "VoiceNotesCohere" }

    private val executor = Executors.newSingleThreadExecutor { Thread(it, "CohereArabic") }
    private val dispatcher = executor.asCoroutineDispatcher()

    /** Measured compute time per second of audio on this phone; starts with a mid-range guess. */
    @Volatile private var msPerSecond = CohereProgressEstimate.DEFAULT_MS_PER_SECOND

    // Touched only on the executor thread.
    private var handle = 0L
    private var handlePath: String? = null

    override suspend fun validate(model: ImportedModel): Result<Unit> = withContext(Dispatchers.IO) {
        runCatchingCancellable {
            require(CohereArabicModel.isArabic(model)) { "Not the Arabic speech model." }
            val file = File(model.path)
            require(file.isFile) { "The Arabic speech model is missing. Download it again in Settings." }
            require(file.length() == model.sizeBytes) { "The Arabic speech model is incomplete. Remove it and download it again." }
        }
    }

    override suspend fun prepare(model: ImportedModel): Result<Unit> = withContext(dispatcher) {
        runCatchingCancellable {
            validate(model).getOrThrow()
            ensureLoaded(model)
            Unit
        }
    }

    override suspend fun transcribe(
        model: ImportedModel,
        pcm16KhzMono: FloatArray,
        language: LanguageOption,
        onProgress: (Float) -> Unit
    ): String = coroutineScope {
        // Native code reports progress only after each 30 s window, so a ticker interpolates
        // within the running window from the measured speed of this phone.
        val estimate = CohereProgressEstimate(pcm16KhzMono.size, msPerSecond)
        val ticker = launch(Dispatchers.Default) {
            while (isActive) {
                delay(250)
                onProgress(estimate.fraction(SystemClock.elapsedRealtime()))
            }
        }
        try {
            transcribeOnEngine(model, pcm16KhzMono, estimate, onProgress)
        } finally {
            ticker.cancel()
        }
    }

    private suspend fun transcribeOnEngine(
        model: ImportedModel,
        pcm16KhzMono: FloatArray,
        estimate: CohereProgressEstimate,
        onProgress: (Float) -> Unit
    ): String = withContext(dispatcher) {
        // The Arabic finetune is always prompted as Arabic; the app's language option is ignored.
        val job = coroutineContext.job
        val loaded = ensureLoaded(model)
        val started = SystemClock.elapsedRealtime()
        estimate.start(started)
        val control = CohereControl { done, total ->
            estimate.window(done, total, SystemClock.elapsedRealtime())
            if (total > 0) onProgress(estimate.fraction(SystemClock.elapsedRealtime()))
            job.isActive
        }
        val bytes = try {
            NativeCohere.transcribe(loaded, pcm16KhzMono, CohereArabicModel.LANGUAGE, control)
        } catch (error: OutOfMemoryError) {
            throw IllegalStateException("Not enough memory for Arabic transcription.", error)
        }
        estimate.measured()?.let { msPerSecond = it }
        if (bytes == null || !job.isActive) throw CancellationException("Transcription cancelled.")
        val text = CohereText.clean(String(bytes, Charsets.UTF_8))
        Log.i(TAG, "transcribe samples=${pcm16KhzMono.size} total=${SystemClock.elapsedRealtime() - started}ms")
        text
    }

    /** Runs on the executor thread. */
    private fun ensureLoaded(model: ImportedModel): Long {
        if (handle != 0L && handlePath == model.path) return handle
        unload()
        NativeCohere.loadError?.let { throw IllegalStateException("The Arabic speech engine is not available on this phone.", it) }
        val count = threads()
        val started = SystemClock.elapsedRealtime()
        val opened = try {
            NativeCohere.open(model.path, count)
        } catch (error: OutOfMemoryError) {
            throw IllegalStateException("Not enough memory to load the Arabic speech model.", error)
        }
        if (opened == 0L) throw IllegalStateException("The Arabic speech model could not be loaded. Remove it and download it again.")
        Log.i(TAG, "loaded threads=$count in ${SystemClock.elapsedRealtime() - started}ms")
        handle = opened
        handlePath = model.path
        return opened
    }

    private fun unload() {
        if (handle != 0L) NativeCohere.close(handle)
        handle = 0L
        handlePath = null
    }

    /** Cancellation is per call (the calling coroutine); nothing else to abort. */
    override fun cancel() = Unit

    /** Frees the model after any running call; a later [prepare] or [transcribe] loads it again. */
    override fun close() {
        executor.execute { unload() }
    }

    /** Suspending [close] for removal: returns once the model file is no longer in use. */
    suspend fun release() = withContext(dispatcher) { unload() }
}
