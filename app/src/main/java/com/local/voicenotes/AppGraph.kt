package com.local.voicenotes

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.local.voicenotes.audio.AndroidAudioDecoder
import com.local.voicenotes.domain.ImportedModel
import com.local.voicenotes.history.NewHistoryEntry
import com.local.voicenotes.history.TranscriptHistory
import com.local.voicenotes.history.TranscriptHistoryStore
import com.local.voicenotes.inference.LiteRtParakeetBackend
import com.local.voicenotes.inference.MistralApiKeyStore
import com.local.voicenotes.inference.MistralTranscriptionBackend
import com.local.voicenotes.domain.TranscriptionProgress
import com.local.voicenotes.inference.CohereArabicBackend
import com.local.voicenotes.inference.CpuCores
import com.local.voicenotes.model.CohereArabicModel
import com.local.voicenotes.model.DownloadSpec
import com.local.voicenotes.model.DownloadState
import com.local.voicenotes.model.LocalModelChoice
import com.local.voicenotes.model.ModelDownloads
import com.local.voicenotes.model.ModelRepository
import com.local.voicenotes.model.ModelSpecs
import com.local.voicenotes.model.OkHttpRangeSource
import com.local.voicenotes.model.ParakeetUltraDownload
import com.local.voicenotes.model.ParakeetUltraModels
import com.local.voicenotes.model.SettingsStore
import com.local.voicenotes.model.TranslationModel
import com.local.voicenotes.model.TranslationSettings
import com.local.voicenotes.model.translationModelsReplacedBy
import com.local.voicenotes.translate.GemmaTranslator
import com.local.voicenotes.translate.TranslationController
import com.local.voicenotes.translate.TranslationText
import com.local.voicenotes.translate.running
import com.local.voicenotes.ui.localModelFor
import com.local.voicenotes.share.SharedWorkController
import com.local.voicenotes.share.sharedAutoStart
import com.local.voicenotes.work.TranscriptionService
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import java.util.concurrent.atomic.AtomicReference
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-scoped dependencies. The coordinator, its scope and the backends live as long as
 * the process: activities and their ViewModels come and go, running work does not depend on
 * them. Backends are therefore never closed explicitly; the OS reclaims them with the process.
 */
class AppGraph(private val app: VoiceNotesApplication) {
    companion object {
        private const val TAG = "VoiceNotesBenchmark"
        private const val HISTORY_FILE = "transcript_history.json"
    }

    private fun log(message: String) { Log.i(TAG, message) }

