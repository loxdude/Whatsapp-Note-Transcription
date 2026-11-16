package com.local.voicenotes.model

import org.junit.Assert.*
import org.junit.Test

class QualcommModelsTest {
    @Test fun portableModelsUseCpuAndNpuExportsStayOnNpu() {
        assertEquals("litert-cpu", QualcommModels.backend("parakeet_tdt_0.6b_v3_5s_i8_stateful.tflite"))
        assertEquals("litert-cpu", QualcommModels.backend("parakeet_tdt_0.6b_v3_5s_f32_stateful.tflite"))
        assertEquals("litert-qnn", QualcommModels.backend("parakeet_tdt_0.6b_v3_5s_f32_stateful_Qualcomm_SM8750.tflite"))
        assertEquals("litert-qnn", QualcommModels.backend("parakeet_tdt_0.6b_v3_5s_f32_stateful_Google_Tensor_G5.tflite"))
        assertTrue(QualcommModels.note("parakeet_stateful_SM8450.tflite", "SM8475").contains("blank"))
    }
    @Test fun fold4UsesV69Export() {
        assertEquals("SM8450", QualcommModels.recommendedTarget("SM8475"))
        assertTrue(QualcommModels.supportsLegacyDevice("QTI", "SM8475"))
        assertTrue(QualcommModels.supportsLegacyDevice("Qualcomm", "SM8450"))
    }

    @Test fun overrideDoesNotAdmitOtherDevices() {
        assertFalse(QualcommModels.supportsLegacyDevice("Samsung", "SM8475"))
        assertFalse(QualcommModels.supportsLegacyDevice("QTI", "SM8750"))
        assertFalse(QualcommModels.supportsLegacyDevice("QTI", "SM8350"))
    }

    @Test fun eliteNeedsItsOwnExport() {
        assertEquals("SM8750", QualcommModels.recommendedTarget("SM8750"))
        assertTrue(QualcommModels.note("parakeet_stateful_SM8650.tflite", "SM8750").contains("needs SM8750"))
        assertFalse(QualcommModels.note("parakeet_stateful_SM8750.tflite", "SM8750").contains("needs"))
    }

    @Test fun unknownTargetIsNotCalledReady() {
        assertNull(QualcommModels.target("parakeet_stateful.tflite"))
        assertTrue(QualcommModels.note("parakeet_stateful.tflite", "SM8475").contains("unknown"))
    }

    @Test fun duplicateDownloadSuffixesStillRouteToCpu() {
        for (name in listOf(
            "parakeet_tdt_0.6b_v3_5s_i8_stateful (1).tflite",
            "parakeet_tdt_0.6b_v3_5s_i8_stateful(2).tflite",
            "parakeet_tdt_0.6b_v3_5s_i8_stateful-1.tflite",
            "parakeet_tdt_0.6b_v3_5s_f32_stateful (1) (2).tflite"
        )) assertEquals(name, "litert-cpu", QualcommModels.backend(name))
        assertEquals("litert-qnn", QualcommModels.backend("parakeet_tdt_0.6b_v3_5s_i8_stateful_SM8750 (1).tflite"))
        assertEquals("litert-qnn", QualcommModels.backend("parakeet_tdt_0.6b_v3_5s_i8_stateless (1).tflite"))
    }

    @Test fun sm8450ExportIsBlockedOnlyOnSm8475() {
        val export = "parakeet_tdt_0.6b_v3_5s_f32_stateful_Qualcomm_SM8450.tflite"
        assertTrue(QualcommModels.isBlocked(export, "SM8475"))
        assertFalse(QualcommModels.isBlocked(export, "SM8450"))
        assertFalse(QualcommModels.isBlocked("parakeet_tdt_0.6b_v3_5s_i8_stateful.tflite", "SM8475"))
        assertFalse(QualcommModels.isBlocked("parakeet_stateful_SM8750.tflite", "SM8475"))
        val evaluation = QualcommModels.evaluate(export, "SM8475")
        assertFalse(evaluation.enabled)
        assertTrue(evaluation.note.contains("portable i8"))
        assertTrue(QualcommModels.evaluate(export, "SM8450").enabled)
    }

    @Test fun evaluationRejectsNonParakeetAndStatelessExports() {
        assertFalse(QualcommModels.evaluate("whisper.tflite", "SM8475").enabled)
        assertFalse(QualcommModels.evaluate("parakeet_tdt_0.6b_v3_5s_i8.tflite", "SM8475").enabled)
        assertTrue(QualcommModels.evaluate("parakeet_tdt_0.6b_v3_5s_i8_stateful (1).tflite", "SM8475").enabled)
    }
}
