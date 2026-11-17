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
import com.local.voicenotes.model.DownloadState
import com.local.voicenotes.model.ModelDownload
import com.local.voicenotes.model.ModelRepository
import com.local.voicenotes.model.OkHttpRangeSource
import com.local.voicenotes.model.ParakeetUltraDownload
import com.local.voicenotes.model.ParakeetUltraModels
import com.local.voicenotes.model.active
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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
        onTranscriptEdited = history::updateText
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
    }
    /** Remembered until revoked in Settings; the consent dialog says so. */
    fun setCloudUploadConsent(granted: Boolean) {
        consentPreferences.edit().putBoolean("cloud_upload_consent", granted).apply()
        mutableCloudConsent.value = granted
    }

    init {
        // Runs once per process, so a recreated activity cannot reset audio of running work.
        preferences.getString("uri", null)?.let { saved ->
            runCatching { setAudio(Uri.parse(saved), persist = false) }
        }
        initialRefresh = coordinator.refreshModels()
        releaseReplacedModelCopies()
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
        if (!started) log("Foreground service could not start; work continues without it.")
        return started
    }

    /** True while the work service runs in the foreground. Main thread; set by the service. */
    @Volatile var workServiceInForeground = false

    // ---- One-tap model download -------------------------------------------------------

    private val mutableDownload = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val modelDownload: StateFlow<DownloadState> = mutableDownload.asStateFlow()
    private var downloadJob: Job? = null
    /** Set for the whole removal so a new download cannot register a file the removal then deletes. Main thread. */
    private var removeJob: Job? = null
    private val downloadCall = AtomicReference<Call?>()
    private val download = ModelDownload(
        directory = File(app.noBackupFilesDir, "models"),
        spec = ParakeetUltraDownload.ENCODER_30S,
        source = OkHttpRangeSource { downloadCall.set(it) }
    )

    /**
     * Downloads, verifies and registers the pinned Parakeet Ultra encoder, then selects it unless
     * the user switched to the cloud meanwhile. Must be called from a user action while visible
     * (it starts the foreground service). Main thread.
     */
    fun startModelDownload(): Boolean {
        if (downloadJob?.isCompleted == false || removeJob?.isCompleted == false) return false
        mutableDownload.value = DownloadState.Running(0, ParakeetUltraDownload.ENCODER_30S.sizeBytes)
        downloadJob = scope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    var lastPublished = -1L
                    download.run(
                        checkCancelled = { ensureActive() },
                        onProgress = { done, total ->
                            // ~0.25 % steps keep StateFlow/notification churn low.
                            val step = done * 400 / total
                            if (step != lastPublished) {
                                lastPublished = step
                                mutableDownload.value = DownloadState.Running(done, total)
                            }
                        },
                        onVerifying = { mutableDownload.value = DownloadState.Verifying }
                    )
                }
                val model = repository.registerDownloaded(file, ParakeetUltraDownload.ENCODER_30S)
                coordinator.refreshModels().join()
                if (coordinator.state.value.selectedModel?.isCloud() != true) coordinator.selectModel(model.id)
                mutableDownload.value = DownloadState.Idle
                log("Model download finished: ${file.length()} bytes")
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                mutableDownload.value = DownloadState.Idle
                throw cancelled
            } catch (error: Exception) {
                // Cancelling the HTTP call surfaces as an IOException in the blocked read.
                if (!kotlin.coroutines.coroutineContext.isActive) {
                    mutableDownload.value = DownloadState.Idle
                    throw kotlinx.coroutines.CancellationException("Download cancelled.").apply { initCause(error) }
                }
                log("Model download failed: ${error.message}")
                mutableDownload.value = DownloadState.Failed(
                    when (error) {
                        is java.net.UnknownHostException, is java.net.ConnectException ->
                            "No internet connection. Check your network and retry."
                        is java.net.SocketTimeoutException -> "The connection timed out. Tap Retry to resume."
                        else -> error.message ?: "Download failed."
                    }
                )
            } finally {
                downloadCall.set(null)
            }
        }
        startWorkService()
        return true
    }

    /** Cancels a running download; the partial file is kept so a retry resumes. */
    fun cancelModelDownload() {
        val job = downloadJob?.takeIf { it.isActive } ?: return
        job.cancel()
        downloadCall.getAndSet(null)?.cancel()
    }

    fun dismissDownloadError() {
        if (mutableDownload.value is DownloadState.Failed) mutableDownload.value = DownloadState.Idle
    }

    /** Removes the downloaded encoder to free storage. Rejected while work is running. */
    fun removeDownloadedModel(): Boolean {
        if (coordinator.state.value.busy || mutableDownload.value.active) return false
        if (downloadJob?.isCompleted == false || removeJob?.isCompleted == false) return false
        val wasSelected = coordinator.state.value.selectedModelId == ParakeetUltraDownload.MODEL_ID
        if (wasSelected && !coordinator.clearSelection()) return false
        removeJob = scope.launch {
            runCatching {
                repository.remove(ParakeetUltraDownload.MODEL_ID)
                withContext(Dispatchers.IO) { download.delete() }
            }.onFailure { log("Removing the downloaded model failed: ${it.message}") }
            coordinator.refreshModels().join()
        }
        return true
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
}