    /** Main-thread scope like viewModelScope, but never cancelled. */
    val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate +
            CoroutineExceptionHandler { _, error -> Log.e(TAG, "Uncaught app-scope failure", error) }
    )

    private val preferences = app.getSharedPreferences("audio_selection", 0)
    private val repository = ModelRepository(app)
    private val decoder = AndroidAudioDecoder(app.contentResolver)
    private val liteRtBackend = LiteRtParakeetBackend(app)
    private val cohereBackend = CohereArabicBackend { CpuCores.inferenceThreads(CpuCores.readMaxFreqsKhz()) }
    val mistralApiKeyStore = MistralApiKeyStore(app)
    private val mistralBackend = MistralTranscriptionBackend(app, mistralApiKeyStore)

    val history = TranscriptHistory(
        store = TranscriptHistoryStore(
            file = File(app.filesDir, HISTORY_FILE),
            clock = System::currentTimeMillis,
            log = ::log
        ),
        log = ::log
    )

    val coordinator = TranscriptionCoordinator(
        scope = scope,
        catalog = object : ModelCatalog {
            override suspend fun models(): List<ImportedModel> = repository.models()
            override suspend fun selectedModelId(): String? = repository.selectedModelId()
            override suspend fun select(id: String) { repository.select(id) }
            override suspend fun import(source: String, onProgress: (Float) -> Unit): ImportedModel =
                repository.import(Uri.parse(source), onProgress)
        },
        decoder = PcmDecoder { source, onProgress -> decoder.decode(Uri.parse(source), onProgress) },
        backends = mapOf(
            ParakeetUltraModels.CPU_BACKEND to liteRtBackend,
            CohereArabicModel.BACKEND to cohereBackend,
            "mistral-api" to mistralBackend
        ),
        log = ::log,
        // History is written here, not from Compose, so it is saved even without any UI.
        // A blank transcript is only listed once the user edits text into it.
        onTranscriptCompleted = { done ->
            history.add(
                NewHistoryEntry(
                    id = done.id, audioName = done.audioName, modelName = done.modelName,
                    text = done.text, audioDurationMillis = done.audioDurationMillis,
                    processingMillis = done.processingMillis
                )
            )
        },
        onTranscriptEdited = history::updateText,
        // Automatic local picks follow the user's local model choice (never the other model).
        pickLocal = { models -> localModelFor(models, localModel.value) }
    )

    /**
     * Content-provider queries and grant bookkeeping run off the main thread, one at a time, so
     * rapid audio picks are still applied in the order the user made them.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val audioDispatcher = Dispatchers.IO.limitedParallelism(1)

    /** The first model list load; shared-audio handling waits for it. */
    val initialRefresh: Job

    private val consentPreferences = app.getSharedPreferences("privacy", 0)
    private val mutableCloudConsent = MutableStateFlow(consentPreferences.getBoolean("cloud_upload_consent", false))
    val cloudUploadConsent: StateFlow<Boolean> = mutableCloudConsent.asStateFlow()

    private val sharedWork = SharedWorkController(
        scope = scope,
        coordinator = coordinator,
        loadAudioName = { source, persist -> loadAudioName(Uri.parse(source), persist) },
        awaitInitialRefresh = { initialRefresh.join() },
        cloudConsent = { cloudUploadConsent.value },
        reserveService = ::reserveWorkServiceForShare,
        startService = { startWorkService() },
        autoStart = { sharedAutoStart(app.isInForeground, workServiceInForeground) }
    )
    val shareHint = sharedWork.shareHint

    fun onSharedAudio(uri: Uri) = sharedWork.onSharedAudio(uri.toString())
    fun transcribe(audioUri: String? = null, modelId: String? = null): Boolean =
        sharedWork.transcribe(audioUri = audioUri, modelId = modelId)
    /** Single process-owned cancellation authority for UI and notification actions. */
    fun cancelWork() = sharedWork.cancelWork()

    /** Notification Cancel and the service time limit stop all background work, downloads included. */
    fun cancelAll() {
        cancelWork()
        cancelModelDownload()
        cancelTranslation()
    }
    /** Remembered until revoked in Settings; the consent dialog says so. */
    fun setCloudUploadConsent(granted: Boolean) {
        consentPreferences.edit().putBoolean("cloud_upload_consent", granted).apply()
        mutableCloudConsent.value = granted
    }

    /**
     * Parakeet v3 records stay listed (disabled), but their private copies in the model cache
     * are never loaded again: drop their cache refs so the cache GC deletes the blobs.
     */
    private fun releaseReplacedModelCopies() {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val paths = repository.replacedModelPaths()
                if (paths.isNotEmpty()) {
                    liteRtBackend.forgetCachedCopies(paths)
                    log("Released cached copies of ${paths.size} replaced Parakeet v3 model(s).")
                }
            }.onFailure { log("Releasing replaced model copies failed: ${it.message}") }
        }
    }

    /**
     * Selects [uri] as the audio. With [persist] (picker URIs from OpenDocument, which grant
     * persistable access) the grant is taken and the audio is remembered for the next launch;
     * the previous picked audio's grant is released.
     *
     * Audio shared from other apps uses `persist = false`: it carries only a temporary grant,
     * so it is used for this session only, and the previously picked audio stays remembered
     * with its grant untouched. The temporary grant may end with the receiving activity,
     * which is acceptable because the audio is read when a transcription starts (local
     * decoding opens the file, Mistral copies it to a staging file first).
     */
    fun setAudio(uri: Uri, persist: Boolean): Job = sharedWork.setAudio(uri.toString(), persist)

    private suspend fun loadAudioName(uri: Uri, persist: Boolean): String = withContext(audioDispatcher) {
        val resolver = app.contentResolver
        if (persist) {
            val previous = preferences.getString("uri", null)
            val persisted = try {
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                true
            } catch (notPersistable: SecurityException) {
                log("Audio grant is temporary; not remembering it: ${notPersistable.message}")
                false
            }
            if (persisted) preferences.edit().putString("uri", uri.toString()).apply()
            else preferences.edit().remove("uri").apply()
            // Android caps persisted grants per app; release the previous audio grant so
            // repeated picks cannot evict the grant of an imported model.
            if (previous != null && previous != uri.toString() &&
                coordinator.state.value.models.none { it.path == previous }
            ) {
                runCatching {
                    resolver.releasePersistableUriPermission(
                        Uri.parse(previous), Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
            }
        }
        val name = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: "Selected audio"
        name
    }

    /**
     * Starts the foreground service for a just-started import or transcription. Must be
     * called from a user action while the app is visible (background FGS starts are denied).
     */
    fun startWorkService(): Boolean {
        val started = TranscriptionService.start(app)
        if (!started) {
            log("Foreground service could not start; work continues without it.")
            reportBackgroundProtectionLost()
        }
        return started
    }

    private val mutableBackgroundProtectionLost = MutableStateFlow(false)
    /** True once work runs without the foreground service; the main screen shows a hint. */
    val backgroundProtectionLost: StateFlow<Boolean> = mutableBackgroundProtectionLost.asStateFlow()

    /** Called when the service could not start or was refused the foreground. */
    fun reportBackgroundProtectionLost() { mutableBackgroundProtectionLost.value = true }

    fun backgroundProtectionHintShown() { mutableBackgroundProtectionLost.value = false }

    /** True while the work service runs in the foreground. Main thread; set by the service. */
    @Volatile var workServiceInForeground = false

    // ---- Model downloads (speech and translation) ------------------------------------------

    private val downloadCall = AtomicReference<Call?>()
    val downloads = ModelDownloads(
        scope = scope,
        directory = File(app.noBackupFilesDir, "models"),
        source = OkHttpRangeSource { downloadCall.set(it) },
        abortInFlight = { downloadCall.getAndSet(null)?.cancel() },
        log = ::log
    )
    /** State of the latest download, for the foreground service. */
    val modelDownload: StateFlow<DownloadState> = downloads.current

    /**
     * Downloads and verifies [spec]. A speech model is registered and, unless the user switched to
     * the cloud meanwhile, selected when it is the chosen local model. Must be called from a user
     * action while visible (it starts the foreground service). Main thread.
     */
    fun startModelDownload(spec: DownloadSpec): Boolean {
        val started = downloads.start(spec) { file -> onDownloaded(spec, file) }
        if (started) startWorkService()
        return started
    }

    private suspend fun onDownloaded(spec: DownloadSpec, file: File) {
        val (record, choice) = when (spec.id) {
            ModelSpecs.PARAKEET.id -> repository.registerDownloaded(file, spec) to LocalModelChoice.PARAKEET
            ModelSpecs.COHERE_ARABIC.id ->
                repository.registerDownloaded(CohereArabicModel.record(file.absolutePath)) to LocalModelChoice.ARABIC
            else -> return
        }
        coordinator.refreshModels().join()
        val state = coordinator.state.value
        if (state.selectedModel?.isCloud() != true && localModel.value == choice) coordinator.selectModel(record.id)
    }

    /** Cancels a running download; the partial file is kept so a retry resumes. */
    fun cancelModelDownload() = downloads.cancel()

    fun dismissDownloadState(id: String) = downloads.dismiss(id)

    /** Removes a downloaded speech model to free storage. Rejected while work is running. */
    fun removeSpeechModel(spec: DownloadSpec): Boolean {
        val id = when (spec.id) {
            ModelSpecs.PARAKEET.id -> ParakeetUltraDownload.MODEL_ID
            ModelSpecs.COHERE_ARABIC.id -> CohereArabicModel.MODEL_ID
            else -> return false
        }
        if (coordinator.state.value.busy || downloads.busy) return false
        val wasSelected = coordinator.state.value.selectedModelId == id
        if (wasSelected && !coordinator.clearSelection()) return false
        return downloads.remove(
            listOf(spec),
            beforeDelete = {
                repository.remove(id)
                if (spec.id == ModelSpecs.COHERE_ARABIC.id) cohereBackend.release()
            },
            afterDelete = { coordinator.refreshModels().join() }
        )
    }

    // ---- Settings: local model and translation -----------------------------------------

    private val settings = SettingsStore(app)
    private val settingsWrites = Mutex()
    private val mutableLocalModel = MutableStateFlow(LocalModelChoice.PARAKEET)
    /** On-device model used by Local transcription. */
    val localModel: StateFlow<LocalModelChoice> = mutableLocalModel.asStateFlow()
    private val mutableTranslationSettings = MutableStateFlow(TranslationSettings())
    val translationSettings: StateFlow<TranslationSettings> = mutableTranslationSettings.asStateFlow()

    private suspend fun loadSettings() {
        runCatching { settings.load() }
            .onSuccess {
                mutableLocalModel.value = it.localModel
                mutableTranslationSettings.value = it.translation
            }
            .onFailure { log("Loading settings failed: ${it.message}") }
    }

    private fun persist(block: suspend () -> Unit) {
        scope.launch {
            // FIFO, so rapid changes are written in the order they were made.
            settingsWrites.withLock {
                runCatching { block() }.onFailure { log("Saving settings failed: ${it.message}") }
            }
        }
    }

    /**
     * Chooses the on-device speech model. While Local is selected the coordinator switches to it
     * right away, or clears the selection if it is not installed yet (nothing else may run then).
     * Rejected while a transcription or import runs. Main thread.
     */
    fun setLocalModel(choice: LocalModelChoice): Boolean {
        val state = coordinator.state.value
        if (state.busy && state.progress !is TranscriptionProgress.LoadingModel) return false
        mutableLocalModel.value = choice
        persist { settings.saveLocalModel(choice) }
        if (choice != LocalModelChoice.ARABIC) cohereBackend.close()
        if (state.selectedModel?.isCloud() == true) return true
        val target = localModelFor(state.models, choice, state.selectedModelId)
        if (target != null) coordinator.selectModel(target.id) else coordinator.clearSelection()
        return true
    }

    fun setTranslationSettings(update: TranslationSettings) {
        val previous = mutableTranslationSettings.value
        if (!update.enabled && previous.enabled) translation.cancel()
        mutableTranslationSettings.value = update
        persist { settings.saveTranslation(update) }
    }

    /**
     * Switches the translation model and removes the other installed one (only one is kept).
     * Returns false while a download or removal runs.
     */
    fun switchTranslationModel(model: TranslationModel): Boolean {
        if (downloads.busy) return false
        val replaced = translationModelsReplacedBy(model, downloads.installed.value)
        translation.cancel()
        setTranslationSettings(mutableTranslationSettings.value.copy(model = model))
        if (replaced.isNotEmpty()) downloads.remove(replaced, beforeDelete = { translator.release() })
        return true
    }

    /** Removes every downloaded translation model. */
    fun removeTranslationModels(): Boolean {
        if (downloads.busy) return false
        translation.cancel()
        val installed = TranslationModel.specs.filter { it.id in downloads.installed.value }
        return downloads.remove(installed, beforeDelete = { translator.release() })
    }

    // ---- Translation -------------------------------------------------------------------

    private val translator = GemmaTranslator(app.cacheDir, beforeLoad = { cohereBackend.close() })
    val translation = TranslationController(scope, ::log)

    /**
     * Translates the transcript on screen (including the user's edits) and stores the result with
     * its history entry. Rejected while a transcription runs or the model is missing. Main thread.
     */
    fun translate(): Boolean {
        val state = coordinator.state.value
        val id = state.transcriptId ?: return false
        val settings = mutableTranslationSettings.value
        val spec = settings.model.spec
        if (state.busy || downloads.removing) return false
        if (!TranslationText.canOffer(state.transcript, settings, spec.id in downloads.installed.value)) return false
        val text = state.transcript
        return translation.start(id, text, translator.forModel(downloads.file(spec), settings.languageName)) { result ->
            history.updateTranslation(id, result)
        }
    }

    fun cancelTranslation() = translation.cancel()

    /** Frees the translation engine when the system needs memory (it loads again on demand). */
    fun onTrimMemory(level: Int) {
        @Suppress("DEPRECATION")
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND && !translation.isRunning) {
            translator.releaseLater()
        }
    }

    private val mutableShareReservations = MutableStateFlow(0)
    /** Number of shares waiting for their automatic transcription; the service stays while > 0. */
    val shareReservations: StateFlow<Int> = mutableShareReservations.asStateFlow()

    /**
     * Called when audio is shared to the app, i.e. while it is visible: starts the work
     * service now (a later start, once the model list is loaded and prepared, may already be
     * in the background and be refused) and keeps it running until the returned release
     * function is called (idempotent). Release it only after the transcription started, so
     * the service carries straight over into the transcription.
     */
    fun reserveWorkServiceForShare(): () -> Unit {
        mutableShareReservations.update { it + 1 }
        val released = AtomicBoolean()
        val release = { if (released.compareAndSet(false, true)) mutableShareReservations.update { it - 1 } }
        if (!startWorkService()) release()
        return release
    }

    // Last, so every property above is initialized before the first refresh runs.
    init {
        // Runs once per process, so a recreated activity cannot reset audio of running work.
        preferences.getString("uri", null)?.let { saved ->
            runCatching { setAudio(Uri.parse(saved), persist = false) }
        }
        // The local model choice decides automatic selections, so it is loaded first.
        initialRefresh = scope.launch {
            loadSettings()
            coordinator.refreshModels().join()
        }
        // A translation belongs to the transcript on screen: new audio or a new transcription (e.g.
        // of shared audio, which clears the transcript id) stops it, so the two never run together.
        scope.launch {
            coordinator.state.collect { state ->
                val running = translation.state.value
                if (running.running && running.transcriptId != state.transcriptId) translation.cancel()
            }
        }
        releaseReplacedModelCopies()
    }
}
