package com.local.voicenotes

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.local.voicenotes.audio.AndroidAudioDecoder
import com.local.voicenotes.domain.ImportedModel
import com.local.voicenotes.domain.LanguageOption
import com.local.voicenotes.domain.TranscriptionProgress
import com.local.voicenotes.domain.TranscriptionResult
import com.local.voicenotes.inference.ParakeetBackend
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
    private val repository = ModelRepository(application)
    private val decoder = AndroidAudioDecoder(application.contentResolver)
    private val backend = ParakeetBackend()
    private val mutableState = MutableStateFlow(AppUiState())
    val state: StateFlow<AppUiState> = mutableState.asStateFlow()
    private var activeJob: Job? = null
    private var clockJob: Job? = null
    private var preloadJob: Job? = null
    private var preloadedModelId: String? = null

    init { refreshModels() }

    fun refreshModels() = viewModelScope.launch {
        val models = repository.models()
        val saved = repository.selectedModelId()
        val selected = models.firstOrNull { it.id == saved && it.enabled }?.id
            ?: models.firstOrNull { it.enabled }?.id
        mutableState.value = mutableState.value.copy(models = models, selectedModelId = selected)
        models.firstOrNull { it.id == selected }?.let(::preload)
    }

    fun setAudio(uri: Uri) {
        val resolver = getApplication<Application>().contentResolver
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
        viewModelScope.launch { repository.select(id) }
        mutableState.value.selectedModel?.let(::preload)
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
                if (!model.enabled) {
                    mutableState.value = mutableState.value.copy(progress = TranscriptionProgress.Failed(model.note))
                }
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
            startClock(started)
            try {
                mutableState.value = mutableState.value.copy(
                    progress = TranscriptionProgress.Preparing, transcript = "", elapsedMillis = 0
                )
                backend.validate(model).getOrThrow()
                mutableState.value = mutableState.value.copy(progress = TranscriptionProgress.Decoding(0f))
                val pcm = decoder.decode(uri) { fraction ->
                    mutableState.value = mutableState.value.copy(progress = TranscriptionProgress.Decoding(fraction))
                }
                require(pcm.isNotEmpty()) { "The audio contains no decoded samples." }
                mutableState.value = mutableState.value.copy(progress = TranscriptionProgress.LoadingModel)
                val text = backend.transcribe(model, pcm, snapshot.language) { fraction ->
                    mutableState.value = mutableState.value.copy(progress = TranscriptionProgress.Transcribing(fraction))
                }.trim()
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
        backend.cancel()
        activeJob?.cancel()
        mutableState.value = mutableState.value.copy(progress = TranscriptionProgress.Cancelled)
    }

    fun editTranscript(value: String) {
        mutableState.value = mutableState.value.copy(transcript = value)
    }

    private fun preload(model: ImportedModel) {
        if (!model.enabled || preloadedModelId == model.id) return
        preloadJob?.cancel()
        preloadJob = viewModelScope.launch {
            try {
                mutableState.value = mutableState.value.copy(progress = TranscriptionProgress.LoadingModel)
                backend.preload(model).getOrThrow()
                preloadedModelId = model.id
                if (mutableState.value.selectedModelId == model.id) {
                    mutableState.value = mutableState.value.copy(progress = TranscriptionProgress.Idle)
                }
            } catch (t: Throwable) {
                if (mutableState.value.selectedModelId == model.id) {
                    mutableState.value = mutableState.value.copy(
                        progress = TranscriptionProgress.Failed(t.message ?: "Model preload failed.")
                    )
                }
            }
        }
    }

    private fun startClock(started: Long) {
        clockJob?.cancel()
        clockJob = viewModelScope.launch {
            while (true) {
                mutableState.value = mutableState.value.copy(elapsedMillis = System.currentTimeMillis() - started)
                delay(250.milliseconds)
            }
        }
    }

    override fun onCleared() {
        backend.cancel()
        backend.close()
        super.onCleared()
    }
}
