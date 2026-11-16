package com.local.voicenotes

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.local.voicenotes.audio.AndroidAudioDecoder
import com.local.voicenotes.domain.ImportedModel
import com.local.voicenotes.domain.LanguageOption
import com.local.voicenotes.inference.LiteRtParakeetBackend
import com.local.voicenotes.inference.MistralApiKeyStore
import com.local.voicenotes.inference.MistralTranscriptionBackend
import com.local.voicenotes.model.ModelRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Android adapter around [TranscriptionCoordinator], which owns the lifecycle and state rules.
 * This class only resolves Android resources (URIs, permissions, preferences).
 */
class AppViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "VoiceNotesBenchmark"
        private const val CLOUD_CONSENT = "cloud_upload_consent"
    }
    private val preferences = application.getSharedPreferences("audio_selection", 0)
    private val repository = ModelRepository(application)
    private val decoder = AndroidAudioDecoder(application.contentResolver)
    private val liteRtBackend = LiteRtParakeetBackend(application)
    private val mistralBackend = MistralTranscriptionBackend(application)
    private val mistralApiKeyStore = MistralApiKeyStore(application)
    private val coordinator = TranscriptionCoordinator(
        scope = viewModelScope,
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
        log = { message -> Log.i(TAG, message) }
    )
    val state: StateFlow<AppUiState> = coordinator.state

    /**
     * Content-provider queries and grant bookkeeping run off the main thread, one at a time, so
     * rapid audio picks are still applied in the order the user made them.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val audioDispatcher = Dispatchers.IO.limitedParallelism(1)

    private val consentPreferences = application.getSharedPreferences("privacy", 0)
    private val mutableCloudConsent = MutableStateFlow(consentPreferences.getBoolean(CLOUD_CONSENT, false))
    /** Whether the user agreed that audio may be uploaded to Mistral for cloud transcription. */
    val cloudUploadConsent: StateFlow<Boolean> = mutableCloudConsent.asStateFlow()

    init {
        preferences.getString("uri", null)?.let { saved ->
            runCatching { setAudio(Uri.parse(saved), persist = false) }
        }
        coordinator.refreshModels()
    }

    fun refreshModels() = coordinator.refreshModels()

    fun setAudio(uri: Uri, persist: Boolean = true) {
        viewModelScope.launch(audioDispatcher) {
            val resolver = getApplication<Application>().contentResolver
            if (persist) {
                val previous = preferences.getString("uri", null)
                runCatching {
                    resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                preferences.edit().putString("uri", uri.toString()).apply()
                // Android caps persisted grants per app; release the previous audio grant so
                // repeated picks cannot evict the grant of an imported model.
                if (previous != null && previous != uri.toString() &&
                    state.value.models.none { it.path == previous }
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
    }

    fun grantCloudUploadConsent() {
        consentPreferences.edit().putBoolean(CLOUD_CONSENT, true).apply()
        mutableCloudConsent.value = true
    }

    fun setLanguage(language: LanguageOption) = coordinator.setLanguage(language)

    fun selectModel(id: String) {
        coordinator.selectModel(id)
    }

    fun importModel(uri: Uri) {
        coordinator.importModel(uri.toString())
    }

    /**
     * Starts a transcription. Cloud models never upload without the user's recorded consent;
     * the UI asks first, this is the enforcement point.
     */
    fun transcribe(): Boolean {
        if (state.value.selectedModel?.backend == "mistral-api" && !cloudUploadConsent.value) return false
        return coordinator.transcribe()
    }

    fun cancel() = coordinator.cancel()

    fun editTranscript(value: String) = coordinator.editTranscript(value)

    fun setApiKey(apiKey: String) {
        mistralApiKeyStore.set(apiKey)
    }

    fun getApiKey(): String? = mistralApiKeyStore.get()

    override fun onCleared() {
        // viewModelScope is already cancelled here; dispose() additionally discards any late
        // writes and closes each backend once (LiteRT closes on its NPU thread after inference).
        coordinator.dispose()
        super.onCleared()
    }
}
