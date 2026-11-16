package com.local.voicenotes

import com.local.voicenotes.concurrent.runCatchingCancellable
import com.local.voicenotes.domain.ImportedModel
import com.local.voicenotes.domain.LanguageOption
import com.local.voicenotes.domain.TranscriptionProgress
import com.local.voicenotes.domain.TranscriptionResult
import com.local.voicenotes.inference.TranscriptionBackend
import com.local.voicenotes.inference.UriTranscriptionBackend
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import java.util.UUID

/** Sample rate of the PCM produced by [PcmDecoder]. */
private const val PCM_SAMPLE_RATE = 16_000L

data class AppUiState(
    val models: List<ImportedModel> = emptyList(),
    val selectedModelId: String? = null,
    /** Content or file URI string of the selected audio. */
    val audioUri: String? = null,
    val audioName: String = "",
    val language: LanguageOption = LanguageOption.AUTO,
    val progress: TranscriptionProgress = TranscriptionProgress.Idle,
    val transcript: String = "",
    val elapsedMillis: Long = 0L,
    /**
     * Identifies the completed transcription currently shown in [transcript] (it is passed to
     * [TranscriptionCoordinator]'s completion callback). Null while no completed transcript
     * is shown, e.g. after new audio was selected or a new run started.
     */
    val transcriptId: String? = null
) {
    val busy: Boolean get() = progress is TranscriptionProgress.Importing ||
        progress is TranscriptionProgress.Preparing || progress is TranscriptionProgress.Decoding ||
        progress is TranscriptionProgress.LoadingModel || progress is TranscriptionProgress.Transcribing
    val selectedModel: ImportedModel? get() = models.firstOrNull { it.id == selectedModelId }
    val canTranscribe: Boolean get() = !busy && audioUri != null && selectedModel?.enabled == true
}

/** True for models that send audio off the device (e.g. the built-in Mistral API entry). */
fun ImportedModel.isCloud(): Boolean = isApiModel || backend == "mistral-api"

/**
 * Chooses the model selection for a (re)loaded [models] list:
 * 1. keep [current] (the in-memory choice) if it is still listed and enabled;
 * 2. else restore [saved] (the persisted explicit user choice) if listed and enabled;
 * 3. else pick the first enabled *local* model.
 * A cloud model is only ever chosen in steps 1/2, i.e. because the user selected it; it is
 * never picked automatically, so audio is not uploaded without an explicit choice. Returns null
 * when no usable local model exists.
 */
fun chooseSelection(models: List<ImportedModel>, current: String?, saved: String?): String? {
    fun usable(id: String?) = id != null && models.any { it.id == id && it.enabled }
    return when {
        usable(current) -> current
        usable(saved) -> saved
        else -> models.firstOrNull { it.enabled && !it.isCloud() }?.id
    }
}

/** Persistent model list and selection. Implemented on Android by ModelRepository. */
interface ModelCatalog {
    suspend fun models(): List<ImportedModel>
    suspend fun selectedModelId(): String?
    suspend fun select(id: String)
    /** Imports the model at [source] (a URI string) and persists it without selecting it. */
    suspend fun import(source: String, onProgress: (Float) -> Unit): ImportedModel
}

/** A successfully completed transcription, reported once to the completion callback. */
data class CompletedTranscript(
    val id: String,
    val audioUri: String,
    val audioName: String,
    val modelId: String,
    val modelName: String,
    val text: String,
    val language: LanguageOption,
    val processingMillis: Long,
    /** Duration of the decoded audio; null when the backend consumed the file directly. */
    val audioDurationMillis: Long?
)

fun interface PcmDecoder {
    /** Decodes [source] (a URI string) to 16 kHz mono PCM. Must honour coroutine cancellation. */
    suspend fun decode(source: String, onProgress: (Float) -> Unit): FloatArray
}

/**
 * Owns the UI state and the import → prepare → transcribe lifecycle, independent of Android.
 *
 * Concurrency rules:
 * - Every operation that reports progress (import, model preparation, transcription) takes a
 *   fresh token from [generation]. It may only write state while its token is current. Cancel,
 *   supersession (new selection/audio) and disposal advance the generation, so late progress
 *   or completions from work that cannot be interrupted immediately (native inference, blocking
 *   I/O) are discarded instead of overwriting newer state.
 * - All state writes happen under [lock], because progress callbacks arrive on worker threads.
 * - At most one foreground operation (import or transcription) runs at a time. A cancelled
 *   operation is no longer "running" even while it unwinds, so the UI stays responsive.
 * - Cancellation is propagated by cancelling coroutine jobs; it is reported as
 *   [TranscriptionProgress.Cancelled] and never as [TranscriptionProgress.Failed].
 */
