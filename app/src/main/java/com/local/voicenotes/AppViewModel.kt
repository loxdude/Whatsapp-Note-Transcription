package com.local.voicenotes

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import com.local.voicenotes.domain.LanguageOption
import com.local.voicenotes.history.HistoryEntry
import com.local.voicenotes.inference.MistralApiKeyStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Thin Android adapter exposing the app-scoped [TranscriptionCoordinator] (owned by
 * [AppGraph], not by this ViewModel) plus UI-only state. Clearing this ViewModel never
 * cancels running work or closes backends; the foreground service keeps work alive.
 */
class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val graph = application.appGraph
    private val coordinator = graph.coordinator
    val state: StateFlow<AppUiState> = coordinator.state

    /** Completed transcripts, newest first. */
    val history: StateFlow<List<HistoryEntry>> = graph.history.entries

    private val mutableShowHistory = MutableStateFlow(false)
    val showHistory: StateFlow<Boolean> = mutableShowHistory.asStateFlow()

    /** Process-owned share state also survives ViewModel/Activity recreation. */
    val cloudUploadConsent = graph.cloudUploadConsent
    val consentPrompt = graph.consentPrompt
    val shareHint = graph.shareHint

    fun refreshModels() = coordinator.refreshModels()

    fun setAudio(uri: Uri, persist: Boolean = true) {
        graph.setAudio(uri, persist)
    }

    fun onSharedAudio(uri: Uri) {
        mutableShowHistory.value = false
        graph.onSharedAudio(uri)
    }

    fun consentPromptShown() = graph.consentPromptShown()
    fun grantCloudUploadConsent() = graph.grantCloudUploadConsent()

    fun setLanguage(language: LanguageOption) = coordinator.setLanguage(language)

    fun selectModel(id: String) {
        coordinator.selectModel(id)
    }

    fun importModel(uri: Uri) {
        if (coordinator.importModel(uri.toString())) graph.startWorkService()
    }

    /** Cloud consent is enforced by the app-scoped start path. */
    fun transcribe(): Boolean = graph.transcribe()

    fun cancel() = graph.cancelWork()

    fun editTranscript(value: String) = coordinator.editTranscript(value)

    fun setShowHistory(show: Boolean) {
        mutableShowHistory.value = show
    }

    fun deleteHistoryEntry(id: String) = graph.history.delete(id)

    /** Persists pending transcript edits now (the app is going to the background). */
    fun flushHistory() = graph.history.flush()

    /** Saves the key; returns an error message for the UI instead of throwing on Keystore failure. */
    fun setApiKey(apiKey: String): String? =
        graph.mistralApiKeyStore.set(apiKey.trim()).exceptionOrNull()?.let { "Could not save the API key: ${it.message}" }

    /** Key status for the UI; never decrypts or exposes the stored key. */
    fun apiKeyStatus(): MistralApiKeyStatus = graph.mistralApiKeyStore.status()

    // No onCleared(): the coordinator and backends belong to the process, not this ViewModel.
}
