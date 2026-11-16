package com.local.voicenotes

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.local.voicenotes.domain.LanguageOption
import com.local.voicenotes.history.HistoryEntry
import com.local.voicenotes.inference.MistralApiKeyStatus
import com.local.voicenotes.share.SharedAudioDecision
import com.local.voicenotes.share.SharedAutoStart
import com.local.voicenotes.share.sharedAutoStart
import com.local.voicenotes.share.decideSharedAudio
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Thin Android adapter exposing the app-scoped [TranscriptionCoordinator] (owned by
 * [AppGraph], not by this ViewModel) plus UI-only state. Clearing this ViewModel never
 * cancels running work or closes backends; the foreground service keeps work alive.
 */
class AppViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val CLOUD_CONSENT = "cloud_upload_consent"
    }
    private val graph = application.appGraph
    private val coordinator = graph.coordinator
    val state: StateFlow<AppUiState> = coordinator.state

    /** Completed transcripts, newest first. */
    val history: StateFlow<List<HistoryEntry>> = graph.history.entries

    private val mutableShowHistory = MutableStateFlow(false)
    val showHistory: StateFlow<Boolean> = mutableShowHistory.asStateFlow()

    private val consentPreferences = application.getSharedPreferences("privacy", 0)
    private val mutableCloudConsent = MutableStateFlow(consentPreferences.getBoolean(CLOUD_CONSENT, false))
    /** Whether the user agreed that audio may be uploaded to Mistral for cloud transcription. */
    val cloudUploadConsent: StateFlow<Boolean> = mutableCloudConsent.asStateFlow()

    private val mutableConsentPrompt = MutableStateFlow(false)
    /** Set when shared audio waits for upload consent; the UI shows the consent dialog. */
    val consentPrompt: StateFlow<Boolean> = mutableConsentPrompt.asStateFlow()

    private val mutableShareHint = MutableStateFlow<String?>(null)
    /** Shown on the main screen when shared audio was loaded but not transcribed automatically. */
    val shareHint: StateFlow<String?> = mutableShareHint.asStateFlow()

    private var shareJob: Job? = null

    fun refreshModels() = coordinator.refreshModels()

    fun setAudio(uri: Uri, persist: Boolean = true) {
        shareJob?.cancel()
        mutableShareHint.value = null
        graph.setAudio(uri, persist)
    }

    /**
     * Loads audio shared from another app (called by the activity, i.e. while visible) and
     * starts transcribing it once the model list is loaded and no import or preparation
     * runs. The foreground service is started right away, from the foreground, and held for
     * the share until then, so the transcription stays protected if the user leaves
     * meanwhile. If the app is in the background by then and the service is not running
     * (its start was refused or it was stopped), the transcription is not started (it would
     * run unprotected and its service start would be refused): the audio stays loaded and
     * [shareHint] asks the user to start it. Cloud models still require consent: the dialog
     * is requested instead of uploading. Without a usable model the audio is only loaded.
     */
    fun onSharedAudio(uri: Uri) {
        shareJob?.cancel()
        mutableShowHistory.value = false
        mutableShareHint.value = null
        val source = uri.toString()
        val releaseService = graph.reserveWorkServiceForShare()
        shareJob = viewModelScope.launch {
            // Temporary grant: not persisted, the previously picked audio stays remembered.
            graph.setAudio(uri, persist = false).join()
            graph.initialRefresh.join()
            val decision = state
                .map { decideSharedAudio(it, source, cloudUploadConsent.value) }
                .first { it != SharedAudioDecision.WAIT }
            when (decision) {
                SharedAudioDecision.TRANSCRIBE -> startSharedTranscription()
                SharedAudioDecision.NEEDS_CONSENT -> mutableConsentPrompt.value = true
                SharedAudioDecision.ABORT, SharedAudioDecision.NO_USABLE_MODEL,
                SharedAudioDecision.WAIT -> Unit
            }
        }.also { job ->
            // After the transcription started (it then keeps the service) or the share ended.
            job.invokeOnCompletion { releaseService() }
        }
    }

    private fun startSharedTranscription() {
        val app = getApplication<VoiceNotesApplication>()
        when (sharedAutoStart(app.isInForeground, graph.workServiceInForeground)) {
            SharedAutoStart.START -> transcribe()
            SharedAutoStart.START_IN_RUNNING_SERVICE -> startTranscription(startService = false)
            SharedAutoStart.HINT_ONLY ->
                mutableShareHint.value = "Shared audio is loaded. Tap Transcribe to start."
        }
    }

    fun consentPromptShown() {
        mutableConsentPrompt.value = false
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
        if (coordinator.importModel(uri.toString())) graph.startWorkService()
    }

    /**
     * Starts a transcription. Cloud models never upload without the user's recorded consent;
     * the UI asks first, this is the enforcement point.
     */
    fun transcribe(): Boolean = startTranscription(startService = true)

    /**
     * [startService] must only be true while the app is visible (a user's tap, or a share
     * whose app is still visible): background foreground-service starts are refused.
     */
    private fun startTranscription(startService: Boolean): Boolean {
        if (state.value.selectedModel?.isCloud() == true && !cloudUploadConsent.value) return false
        val started = coordinator.transcribe()
        if (started) mutableShareHint.value = null
        if (started && startService) graph.startWorkService()
        return started
    }

    fun cancel() {
        shareJob?.cancel()
        coordinator.cancel()
    }

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