class TranscriptionCoordinator(
    private val scope: CoroutineScope,
    private val catalog: ModelCatalog,
    private val decoder: PcmDecoder,
    private val backends: Map<String, TranscriptionBackend>,
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val clockInterval: Duration = 250.milliseconds,
    private val log: (String) -> Unit = {},
    private val newTranscriptId: () -> String = { UUID.randomUUID().toString() },
    /**
     * Called (outside the state lock, on the transcription coroutine) after a transcription's
     * Completed state was published. Never called for cancelled or superseded runs. Must not
     * block; the app persists the transcript history from here, independent of any UI.
     */
    private val onTranscriptCompleted: (CompletedTranscript) -> Unit = {},
    /** Called after the user edited the transcript of completed transcription [id]. */
    private val onTranscriptEdited: (id: String, text: String) -> Unit = { _, _ -> }
) {
    private enum class Kind { IMPORT, TRANSCRIBE }

    private val lock = Any()
    private val mutableState = MutableStateFlow(AppUiState())
    val state: StateFlow<AppUiState> = mutableState.asStateFlow()

    // All fields below are guarded by [lock].
    private var generation = 0L
    private var modelsVersion = 0L
    private var foreground: Job? = null
    private var foregroundKind: Kind? = null
    private var foregroundToken = 0L
    private var prepareJob: Job? = null
    private var disposed = false

    private val selectionPersistence = Mutex()

    private inline fun <T> locked(block: () -> T): T = synchronized(lock, block)

    /** Must be called while holding [lock]. */
    private fun write(transform: (AppUiState) -> AppUiState) {
        mutableState.value = transform(mutableState.value)
    }

    private fun writeIfCurrent(token: Long, transform: (AppUiState) -> AppUiState): Boolean = locked {
        if (token != generation) return@locked false
        write(transform)
        true
    }

    /**
     * Writes the terminal state of a foreground operation and, atomically with it, stops
     * treating that operation as running, so the user can act on the result immediately
     * even though the coroutine still has to unwind (for example its clock child).
     */
    private fun finishIfCurrent(token: Long, transform: (AppUiState) -> AppUiState): Boolean = locked {
        if (token != generation) return@locked false
        write(transform)
        // A terminal state retires the token: progress callbacks that a backend still
        // delivers after returning can no longer turn Completed/Failed back into busy.
        generation++
        if (foregroundToken == token) {
            foreground = null
            foregroundKind = null
        }
        true
    }

    private fun foregroundRunning(): Boolean = foreground?.isActive == true

    // ---- User state -------------------------------------------------------------------

    /**
     * Selects new audio. A running transcription belongs to the previous audio, so it is
     * cancelled and its result discarded. Import and model preparation continue unaffected.
     */
    fun setAudio(source: String, name: String) {
        val superseded = locked {
            val running = foreground?.takeIf { it.isActive && foregroundKind == Kind.TRANSCRIBE }
            if (running != null) generation++
            write {
                it.copy(
                    audioUri = source, audioName = name, transcript = "", transcriptId = null,
                    progress = if (running != null || !it.busy) TranscriptionProgress.Idle else it.progress
                )
            }
            running
        }
        superseded?.cancel(CancellationException("Audio selection changed."))
    }

    fun setLanguage(language: LanguageOption) = locked { write { it.copy(language = language) } }

    fun editTranscript(value: String) {
        val id = locked {
            write { it.copy(transcript = value) }
            mutableState.value.transcriptId
        } ?: return
        runCatching { onTranscriptEdited(id, value) }.onFailure { log("Transcript edit hook failed: ${it.message}") }
    }

    /**
     * Selects an enabled model and prepares it, superseding any earlier preparation.
     * Rejected while an import or transcription is running (the UI also disables it).
     */
    fun selectModel(id: String): Boolean {
        val preparation = locked {
            if (disposed || foregroundRunning()) return false
            val model = mutableState.value.models.firstOrNull { it.id == id && it.enabled } ?: return false
            val select = { state: AppUiState -> state.copy(selectedModelId = id) }
            prepareLocked(model, select) ?: run { write(select); null }
        }
        persistSelection(id)
        preparation?.start()
        return true
    }

    // ---- Model list ---------------------------------------------------------------------

    fun refreshModels(): Job {
        // Snapshot versions *before* the (possibly slow) catalog reads, so a newer import,
        // selection or cancellation that lands meanwhile is not overwritten by this result.
        val (startVersion, startGeneration) = locked { modelsVersion to generation }
        return scope.launch { runRefresh(startVersion, startGeneration) }
    }

    private suspend fun runRefresh(startVersion: Long, startGeneration: Long) {
        val loaded = runCatchingCancellable { catalog.models() to catalog.selectedModelId() }
        val (models, saved) = loaded.getOrElse { error ->
            log("Model list refresh failed: ${error.message}")
            return
        }
        val toPrepare = locked {
            if (disposed) return
            // A newer list (import, reconcile, later refresh) is already published: this
            // snapshot is stale and must neither replace the list nor an existing selection.
            // It may only fill in a missing selection from the *current* list.
            if (modelsVersion != startVersion) {
                val state = mutableState.value
                if (state.selectedModelId == null) {
                    val fallback = chooseSelection(state.models, current = null, saved = saved)
                    if (fallback != null) write { it.copy(selectedModelId = fallback) }
                }
                return
            }
            // A refresh can finish after the user has selected a model. Keep that in-memory
            // choice instead of restoring the older persisted selection over it.
            val current = mutableState.value.selectedModelId
            val selected = chooseSelection(models, current, saved)
            modelsVersion++
            val publish = { state: AppUiState -> state.copy(models = models, selectedModelId = selected) }
            // An import or transcription owns the progress; it will prepare when needed.
            // If any operation started, finished or was cancelled since the refresh began,
            // its terminal state (e.g. Cancelled) stays visible; the backend still prepares
            // lazily on the next transcription.
            val preparation = if (foregroundRunning() || generation != startGeneration) null
            else models.firstOrNull { it.id == selected }?.let { prepareLocked(it, publish) }
            if (preparation == null) write(publish)
            preparation
        }
        toPrepare?.start()
    }

    // ---- Import -----------------------------------------------------------------------

    fun importModel(source: String): Boolean {
        val job = locked {
            if (disposed || mutableState.value.busy || foregroundRunning()) return false
            val token = ++generation
            write { it.copy(progress = TranscriptionProgress.Importing(0f)) }
            scope.launch(start = CoroutineStart.LAZY) { runImport(token, source) }
                .also { trackForeground(it, Kind.IMPORT, token) }
        }
        job.start()
        return true
    }

    private suspend fun runImport(token: Long, source: String) {
        try {
            val imported = catalog.import(source) { fraction ->
                writeIfCurrent(token) {
                    if (it.progress is TranscriptionProgress.Importing) {
                        it.copy(progress = TranscriptionProgress.Importing(fraction))
                    } else it
                }
            }
            val models = catalog.models()
            kotlin.coroutines.coroutineContext.ensureActive()
            val entry = models.firstOrNull { it.id == imported.id } ?: imported
            // One locked step publishes the list, the selection and (for a usable model) the
            // preparation's LoadingModel state. There is no transient Idle in between, so an
            // observer (e.g. a pending share) can never start a transcription that the
            // preparation would then supersede.
            var preparation: Job? = null
            val accepted = locked {
                if (token != generation) return@locked false
                if (foregroundToken == token) {
                    foreground = null
                    foregroundKind = null
                }
                val current = mutableState.value.selectedModelId
                val selected = if (entry.enabled) entry.id else chooseSelection(models, current, saved = null)
                modelsVersion++
                val publish = { state: AppUiState -> state.copy(models = models, selectedModelId = selected) }
                if (entry.enabled) preparation = prepareLocked(entry, publish)
                if (preparation == null) write {
                    publish(it).copy(
                        progress = if (entry.enabled) TranscriptionProgress.Idle
                        else TranscriptionProgress.Failed(entry.note.ifBlank { "This model cannot be used." })
                    )
                }
                true
            }
            if (accepted && entry.enabled) {
                persistSelection(entry.id)
                preparation?.start()
            }
        } catch (cancellation: CancellationException) {
            finishIfCurrent(token) { it.copy(progress = TranscriptionProgress.Cancelled) }
            // The import may have been persisted just before cancellation took effect.
            reconcileModelList()
            throw cancellation
        } catch (error: Throwable) {
            if (!kotlin.coroutines.coroutineContext.isActive) {
                finishIfCurrent(token) { it.copy(progress = TranscriptionProgress.Cancelled) }
                reconcileModelList()
                throw CancellationException("Model import cancelled.").apply { initCause(error) }
            }
            finishIfCurrent(token) {
                it.copy(progress = TranscriptionProgress.Failed(error.message ?: "Model import failed."))
            }
        }
    }

    /** Updates only the model list (never selection or progress) unless a newer list was written. */
    private fun reconcileModelList() {
        val version = locked { if (disposed) return; modelsVersion }
        scope.launch {
            val models = runCatchingCancellable { catalog.models() }.getOrNull() ?: return@launch
            locked {
                if (!disposed && version == modelsVersion) {
                    modelsVersion++
                    write { it.copy(models = models) }
                }
            }
        }
    }

    // ---- Preparation ------------------------------------------------------------------

    /**
     * Must be called while holding [lock]. Publishes [update] together with LoadingModel as ONE
     * state (observers such as a pending share never see an intermediate Idle) and returns the
     * lazy, not yet started preparation job. Returns null and writes nothing when nothing may
     * be prepared: after disposal, when [model] would not be selected, or while an import or
     * transcription runs. Preparation never supersedes running foreground work (that would
     * invalidate its token and discard its result); a transcription prepares its model
     * itself, lazily.
     */
    private fun prepareLocked(model: ImportedModel, update: (AppUiState) -> AppUiState = { it }): Job? {
        val next = update(mutableState.value)
        if (disposed || next.selectedModelId != model.id || foregroundRunning()) return null
        // Retire the old token first, so the superseded preparation can no longer write.
        val token = ++generation
        prepareJob?.cancel(CancellationException("Model selection changed."))
        mutableState.value = next.copy(progress = TranscriptionProgress.LoadingModel)
        return scope.launch(start = CoroutineStart.LAZY) { runPrepare(token, model) }
            .also { prepareJob = it }
    }

    private suspend fun runPrepare(token: Long, model: ImportedModel) {
        try {
            val backend = backends[model.backend]
                ?: throw IllegalStateException("No backend available for this model type.")
            backend.prepare(model).getOrThrow()
            kotlin.coroutines.coroutineContext.ensureActive()
            writeIfCurrent(token) { it.copy(progress = TranscriptionProgress.Idle) }
        } catch (cancellation: CancellationException) {
            writeIfCurrent(token) { it.copy(progress = TranscriptionProgress.Cancelled) }
            throw cancellation
        } catch (error: Throwable) {
            if (!kotlin.coroutines.coroutineContext.isActive) {
                throw CancellationException("Model preparation cancelled.").apply { initCause(error) }
            }
            writeIfCurrent(token) {
                if (it.selectedModelId == model.id) {
                    it.copy(progress = TranscriptionProgress.Failed(error.message ?: "Model preparation failed."))
                } else it.copy(progress = TranscriptionProgress.Idle)
            }
        }
    }

    // ---- Transcription ----------------------------------------------------------------

    fun transcribe(): Boolean {
        val job = locked {
            if (disposed || foregroundRunning()) return false
            val snapshot = mutableState.value
            if (!snapshot.canTranscribe) return false
            val audio = snapshot.audioUri ?: return false
            val model = snapshot.selectedModel ?: return false
            val token = ++generation
            // Set synchronously so a second tap cannot start a concurrent run.
            write {
                it.copy(
                    progress = TranscriptionProgress.Preparing, transcript = "", transcriptId = null,
                    elapsedMillis = 0
                )
            }
            scope.launch(start = CoroutineStart.LAZY) {
                runTranscription(token, model, audio, snapshot.audioName, snapshot.language)
            }.also { trackForeground(it, Kind.TRANSCRIBE, token) }
        }
        job.start()
        return true
    }

    private suspend fun runTranscription(
        token: Long,
        model: ImportedModel,
        audio: String,
        audioName: String,
        language: LanguageOption
    ) = coroutineScope {
        val started = monotonicMillis()
        // The clock is a child of this run: it can neither outlive it nor stop a newer run's clock.
        val clock = launch {
            while (isActive) {
                writeIfCurrent(token) {
                    if (it.busy) it.copy(elapsedMillis = monotonicMillis() - started) else it
                }
                delay(clockInterval)
            }
        }
        try {
            val backend = backends[model.backend]
                ?: throw IllegalStateException("No backend available for this model type.")
            backend.validate(model).getOrThrow()
            val text: String
            var audioDecodeMillis = 0L
            var audioSamples = 0
            if (backend is UriTranscriptionBackend) {
                writeIfCurrent(token) { it.copy(progress = TranscriptionProgress.Transcribing(0f)) }
                text = backend.transcribeUri(model, audio, language) { fraction ->
                    writeIfCurrent(token) { it.copy(progress = TranscriptionProgress.Transcribing(fraction)) }
                }.trim()
            } else {
                writeIfCurrent(token) { it.copy(progress = TranscriptionProgress.Decoding(0f)) }
                val decodeStarted = monotonicMillis()
                val pcm = decoder.decode(audio) { fraction ->
                    writeIfCurrent(token) { it.copy(progress = TranscriptionProgress.Decoding(fraction)) }
                }
                audioDecodeMillis = monotonicMillis() - decodeStarted
                audioSamples = pcm.size
                require(pcm.isNotEmpty()) { "The audio contains no decoded samples." }
                ensureActive()
                // The prepared LiteRT session is reused, so transcription can begin immediately.
                writeIfCurrent(token) { it.copy(progress = TranscriptionProgress.Transcribing(0f)) }
                text = backend.transcribe(model, pcm, language) { fraction ->
                    writeIfCurrent(token) { it.copy(progress = TranscriptionProgress.Transcribing(fraction)) }
                }.trim()
            }
            ensureActive()
            val elapsed = monotonicMillis() - started
            clock.cancel()
            val result = TranscriptionResult(
                detectedLanguage = language.code,
                text = text,
                processingDurationMillis = elapsed,
                modelId = model.id
            )
            val transcriptId = newTranscriptId()
            val accepted = finishIfCurrent(token) {
                it.copy(
                    transcript = text, transcriptId = transcriptId, elapsedMillis = elapsed,
                    progress = TranscriptionProgress.Completed(result)
                )
            }
            if (accepted) {
                val completed = CompletedTranscript(
                    id = transcriptId, audioUri = audio, audioName = audioName, modelId = model.id,
                    modelName = model.displayName, text = text, language = language,
                    processingMillis = elapsed,
                    audioDurationMillis = if (backend is UriTranscriptionBackend) null
                    else audioSamples * 1000L / PCM_SAMPLE_RATE
                )
                runCatching { onTranscriptCompleted(completed) }
                    .onFailure { log("Transcript completion hook failed: ${it.message}") }
            }
            log(
                "audioDecode=${audioDecodeMillis}ms total=${monotonicMillis() - started}ms " +
                    "audioSamples=$audioSamples model=${model.displayName}"
            )
        } catch (cancellation: CancellationException) {
            // Covers both job cancellation and a backend reporting that its call was cancelled.
            finishIfCurrent(token) { it.copy(progress = TranscriptionProgress.Cancelled) }
            throw cancellation
        } catch (error: Throwable) {
            if (!isActive) {
                // Native/network code may fail with an ordinary exception once cancelled.
                finishIfCurrent(token) { it.copy(progress = TranscriptionProgress.Cancelled) }
                throw CancellationException("Transcription cancelled.").apply { initCause(error) }
            }
            finishIfCurrent(token) {
                it.copy(progress = TranscriptionProgress.Failed(error.message ?: "Transcription failed."))
            }
        } finally {
            clock.cancel()
        }
    }

    // ---- Cancellation and disposal ----------------------------------------------------

    /**
     * Cancels the current import, preparation or transcription. The state becomes Cancelled
     * immediately; work that is still unwinding can no longer write state.
     */
    fun cancel() {
        val jobs = locked {
            val jobs = listOfNotNull(foreground, prepareJob).filter { it.isActive }
            val busy = mutableState.value.busy
            if (busy || jobs.isNotEmpty()) generation++
            if (busy) write { it.copy(progress = TranscriptionProgress.Cancelled) }
            jobs
        }
        jobs.forEach { it.cancel(CancellationException("Cancelled by user.")) }
    }

    /** Lifecycle end: invalidates all pending writes, cancels work and releases backends once. */
    fun dispose() {
        val jobs = locked {
            if (disposed) return
            disposed = true
            generation++
            listOfNotNull(foreground, prepareJob)
        }
        jobs.forEach { it.cancel(CancellationException("Coordinator disposed.")) }
        backends.values.distinct().forEach { backend ->
            runCatching { backend.cancel() }.onFailure { log("Backend cancel failed: ${it.message}") }
            runCatching { backend.close() }.onFailure { log("Backend close failed: ${it.message}") }
        }
    }

    // ---- Helpers ----------------------------------------------------------------------

    /** Must be called while holding [lock]. */
    private fun trackForeground(job: Job, kind: Kind, token: Long) {
        foreground = job
        foregroundKind = kind
        foregroundToken = token
        job.invokeOnCompletion {
            locked {
                if (foreground === job) {
                    foreground = null
                    foregroundKind = null
                }
            }
        }
    }

    private fun persistSelection(id: String) {
        scope.launch {
            // Mutex is FIFO, so rapid selections are persisted in the order they were made.
            selectionPersistence.withLock {
                runCatchingCancellable { catalog.select(id) }
                    .onFailure { log("Persisting model selection failed: ${it.message}") }
            }
        }
    }
}
