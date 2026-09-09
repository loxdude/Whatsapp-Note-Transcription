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
}
