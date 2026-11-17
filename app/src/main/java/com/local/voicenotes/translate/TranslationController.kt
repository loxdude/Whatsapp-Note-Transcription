package com.local.voicenotes.translate

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/** Loads a translation model once and translates one chunk at a time. */
interface TextTranslator {
    /** Loads the model if needed; may take tens of seconds the first time. */
    suspend fun load()
    suspend fun translate(text: String): String
}

/** Translation of the transcript [transcriptId]; the UI shows it only for the transcript on screen. */
sealed interface TranslationState {
    val transcriptId: String?

    data object Idle : TranslationState { override val transcriptId: String? = null }
    data class Loading(override val transcriptId: String) : TranslationState
    data class Translating(override val transcriptId: String, val done: Int, val total: Int) : TranslationState {
        val fraction: Float get() = if (total <= 0) 0f else done.toFloat() / total
    }
    data class Done(override val transcriptId: String, val text: String) : TranslationState
    data class Failed(override val transcriptId: String, val message: String) : TranslationState
}

val TranslationState.running: Boolean get() = this is TranslationState.Loading || this is TranslationState.Translating

/**
 * Runs one translation at a time on [scope] (main thread). A new request or [cancel] stops the
 * previous one; a stopped run never writes state again.
 */
class TranslationController(
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = {}
) {
    private val mutableState = MutableStateFlow<TranslationState>(TranslationState.Idle)
    val state: StateFlow<TranslationState> = mutableState.asStateFlow()
    private var job: Job? = null
    private var generation = 0L

    /** Translates [text] of transcript [transcriptId]; [onDone] persists the result. */
    fun start(transcriptId: String, text: String, translator: TextTranslator, onDone: (String) -> Unit): Boolean {
        val chunks = TranslationText.chunks(text)
        if (chunks.isEmpty()) return false
        job?.cancel(CancellationException("Superseded by a new translation."))
        val token = ++generation
        mutableState.value = TranslationState.Loading(transcriptId)
        job = scope.launch {
            fun write(state: TranslationState) { if (token == generation) mutableState.value = state }
            try {
                translator.load()
                val results = ArrayList<String>(chunks.size)
                write(TranslationState.Translating(transcriptId, 0, chunks.size))
                for (chunk in chunks) {
                    coroutineContext.ensureActive()
                    results += translator.translate(chunk.text)
                    write(TranslationState.Translating(transcriptId, results.size, chunks.size))
                }
                coroutineContext.ensureActive()
                val joined = TranslationText.join(chunks, results)
                if (token == generation) {
                    mutableState.value = TranslationState.Done(transcriptId, joined)
                    onDone(joined)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: OutOfMemoryError) {
                log("Translation failed: out of memory")
                write(TranslationState.Failed(transcriptId, "Not enough memory for the translation model."))
            } catch (error: Exception) {
                log("Translation failed: ${error.message}")
                write(TranslationState.Failed(transcriptId, error.message ?: "Translation failed."))
            }
        }
        return true
    }

    /** Stops the running translation and clears its progress. A finished translation stays. */
    fun cancel() {
        val running = job?.takeIf { it.isActive } ?: return
        generation++
        running.cancel(CancellationException("Translation cancelled."))
        if (mutableState.value.running) mutableState.value = TranslationState.Idle
    }

    val isRunning: Boolean get() = job?.isActive == true
}
