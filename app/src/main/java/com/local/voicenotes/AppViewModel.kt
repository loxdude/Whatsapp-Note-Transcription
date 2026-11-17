package com.local.voicenotes

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.local.voicenotes.domain.LanguageOption
import com.local.voicenotes.history.HistoryEntry
import com.local.voicenotes.inference.MistralApiKeyStatus
import com.local.voicenotes.model.DownloadState
import com.local.voicenotes.model.ModelRecords
import com.local.voicenotes.model.ParakeetUltraDownload
import com.local.voicenotes.ui.Engine
import com.local.voicenotes.ui.ThemeMode
import com.local.voicenotes.ui.bestLocalModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    val historyPersistenceError: StateFlow<String?> = graph.history.persistenceError

    private val mutableShowHistory = MutableStateFlow(false)
    val showHistory: StateFlow<Boolean> = mutableShowHistory.asStateFlow()

    /** Process-owned share state also survives ViewModel/Activity recreation. */
    val cloudUploadConsent = graph.cloudUploadConsent
    val shareHint = graph.shareHint

    val modelDownload: StateFlow<DownloadState> = graph.modelDownload

    // ---- Appearance ---------------------------------------------------------------------

    private val uiPreferences = application.getSharedPreferences("ui", 0)
    private val mutableThemeMode = MutableStateFlow(
        ThemeMode.entries.firstOrNull { it.name == uiPreferences.getString("theme_mode", null) } ?: ThemeMode.SYSTEM
    )
    val themeMode: StateFlow<ThemeMode> = mutableThemeMode.asStateFlow()
    private val mutableDynamicColor = MutableStateFlow(uiPreferences.getBoolean("dynamic_color", true))
    val dynamicColor: StateFlow<Boolean> = mutableDynamicColor.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        mutableThemeMode.value = mode
        uiPreferences.edit().putString("theme_mode", mode.name).apply()
    }

    fun setDynamicColor(enabled: Boolean) {
        mutableDynamicColor.value = enabled
        uiPreferences.edit().putBoolean("dynamic_color", enabled).apply()
    }

    // ---- WhatsApp voice-note folder (persisted SAF tree grant) ------------------------------

    private val folderPreferences = application.getSharedPreferences("audio_selection", 0)
    private val lister = com.local.voicenotes.share.VoiceNoteFolderLister(application.contentResolver)
    private val mutableVoiceNoteTree = MutableStateFlow(
        folderPreferences.getString("voice_note_tree", null)?.let(Uri::parse)?.takeIf { tree ->
            application.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission }
        }
    )
    /** Granted WhatsApp voice-note folder, or null until the user picked it once. */
    val voiceNoteTree: StateFlow<Uri?> = mutableVoiceNoteTree.asStateFlow()

    fun setVoiceNoteTree(tree: Uri) {
        val resolver = getApplication<Application>().contentResolver
        val previous = mutableVoiceNoteTree.value
        resolver.takePersistableUriPermission(tree, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (previous != null && previous != tree) {
            runCatching { resolver.releasePersistableUriPermission(previous, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        folderPreferences.edit().putString("voice_note_tree", tree.toString()).apply()
        mutableVoiceNoteTree.value = tree
    }

    /** Newest voice notes of the granted folder, newest first. */
    suspend fun recentVoiceNotes(): Result<List<com.local.voicenotes.share.VoiceNoteFile>> =
        withContext(Dispatchers.IO) {
            val tree = mutableVoiceNoteTree.value ?: return@withContext Result.failure(IllegalStateException("No folder"))
            try { Result.success(lister.newest(tree)) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { Result.failure(error) }
        }

    // ---- Mistral key status (null while loading) -----------------------------------------

    private val mutableKeyStatus = MutableStateFlow<MistralApiKeyStatus?>(null)
    val keyStatus: StateFlow<MistralApiKeyStatus?> = mutableKeyStatus.asStateFlow()

    init { reloadKeyStatus() }

    fun reloadKeyStatus() {
        viewModelScope.launch {
            try { mutableKeyStatus.value = apiKeyStatus() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableKeyStatus.value = MistralApiKeyStatus.UNREADABLE }
        }
    }

    /**
     * Switches between on-device and cloud transcription. Local without an installed model
     * clears the selection, so nothing can be uploaded while the switch says "Local".
     */
    fun selectEngine(engine: Engine) {
        when (engine) {
            Engine.CLOUD -> coordinator.selectModel(ModelRecords.BUILT_IN_MISTRAL_ID)
            Engine.LOCAL -> {
                val local = bestLocalModel(state.value)
                if (local != null) coordinator.selectModel(local.id) else coordinator.clearSelection()
            }
        }
    }

    fun downloadModel(): Boolean = graph.startModelDownload()
    fun cancelModelDownload() = graph.cancelModelDownload()
    fun dismissDownloadError() = graph.dismissDownloadError()
    fun removeDownloadedModel(): Boolean = graph.removeDownloadedModel()
    fun hasDownloadedModel(): Boolean = state.value.models.any { it.id == ParakeetUltraDownload.MODEL_ID }

    fun refreshModels() = coordinator.refreshModels()

    fun setAudio(uri: Uri, persist: Boolean = true) {
        graph.setAudio(uri, persist)
    }

    fun onSharedAudio(uri: Uri) {
        mutableShowHistory.value = false
        graph.onSharedAudio(uri)
    }

    fun grantCloudUploadConsent() = graph.setCloudUploadConsent(true)
    fun revokeCloudUploadConsent() = graph.setCloudUploadConsent(false)

    fun setLanguage(language: LanguageOption) = coordinator.setLanguage(language)

    fun selectModel(id: String) {
        coordinator.selectModel(id)
    }

    fun importModel(uri: Uri) {
        if (coordinator.importModel(uri.toString())) graph.startWorkService()
    }

    /**
     * Cloud consent is enforced by the app-scoped start path. Non-null [audioUri]/[modelId]
     * bind the start to what the user confirmed; nothing starts if either changed.
     */
    fun transcribe(audioUri: String? = null, modelId: String? = null): Boolean = graph.transcribe(audioUri, modelId)

    fun cancel() = graph.cancelWork()

    fun editTranscript(value: String) = coordinator.editTranscript(value)

    fun setShowHistory(show: Boolean) {
        mutableShowHistory.value = show
    }

    fun deleteHistoryEntry(id: String) = graph.history.delete(id)

    /** Persists pending transcript edits now (the app is going to the background). */
    fun flushHistory() = graph.history.flush()

    /** Saves the key; returns an error message for the UI instead of throwing on Keystore failure. */
    suspend fun setApiKey(apiKey: String): String? = withContext(Dispatchers.IO) {
        graph.mistralApiKeyStore.set(apiKey.trim()).exceptionOrNull()?.let { "Could not save the API key: ${it.message}" }
    }.also { if (it == null) reloadKeyStatus() }

    /** Lock contention with a cancelled request must never stall the UI dispatcher. */
    suspend fun apiKeyStatus(): MistralApiKeyStatus = graph.mistralApiKeyStore.statusAsync()

    // No onCleared(): the coordinator and backends belong to the process, not this ViewModel.
}
