package com.local.voicenotes.model

import com.local.voicenotes.domain.ImportedModel
import com.local.voicenotes.translate.TranslationText

/** Every model the app can download. Revisions are pinned in the URLs; size and SHA-256 are verified. */
object ModelSpecs {
    val PARAKEET: DownloadSpec = ParakeetUltraDownload.ENCODER_30S

    /** cstr/cohere-transcribe-arabic-07-2026-GGUF, Q4_K imatrix (Apache-2.0). */
    val COHERE_ARABIC = DownloadSpec(
        url = "https://huggingface.co/cstr/cohere-transcribe-arabic-07-2026-GGUF/resolve/" +
            "dfd6d279cd26c4a8862e5af601976e5c552e709e/cohere-transcribe-arabic-q4_k-imatrix.gguf",
        fileName = "cohere-transcribe-arabic-q4_k-imatrix.gguf",
        sizeBytes = 1_510_365_440L,
        sha256 = "a8b64ca1273072a99645ba154d3ef52a64ec167a0beca316d7e094cd187e6d96",
        id = "cohere-arabic",
        title = "Arabic speech model"
    )

    /** litert-community/gemma-4-E4B-it-litert-lm (Apache-2.0). */
    val GEMMA_E4B = DownloadSpec(
        url = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/" +
            "f254b5ba6e36f7d89cc7892e268e9877bc20f0a0/gemma-4-E4B-it.litertlm",
        fileName = "gemma-4-E4B-it.litertlm",
        sizeBytes = 3_659_530_240L,
        sha256 = "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0",
        id = "gemma-e4b",
        title = "Gemma 4 E4B translation model"
    )

    /** litert-community/gemma-4-E2B-it-litert-lm (Apache-2.0). */
    val GEMMA_E2B = DownloadSpec(
        url = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/" +
            "2d74ac41a33d7da82f39f7d5720a04c68514a103/gemma-4-E2B-it.litertlm",
        fileName = "gemma-4-E2B-it.litertlm",
        sizeBytes = 2_588_147_712L,
        sha256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
        id = "gemma-e2b",
        title = "Gemma 4 E2B translation model"
    )

    val all: List<DownloadSpec> = listOf(PARAKEET, COHERE_ARABIC, GEMMA_E4B, GEMMA_E2B)

    fun byId(id: String): DownloadSpec? = all.firstOrNull { it.id == id }
}

/** Record rules for the downloaded Cohere Transcribe Arabic model. */
object CohereArabicModel {
    const val MODEL_ID = "download:cohere-transcribe-arabic-q4_k"
    const val ARCHITECTURE = "cohere-transcribe-arabic"
    const val BACKEND = "crispasr-cohere-cpu"
    /** The model is an Arabic finetune; it is always prompted with this language. */
    const val LANGUAGE = "ar"

    fun record(path: String): ImportedModel = ImportedModel(
        id = MODEL_ID,
        displayName = ModelSpecs.COHERE_ARABIC.fileName,
        path = path,
        sizeBytes = ModelSpecs.COHERE_ARABIC.sizeBytes,
        architecture = ARCHITECTURE,
        backend = BACKEND,
        enabled = true,
        note = "Cohere Transcribe Arabic · CPU"
    )

    fun isArabic(model: ImportedModel): Boolean = model.backend == BACKEND
}

/** The on-device speech model used by Local transcription. */
enum class LocalModelChoice(val label: String, val detail: String, val spec: DownloadSpec) {
    PARAKEET("Parakeet (multilingual)", "Detects the language of each note", ModelSpecs.PARAKEET),
    ARABIC("Arabic (Cohere)", "Every note is transcribed as Arabic", ModelSpecs.COHERE_ARABIC);

    companion object {
        fun parse(value: String?): LocalModelChoice = entries.firstOrNull { it.name == value } ?: PARAKEET
    }
}

/** Model for the optional "Translate to German" feature (a separate switch turns it on). */
enum class TranslationModel(val label: String, val spec: DownloadSpec) {
    GEMMA_E4B("Gemma 4 E4B (better, 3.7 GB)", ModelSpecs.GEMMA_E4B),
    GEMMA_E2B("Gemma 4 E2B (faster, 2.6 GB)", ModelSpecs.GEMMA_E2B);

    companion object {
        /** E4B is the default until the user chooses otherwise. */
        fun parse(value: String?): TranslationModel = entries.firstOrNull { it.name == value } ?: GEMMA_E4B

        val specs: List<DownloadSpec> = entries.map { it.spec }
    }
}

/** The installed translation specs, in [TranslationModel] order. Both may be installed at once. */
fun installedTranslationSpecs(installed: Set<String>): List<DownloadSpec> =
    TranslationModel.specs.filter { it.id in installed }

/** Language the transcript is translated into. [CUSTOM] uses a name the user typed. */
enum class TargetLanguage(val label: String) {
    ENGLISH("English"), GERMAN("German"), ARABIC("Arabic"), CUSTOM("Custom");

    companion object {
        fun parse(value: String?): TargetLanguage = entries.firstOrNull { it.name == value } ?: GERMAN
    }
}

/** Persisted translation settings; [enabled] is the "Translation" switch. */
data class TranslationSettings(
    val enabled: Boolean = false,
    val model: TranslationModel = TranslationModel.GEMMA_E4B,
    val target: TargetLanguage = TargetLanguage.GERMAN,
    val customLanguage: String = ""
) {
    /** Language name for the prompt. */
    val languageName: String
        get() = if (target == TargetLanguage.CUSTOM) TranslationText.normalizeLanguage(customLanguage) else target.label

    /** False while Custom is chosen but no valid language name was typed. */
    val languageReady: Boolean
        get() = target != TargetLanguage.CUSTOM || TranslationText.customLanguageError(customLanguage) == null
}
