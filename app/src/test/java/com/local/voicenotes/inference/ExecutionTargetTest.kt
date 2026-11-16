package com.local.voicenotes.inference

import com.local.voicenotes.model.QualcommModels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionTargetTest {
    @Test fun backendsMapToTheirAccelerator() {
        assertEquals(ExecutionTarget.CPU, ExecutionTarget.forBackend("litert-cpu"))
        assertEquals(ExecutionTarget.QUALCOMM_NPU, ExecutionTarget.forBackend("litert-qnn"))
        assertEquals(ExecutionTarget.QUALCOMM_NPU, ExecutionTarget.forBackend("litert-qnn-sm8650"))
    }

    @Test fun unknownBackendsAreRejectedInsteadOfDefaultingToCpu() {
        assertNull(ExecutionTarget.forBackendOrNull("mistral-api"))
        assertNull(ExecutionTarget.forBackendOrNull(""))
        assertNull(ExecutionTarget.forBackendOrNull("litert-gpu"))
        val error = assertThrows(IllegalArgumentException::class.java) { ExecutionTarget.forBackend("") }
        assertTrue(error.message!!.contains("not configured for local LiteRT"))
    }

    @Test fun portableModelFilesSelectTheCpuPath() {
        for (name in listOf(
            "parakeet_tdt_0.6b_v3_5s_i8_stateful.tflite",
            "parakeet_tdt_0.6b_v3_5s_f32_stateful.tflite"
        )) {
            assertEquals(name, ExecutionTarget.CPU, ExecutionTarget.forBackend(QualcommModels.backend(name)))
        }
    }

    @Test fun npuExportsNeverSelectTheCpuPath() {
        for (name in listOf(
            "parakeet_tdt_0.6b_v3_5s_f32_stateful_Qualcomm_SM8750.tflite",
            "parakeet_tdt_0.6b_v3_5s_f32_stateful_Qualcomm_SM8450.tflite",
            "parakeet_stateful.tflite"
        )) {
            assertEquals(name, ExecutionTarget.QUALCOMM_NPU, ExecutionTarget.forBackend(QualcommModels.backend(name)))
        }
    }

    @Test fun labelsNameTheAccelerator() {
        assertEquals("CPU", ExecutionTarget.CPU.label)
        assertFalse(ExecutionTarget.CPU.label.contains("NPU"))
        assertEquals("Qualcomm NPU", ExecutionTarget.QUALCOMM_NPU.label)
    }
}
