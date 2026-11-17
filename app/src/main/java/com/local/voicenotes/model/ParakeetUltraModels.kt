package com.local.voicenotes.model

import com.local.voicenotes.domain.ImportedModel

/**
 * Filename rules for Parakeet Ultra (litert-community/parakeet-ultra), the only on-device
 * model. The user imports one encoder; the mel front ends and the decoder are bundled with
 * the app. Records of the removed Parakeet TDT v3 path are recognized only to show them
 * disabled.
 */
internal object ParakeetUltraModels {
    const val ARCHITECTURE = "parakeet-ultra"
    const val CPU_BACKEND = "litert-ultra-cpu"
    const val F16_NOTE = "Use the i8 encoder (CPU); f16 is for GPU/NPU and not supported yet"

    /** Architecture persisted by versions that ran Parakeet TDT v3. */
    const val LEGACY_V3_ARCHITECTURE = "parakeet-tdt-0.6b-v3"
    /** Backends persisted for v3 records: portable CPU and Qualcomm NPU exports. */
    private const val LEGACY_CPU_BACKEND = "litert-cpu"
    private const val LEGACY_QNN_BACKEND_PREFIX = "litert-qnn"

    const val V3_REPLACED_NOTE =
        "Parakeet v3 was replaced by Parakeet Ultra — download the model or import parakeet_ultra_encoder_30s_i8.tflite"
    const val SHORT_WINDOW_NOTE =
        "Only the 30 s encoder is supported — download the model or import parakeet_ultra_encoder_30s_i8.tflite"

    private val encoderPattern =
        Regex("parakeet_ultra_encoder_(10|20|30)s_(i8|f16)\\.tflite", RegexOption.IGNORE_CASE)

    /** Browsers and file managers append " (1)", "(2)" or "-1" to repeated downloads. */
    private val duplicateSuffix = Regex("(?:\\s*\\(\\d+\\)|-\\d+)+(\\.tflite)$", RegexOption.IGNORE_CASE)

    /** Result of evaluating a model file name. */
    data class Evaluation(
        val backend: String,
        val enabled: Boolean,
        val note: String,
        val architecture: String = ARCHITECTURE
    )

    data class Encoder(val windowSeconds: Int, val precision: String) {
        val isCpu: Boolean get() = precision == "i8"
        val isSupported: Boolean get() = isCpu && windowSeconds == 30
    }

    /** Strips duplicate-download suffixes so "x (1).tflite" is treated as "x.tflite". */
    fun normalizeName(name: String): String = name.trim().replace(duplicateSuffix, "$1")

    /** Any file of the Ultra family, including the bundled parts and unsupported variants. */
    fun isUltraName(name: String): Boolean {
        val lower = normalizeName(name).lowercase()
        return "parakeet_ultra" in lower || "parakeet-ultra" in lower
    }

    /** The encoder described by [name] (browser duplicate suffixes tolerated), or null. */
    fun parseEncoder(name: String): Encoder? =
        encoderPattern.matchEntire(normalizeName(name))?.let { match ->
            Encoder(match.groupValues[1].toInt(), match.groupValues[2].lowercase())
        }

    fun summary(encoder: Encoder): String = "Parakeet Ultra · CPU · ${encoder.windowSeconds} s windows"

    fun evaluate(name: String): Evaluation {
        val encoder = parseEncoder(name)
        val lower = normalizeName(name).lowercase()
        return when {
            encoder != null && encoder.isSupported -> Evaluation(CPU_BACKEND, true, summary(encoder))
            encoder != null && encoder.isCpu -> Evaluation(CPU_BACKEND, false, SHORT_WINDOW_NOTE)
            encoder != null -> Evaluation(CPU_BACKEND, false, F16_NOTE)
            "_mel_" in lower || "_decoder_" in lower -> Evaluation(
                CPU_BACKEND, false,
                "Bundled with the app; import parakeet_ultra_encoder_30s_i8.tflite"
            )
            else -> Evaluation(
                CPU_BACKEND, false,
                "Not a Parakeet Ultra encoder; import parakeet_ultra_encoder_30s_i8.tflite"
            )
        }
    }

    /**
     * Error shown when importing a file that is not part of the Parakeet Ultra family (e.g. a
     * Parakeet TDT v3 or NPU export), or null if the import may proceed. Such files are
     * rejected before anything is persisted.
     */
    fun importError(name: String): String? = when {
        isUltraName(name) -> null
        "parakeet" in normalizeName(name).lowercase() ->
            "Parakeet v3 and NPU exports are no longer supported. Import a Parakeet Ultra encoder: " +
                "parakeet_ultra_encoder_30s_i8.tflite."
        else ->
            "Not a Parakeet Ultra model. Import parakeet_ultra_encoder_30s_i8.tflite."
    }

    /** A record of the removed Parakeet TDT v3 path (portable CPU or Qualcomm NPU export). */
    fun isReplacedV3(model: ImportedModel): Boolean =
        model.backend != CPU_BACKEND && (
            model.architecture == LEGACY_V3_ARCHITECTURE ||
                model.backend == LEGACY_CPU_BACKEND ||
                model.backend.startsWith(LEGACY_QNN_BACKEND_PREFIX)
            )

    /** True for records run by the local LiteRT backend. */
    fun isLocalBackend(backend: String): Boolean = backend == CPU_BACKEND
}
