package com.local.voicenotes

import android.app.Application
import android.net.Uri
import android.content.Intent
import android.provider.OpenableColumns
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.local.voicenotes.audio.AndroidAudioDecoder
import com.local.voicenotes.domain.ImportedModel
import com.local.voicenotes.domain.LanguageOption
import com.local.voicenotes.domain.TranscriptionProgress
import com.local.voicenotes.domain.TranscriptionResult
import com.local.voicenotes.inference.LiteRtParakeetBackend
import com.local.voicenotes.inference.MistralTranscriptionBackend
import com.local.voicenotes.inference.MistralApiKeyStore
import com.local.voicenotes.inference.TranscriptionBackend
import com.local.voicenotes.inference.UriTranscriptionBackend
import com.local.voicenotes.model.ModelRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

data class AppUiState(
    val models: List<ImportedModel> = emptyList(),
    val selectedModelId: String? = null,
    val audioUri: Uri? = null,
    val audioName: String = "",
    val language: LanguageOption = LanguageOption.AUTO,
    val progress: TranscriptionProgress = TranscriptionProgress.Idle,
    val transcript: String = "",
    val elapsedMillis: Long = 0L
) {
    val busy: Boolean get() = progress is TranscriptionProgress.Importing ||
        progress is TranscriptionProgress.Preparing || progress is TranscriptionProgress.Decoding ||
        progress is TranscriptionProgress.LoadingModel || progress is TranscriptionProgress.Transcribing
    val selectedModel: ImportedModel? get() = models.firstOrNull { it.id == selectedModelId }
    val canTranscribe: Boolean get() = !busy && audioUri != null && selectedModel?.enabled == true
}

class AppViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "VoiceNotesBenchmark"
    }
    private val preferences = application.getSharedPreferences("audio_selection", 0)
    private val repository = ModelRepository(application)
    private val decoder = AndroidAudioDecoder(application.contentResolver)
    private val liteRtBackend = LiteRtParakeetBackend(application)
    private val mistralBackend = MistralTranscriptionBackend(application)
    private val mistralApiKeyStore = MistralApiKeyStore(application)
    private val mutableState = MutableStateFlow(AppUiState())
    private val backends: Map<String, TranscriptionBackend> = mapOf(
        "litert-qnn" to liteRtBackend,
        "litert-qnn-sm8650" to liteRtBackend,
        "mistral-api" to mistralBackend
    )
    val state: StateFlow<AppUiState> = mutableState.asStateFlow()
    private var activeJob: Job? = null
    private var clockJob: Job? = null
    private var prepareJob: Job? = null

    init {
        preferences.getString("uri", null)?.let { saved ->
            runCatching { setAudio(Uri.parse(saved), persist = false) }
        }
        refreshModels()
    }

    fun refreshModels() = viewModelScope.launch {
        val models = repository.models()
        val saved = repository.selectedModelId()
        // A startup refresh can finish after the user has selected a model. Keep that
        // in-memory choice instead of restoring the older persisted selection over it.
        val current = mutableState.value.selectedModelId
        val selected = models.firstOrNull { it.id == current && it.enabled }?.id
            ?: models.firstOrNull { it.id == saved && it.enabled }?.id
            ?: models.firstOrNull { it.enabled }?.id
        mutableState.value = mutableState.value.copy(models = models, selectedModelId = selected)
        models.firstOrNull { it.id == selected }?.let(::prepareModel)
    }

    fun setAudio(uri: Uri, persist: Boolean = true) {
        val resolver = getApplication<Application>().contentResolver
        if (persist) {
            runCatching {
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            preferences.edit().putString("uri", uri.toString()).apply()
        }
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: uri.lastPathSegment ?: "Selected audio"
        mutableState.value = mutableState.value.copy(
            audioUri = uri, audioName = name, transcript = "", progress = TranscriptionProgress.Idle
        )
    }

    fun setLanguage(language: LanguageOption) {
        mutableState.value = mutableState.value.copy(language = language)
    }

    fun selectModel(id: String) {
        mutableState.value = mutableState.value.copy(selectedModelId = id)
        val selected = mutableState.value.models.firstOrNull { it.id == id } ?: return
        viewModelScope.launch { repository.select(id) }
        prepareModel(selected)
    }

    fun importModel(uri: Uri) {
        if (mutableState.value.busy) return
        activeJob = viewModelScope.launch {
            try {
                mutableState.value = mutableState.value.copy(progress = TranscriptionProgress.Importing(0f))
                val model = repository.import(uri) { fraction ->
                    mutableState.value = mutableState.value.copy(progress = TranscriptionProgress.Importing(fraction))
                }
                refreshModels().join()
                mutableState.value = mutableState.value.copy(
                    progress = if (model.enabled) TranscriptionProgress.Idle
                    else TranscriptionProgress.Failed(model.note)
                )
            } catch (t: Throwable) {
                mutableState.value = mutableState.value.copy(
                    progress = TranscriptionProgress.Failed(t.message ?: "Model import failed.")
                )
            }
        }
    }

    fun transcribe() {
        val snapshot = mutableState.value
        val uri = snapshot.audioUri ?: return
        val model = snapshot.selectedModel ?: return
        if (!snapshot.canTranscribe) return
        activeJob = viewModelScope.launch {
            val started = System.currentTimeMillis()
            val benchmarkStarted = SystemClock.elapsedRealtimeNanos()
            startClock(started)
            try {
                mutableState.value = mutableState.value.copy(
                    progress = TranscriptionProgress.Preparing, transcript = "", elapsedMillis = 0
                )
                val backend = backends[model.backend] ?: throw IllegalStateException("No backend available for this model type.")
                backend.validate(model).getOrThrow()
                val (text, audioDecodeMillis, audioSamples) = if (backend is UriTranscriptionBackend) {
                    mutableState.value = mutableState.value.copy(progress = TranscriptionProgress.Transcribing(0f))
                    Triple(backend.transcribeUri(model, uri, snapshot.language) { fraction ->
                         mutableState.value = mutableState.value.copy(progress = TranscriptionProgress.Transcribing(fraction))
                    }.trim(), 0L, 0)
                } else {
                    mutableState.value = mutableState.value.copy(progress = TranscriptionProgress.Decoding(0f))
                    val decodeStarted = SystemClock.elapsedRealtimeNanos()
                    val pcm = decoder.decode(uri) { fraction ->
                        mutableState.value = mutableState.value.copy(progress = TranscriptionProgress.Decoding(fraction))
                    }
                    val audioDecodeMillis =
                        (SystemClock.elapsedRealtimeNanos() - decodeStarted) / 1_000_000
                    require(pcm.isNotEmpty()) { "The audio contains no decoded samples." }
                    // prepareModel() creates and retains the LiteRT session on selection.
                    // Once decoding ends we can immediately begin transcription.
                    mutableState.value = mutableState.value.copy(progress = TranscriptionProgress.Transcribing(0f))
                    Triple(backend.transcribe(model, pcm, snapshot.language) { fraction ->
                         mutableState.value = mutableState.value.copy(progress = TranscriptionProgress.Transcribing(fraction))
                    }.trim(), audioDecodeMillis, pcm.size)
                }
                val result = TranscriptionResult(
                    detectedLanguage = snapshot.language.code,
                    text = text,
                    processingDurationMillis = System.currentTimeMillis() - started,
                    modelId = model.id
                )
                mutableState.value = mutableState.value.copy(
                    transcript = text,
                    elapsedMillis = result.processingDurationMillis,
                    progress = TranscriptionProgress.Completed(result)
                )
                Log.i(
                    TAG,
                    "audioDecode=${audioDecodeMillis}ms total=" +
                        "${(SystemClock.elapsedRealtimeNanos() - benchmarkStarted) / 1_000_000}ms " +
                        "audioSamples=$audioSamples model=${model.displayName}"
                )
            } catch (_: CancellationException) {
                mutableState.value = mutableState.value.copy(progress = TranscriptionProgress.Cancelled)
            } catch (t: Throwable) {
                mutableState.value = mutableState.value.copy(
                    progress = TranscriptionProgress.Failed(t.message ?: "Transcription failed.")
                )
            } finally {
                clockJob?.cancel()
            }
        }
    }

    fun cancel() {
        liteRtBackend.cancel()
        mistralBackend.cancel()
        activeJob?.cancel()
        mutableState.value = mutableState.value.copy(progress = TranscriptionProgress.Cancelled)
    }

    fun editTranscript(value: String) {
        mutableState.value = mutableState.value.copy(transcript = value)
    }

    fun setApiKey(apiKey: String) {
        mistralApiKeyStore.set(apiKey)
    }

    fun getApiKey(): String? = mistralApiKeyStore.get()

    private fun startClock(started: Long) {
        clockJob?.cancel()
        clockJob = viewModelScope.launch {
            while (true) {
                mutableState.value = mutableState.value.copy(elapsedMillis = System.currentTimeMillis() - started)
                delay(250.milliseconds)
            }
        }
    }

    private fun prepareModel(model: ImportedModel) {
        prepareJob?.cancel()
        prepareJob = viewModelScope.launch {
            if (mutableState.value.selectedModelId != model.id) return@launch
            mutableState.value = mutableState.value.copy(progress = TranscriptionProgress.LoadingModel)
            val backend = backends[model.backend] ?: run {
                mutableState.value = mutableState.value.copy(
                    progress = TranscriptionProgress.Failed("No backend available for this model type.")
                )
                return@launch
            }
            backend.prepare(model).fold(
                onSuccess = {
                    if (mutableState.value.selectedModelId == model.id) {
                        mutableState.value = mutableState.value.copy(progress = TranscriptionProgress.Idle)
                    }
                },
                onFailure = { error ->
                    if (mutableState.value.selectedModelId == model.id) {
                        mutableState.value = mutableState.value.copy(
                            progress = TranscriptionProgress.Failed(error.message ?: "Model preparation failed.")
                        )
                    }
                }
            )
        }
    }

    override fun onCleared() {
        liteRtBackend.cancel()
        liteRtBackend.close()
        mistralBackend.cancel()
        mistralBackend.close()
        super.onCleared()
    }
}
