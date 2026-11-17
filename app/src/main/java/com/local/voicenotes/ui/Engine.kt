package com.local.voicenotes.ui

import com.local.voicenotes.AppUiState
import com.local.voicenotes.domain.ImportedModel
import com.local.voicenotes.isCloud
import com.local.voicenotes.model.ParakeetUltraDownload
import com.local.voicenotes.model.ParakeetUltraModels

/** The two user-facing transcription engines: Parakeet Ultra on device, or Mistral in the cloud. */
enum class Engine(val label: String) { LOCAL("Local"), CLOUD("Cloud") }

/** The switch reflects the actual selection; no selection (no local model yet) reads as Local. */
fun engineOf(state: AppUiState): Engine = if (state.selectedModel?.isCloud() == true) Engine.CLOUD else Engine.LOCAL

/** Usable on-device model: the selected one, else the downloaded encoder, else any import. */
fun bestLocalModel(state: AppUiState): ImportedModel? {
    val usable = state.models.filter { it.enabled && !it.isCloud() }
    return usable.firstOrNull { it.id == state.selectedModelId }
        ?: usable.firstOrNull { it.id == ParakeetUltraDownload.MODEL_ID }
        ?: usable.firstOrNull()
}

/** Short user-facing name for a model record or a history entry's stored model name. */
fun friendlyModelName(name: String): String = when {
    ParakeetUltraModels.isUltraName(name) -> "Parakeet Ultra"
    name.contains("mistral", ignoreCase = true) || name.contains("voxtral", ignoreCase = true) -> "Mistral"
    else -> name.removeSuffix(".tflite")
}

/** "Local" or "Cloud", for history chips. */
fun engineLabelForModelName(name: String): String =
    if (name.contains("mistral", ignoreCase = true) || name.contains("voxtral", ignoreCase = true)) "Cloud" else "Local"
