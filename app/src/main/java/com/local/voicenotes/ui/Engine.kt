package com.local.voicenotes.ui

import com.local.voicenotes.AppUiState
import com.local.voicenotes.domain.ImportedModel
import com.local.voicenotes.isCloud
import com.local.voicenotes.model.CohereArabicModel
import com.local.voicenotes.model.LocalModelChoice
import com.local.voicenotes.model.ParakeetUltraDownload

/** The two user-facing transcription engines: Parakeet Ultra on device, or Mistral in the cloud. */
enum class Engine(val label: String) { LOCAL("Local"), CLOUD("Cloud") }

/** Work the user started (import or transcription); model preparation alone is not. */
val AppUiState.working: Boolean
    get() = busy && progress !is com.local.voicenotes.domain.TranscriptionProgress.LoadingModel

/** Transcribe may be tapped while the selected model is still being prepared; it starts afterwards. */
val AppUiState.canRequestTranscription: Boolean
    get() = canTranscribe || (progress is com.local.voicenotes.domain.TranscriptionProgress.LoadingModel &&
        audioUri != null && selectedModel?.enabled == true)

/**
 * A Transcribe tap (or consent confirmation) made while the model was still loading. It is
 * bound to the audio and model it was made for, so it never starts something else.
 * Serializable for rememberSaveable.
 */
data class PendingStart(val audioUri: String, val modelId: String) : java.io.Serializable

enum class PendingStartAction { WAIT, START, DROP }

/**
 * What to do with [pending] in [state]: start once the model is loaded, keep waiting while it
 * loads, and drop it when the audio or model changed, or loading failed or was cancelled.
 */
fun pendingStartAction(pending: PendingStart, state: AppUiState): PendingStartAction = when {
    state.audioUri != pending.audioUri || state.selectedModelId != pending.modelId -> PendingStartAction.DROP
    state.progress is com.local.voicenotes.domain.TranscriptionProgress.LoadingModel -> PendingStartAction.WAIT
    state.canTranscribe && state.progress == com.local.voicenotes.domain.TranscriptionProgress.Idle -> PendingStartAction.START
    // Cancelled, Failed, or other work (import, transcription) took over.
    else -> PendingStartAction.DROP
}

/** The switch reflects the actual selection; no selection (no local model yet) reads as Local. */
fun engineOf(state: AppUiState): Engine = if (state.selectedModel?.isCloud() == true) Engine.CLOUD else Engine.LOCAL

/**
 * Usable on-device model for [choice]: the Arabic model, or for Parakeet the selected one, else
 * the downloaded encoder, else any import. Null when the chosen model is not installed.
 */
fun bestLocalModel(state: AppUiState, choice: LocalModelChoice = LocalModelChoice.PARAKEET): ImportedModel? =
    localModelFor(state.models, choice, state.selectedModelId)

fun localModelFor(models: List<ImportedModel>, choice: LocalModelChoice, selectedId: String? = null): ImportedModel? {
    val usable = models.filter { it.enabled && !it.isCloud() }
    return when (choice) {
        LocalModelChoice.ARABIC -> usable.firstOrNull(CohereArabicModel::isArabic)
        LocalModelChoice.PARAKEET -> {
            val parakeet = usable.filterNot(CohereArabicModel::isArabic)
            parakeet.firstOrNull { it.id == selectedId }
                ?: parakeet.firstOrNull { it.id == ParakeetUltraDownload.MODEL_ID }
                ?: parakeet.firstOrNull()
        }
    }
}

/** "Local" or "Cloud", for history chips. */
fun engineLabelForModelName(name: String): String =
    if (name.contains("mistral", ignoreCase = true) || name.contains("voxtral", ignoreCase = true)) "Cloud" else "Local"
