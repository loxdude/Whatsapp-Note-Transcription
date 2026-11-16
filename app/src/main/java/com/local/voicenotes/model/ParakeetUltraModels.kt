package com.local.voicenotes.model

/**
 * Filename rules for Parakeet Ultra (litert-community/parakeet-ultra). The user imports one
 * encoder; the mel front ends and the decoder are bundled with the app.
 */
internal object ParakeetUltraModels {
    const val ARCHITECTURE = "parakeet-ultra"
    const val CPU_BACKEND = "litert-ultra-cpu"
    const val F16_NOTE = "Use the i8 encoder (CPU); f16 is for GPU/NPU and not supported yet"

    private val encoderPattern =
        Regex("parakeet_ultra_encoder_(10|20|30)s_(i8|f16)\\.tflite", RegexOption.IGNORE_CASE)

    data class Encoder(val windowSeconds: Int, val precision: String) {
        val isCpu: Boolean get() = precision == "i8"
    }

    /** Any file of the Ultra family, including the bundled parts and unsupported variants. */
    fun isUltraName(name: String): Boolean {
        val lower = QualcommModels.normalizeName(name).lowercase()
        return "parakeet_ultra" in lower || "parakeet-ultra" in lower
    }

    /** The encoder described by [name] (browser duplicate suffixes tolerated), or null. */
    fun parseEncoder(name: String): Encoder? =
        encoderPattern.matchEntire(QualcommModels.normalizeName(name))?.let { match ->
            Encoder(match.groupValues[1].toInt(), match.groupValues[2].lowercase())
        }

    fun summary(encoder: Encoder): String = "Parakeet Ultra · CPU · ${encoder.windowSeconds} s windows"

    fun evaluate(name: String): QualcommModels.Evaluation {
        val encoder = parseEncoder(name)
        val lower = QualcommModels.normalizeName(name).lowercase()
        return when {
            encoder != null && encoder.isCpu ->
                QualcommModels.Evaluation(CPU_BACKEND, true, summary(encoder), ARCHITECTURE)
            encoder != null -> QualcommModels.Evaluation(CPU_BACKEND, false, F16_NOTE, ARCHITECTURE)
            "_mel_" in lower || "_decoder_" in lower -> QualcommModels.Evaluation(
                CPU_BACKEND, false,
                "Bundled with the app; import a parakeet_ultra_encoder_{10|20|30}s_i8.tflite encoder",
                ARCHITECTURE
            )
            else -> QualcommModels.Evaluation(
                CPU_BACKEND, false,
                "Not a Parakeet Ultra encoder; import parakeet_ultra_encoder_{10|20|30}s_i8.tflite",
                ARCHITECTURE
            )
        }
    }
}
