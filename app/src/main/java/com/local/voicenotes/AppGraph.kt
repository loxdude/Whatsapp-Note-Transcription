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
import com.local.voicenotes.model.ModelRepository
import com.local.voicenotes.work.TranscriptionService
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

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
    private val mistralBackend = MistralTranscriptionBackend(app)
    val mistralApiKeyStore = MistralApiKeyStore(app)

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
            "litert-qnn" to liteRtBackend,
            "litert-cpu" to liteRtBackend,
            "litert-qnn-sm8650" to liteRtBackend,
            "mistral-api" to mistralBackend
        ),
        log = ::log,
        // History is written here, not from Compose, so it is saved even without any UI.
        onTranscriptCompleted = { done ->
            if (done.text.isNotBlank()) {
                history.add(
                    NewHistoryEntry(
                        id = done.id, audioName = done.audioName, modelName = done.modelName,
                        text = done.text, audioDurationMillis = done.audioDurationMillis,
                        processingMillis = done.processingMillis
                    )
                )
            }
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

    init {
        // Runs once per process, so a recreated activity cannot reset audio of running work.
        preferences.getString("uri", null)?.let { saved ->
            runCatching { setAudio(Uri.parse(saved), persist = false) }
        }
        initialRefresh = coordinator.refreshModels()
    }

    /**
     * Selects [uri] as the audio. With [persist], tries to keep read access across restarts.
     *
     * Picker URIs (OpenDocument) grant persistable access. URIs shared from other apps carry
     * only a temporary grant; taking a persistable grant throws SecurityException then. Such
     * audio is used for this session only: it is not remembered for the next launch and no
     * grant is released for it later. The temporary grant may end with the receiving
     * activity, which is acceptable because the audio is read when a transcription starts
     * (local decoding opens the file, Mistral copies it to a staging file first).
     */
    fun setAudio(uri: Uri, persist: Boolean): Job = scope.launch(audioDispatcher) {
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
        coordinator.setAudio(uri.toString(), name)
    }

    /**
     * Starts the foreground service for a just-started import or transcription. Must be
     * called from a user action while the app is visible (background FGS starts are denied).
     */
    fun startWorkService() {
        if (!TranscriptionService.start(app)) {
            log("Foreground service could not start; work continues without it.")
        }
    }
}
