package com.local.voicenotes.model

/** AOT exports target an NPU generation, not the phone's CPU clock speed. */
internal object QualcommModels {
    const val V3_ARCHITECTURE = "parakeet-tdt-0.6b-v3"
    private val socPattern = Regex("SM\\d{4}", RegexOption.IGNORE_CASE)
    private val portablePattern = Regex("parakeet_tdt_0\\.6b_v3_5s_(f32|i8)_stateful\\.tflite", RegexOption.IGNORE_CASE)

    /** Browsers and file managers append " (1)", "(2)" or "-1" to repeated downloads. */
    private val duplicateSuffix = Regex("(?:\\s*\\(\\d+\\)|-\\d+)+(\\.tflite)$", RegexOption.IGNORE_CASE)

    /** Result of evaluating a LiteRT model file name against the current device. */
    data class Evaluation(
        val backend: String,
        val enabled: Boolean,
        val note: String,
        val architecture: String = V3_ARCHITECTURE
    )

    /** Strips duplicate-download suffixes so "x_stateful (1).tflite" is treated as "x_stateful.tflite". */
    fun normalizeName(name: String): String = name.trim().replace(duplicateSuffix, "$1")

    fun isPortable(name: String): Boolean = portablePattern.matches(normalizeName(name))

    fun backend(name: String): String = when {
        ParakeetUltraModels.isUltraName(name) -> ParakeetUltraModels.CPU_BACKEND
        isPortable(name) -> "litert-cpu"
        else -> "litert-qnn"
    }

    fun target(name: String): String? = socPattern.find(name)?.value?.uppercase()

    fun recommendedTarget(soc: String): String? = when (target(soc)) {
        "SM8475" -> "SM8450" // Both use HTP v69; native QNN still validates the binary.
        "SM8450", "SM8550", "SM8650", "SM8750", "SM8850" -> target(soc)
        else -> null
    }

    fun supportsLegacyDevice(manufacturer: String, soc: String): Boolean =
        (manufacturer.equals("QTI", true) || manufacturer.equals("Qualcomm", true)) &&
            soc.uppercase() in setOf("SM8450", "SM8475")

    /**
     * Exports known to load but produce wrong output on [soc]: the SM8450 NPU export returns
     * blank transcripts on SM8475 (Galaxy Z Fold 4) even though both report HTP v69.
     */
    fun isBlocked(name: String, soc: String): Boolean =
        !isPortable(name) && target(name) == "SM8450" && target(soc) == "SM8475"

    fun note(name: String, soc: String): String {
        val export = target(name)
        val recommended = recommendedTarget(soc)
        return when {
            isPortable(name) -> "Offline CPU · portable stateful model"
            isBlocked(name, soc) ->
                "SM8450 NPU export returned blank speech on Fold 4; use the portable i8 stateful model"
            export == null -> "Stateful export · chip target unknown; NPU validation required"
            recommended == null -> "Stateful $export export · device compatibility unverified ($soc)"
            export != recommended -> "Export for $export · this device ($soc) needs $recommended"
            else -> "Stateful $export export · device $soc · NPU validation required"
        }
    }

    /**
     * Evaluates a LiteRT model purely from its file name and the device SoC. Used at import time
     * and on every catalog read, so a persisted `enabled` flag never outlives a rule change or a
     * device change (e.g. a restored backup).
     */
    fun evaluate(name: String, soc: String): Evaluation {
        if (ParakeetUltraModels.isUltraName(name)) return ParakeetUltraModels.evaluate(name)
        val lower = normalizeName(name).lowercase()
        val isParakeet = "parakeet" in lower && ("tdt" in lower || "0.6b_v3" in lower)
        val isStateful = "stateful" in lower
        val backend = backend(name)
        return when {
            !isParakeet -> Evaluation(backend, false, "Not recognized as a Parakeet TDT v3 export")
            !isStateful -> Evaluation(backend, false, "Stateless 5 s export; select the stateful model for WhatsApp notes")
            else -> Evaluation(backend, !isBlocked(name, soc), note(name, soc))
        }
    }

    fun isLiteRtBackend(backend: String): Boolean =
        backend.startsWith("litert-qnn") || backend == "litert-cpu" || backend == ParakeetUltraModels.CPU_BACKEND
}
