package com.local.voicenotes.model

/** AOT exports target an NPU generation, not the phone's CPU clock speed. */
internal object QualcommModels {
    private val socPattern = Regex("SM\\d{4}", RegexOption.IGNORE_CASE)
    private val portablePattern = Regex("parakeet_tdt_0\\.6b_v3_5s_(f32|i8)_stateful\\.tflite", RegexOption.IGNORE_CASE)

    fun isPortable(name: String): Boolean = portablePattern.matches(name)

    fun backend(name: String): String = if (isPortable(name)) "litert-cpu" else "litert-qnn"

    fun target(name: String): String? = socPattern.find(name)?.value?.uppercase()

    fun recommendedTarget(soc: String): String? = when (target(soc)) {
        "SM8475" -> "SM8450" // Both use HTP v69; native QNN still validates the binary.
        "SM8450", "SM8550", "SM8650", "SM8750", "SM8850" -> target(soc)
        else -> null
    }

    fun supportsLegacyDevice(manufacturer: String, soc: String): Boolean =
        (manufacturer.equals("QTI", true) || manufacturer.equals("Qualcomm", true)) &&
            soc.uppercase() in setOf("SM8450", "SM8475")

    fun note(name: String, soc: String): String {
        val export = target(name)
        val recommended = recommendedTarget(soc)
        return when {
            isPortable(name) -> "Offline CPU · portable stateful model"
            export == "SM8450" && target(soc) == "SM8475" ->
                "SM8450 NPU export returned blank speech on Fold 4; use the portable i8 stateful model"
            export == null -> "Stateful export · chip target unknown; NPU validation required"
            recommended == null -> "Stateful $export export · device compatibility unverified ($soc)"
            export != recommended -> "Export for $export · this device ($soc) needs $recommended"
            else -> "Stateful $export export · device $soc · NPU validation required"
        }
    }
}
