package com.local.voicenotes.domain

import android.net.Uri

enum class LanguageOption(val code: String, val label: String) {
    AUTO("auto", "Auto-detect"),
    GERMAN("de", "German"),
    ENGLISH("en", "English"),
    SPANISH("es", "Spanish"),
    FRENCH("fr", "French"),
    ITALIAN("it", "Italian"),
    PORTUGUESE("pt", "Portuguese"),
    DUTCH("nl", "Dutch"),
    POLISH("pl", "Polish"),
    TURKISH("tr", "Turkish"),
    JAPANESE("ja", "Japanese"),
    KOREAN("ko", "Korean"),
    CHINESE("zh", "Chinese")
}

data class ImportedModel(
    val id: String,
    val displayName: String,
    val path: String,
    val sizeBytes: Long,
    val architecture: String,
    val backend: String,
    val enabled: Boolean,
    val note: String = ""
)

data class TranscriptionRequest(
    val audioUri: Uri,
    val model: ImportedModel,
    val language: LanguageOption
)

sealed interface TranscriptionProgress {
    data object Idle : TranscriptionProgress
    data class Importing(val fraction: Float) : TranscriptionProgress
    data object Preparing : TranscriptionProgress
    data class Decoding(val fraction: Float) : TranscriptionProgress
    data object LoadingModel : TranscriptionProgress
    data class Transcribing(val fraction: Float) : TranscriptionProgress
    data class Completed(val result: TranscriptionResult) : TranscriptionProgress
    data object Cancelled : TranscriptionProgress
    data class Failed(val message: String) : TranscriptionProgress
}

data class TranscriptionResult(
    val detectedLanguage: String,
    val text: String,
    val processingDurationMillis: Long,
    val modelId: String
)

