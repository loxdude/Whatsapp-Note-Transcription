package com.local.voicenotes.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreparationDiagnosticsTest {
    private val portable = "parakeet_tdt_0.6b_v3_5s_i8_stateful.tflite"

    @Test fun cpuFailureNamesCpuAndOmitsQualcommDetails() {
        val message = PreparationDiagnostics.message(
            ExecutionTarget.CPU, portable, "Samsung", "s5e9925", IllegalStateException("bad flatbuffer")
        )

        assertEquals("CPU preparation failed for $portable. bad flatbuffer", message)
        assertFalse(message.contains("Qualcomm"))
        assertFalse(message.contains("NPU"))
    }

    @Test fun npuFailureNamesNpuDeviceNoteAndReason() {
        val model = "parakeet_tdt_0.6b_v3_5s_f32_stateful_Qualcomm_SM8750.tflite"
        val message = PreparationDiagnostics.message(
            ExecutionTarget.QUALCOMM_NPU, model, "QTI", "SM8650",
            AcceleratorSetupException(
                ExecutionTarget.QUALCOMM_NPU, AcceleratorSetupException.Failure.LIBRARY_NOT_READY,
                "The LiteRT Qualcomm dispatch runtime is not available."
            )
        )

        assertTrue(message.startsWith("Qualcomm NPU preparation failed on QTI SM8650."))
        assertTrue(message.contains("needs SM8650"))
        assertTrue(message.contains("dispatch runtime is not available"))
        // Points the user to an explicit CPU choice rather than silently falling back.
        assertTrue(message.contains("To run on CPU instead"))
    }

    @Test fun npuFailureDoesNotRepeatPortableAdviceAlreadyInNote() {
        val message = PreparationDiagnostics.message(
            ExecutionTarget.QUALCOMM_NPU, "parakeet_stateful_SM8450.tflite", "QTI", "SM8475",
            IllegalStateException("blank")
        )

        assertTrue(message.contains("use the portable i8 stateful model"))
        assertFalse(message.contains("To run on CPU instead"))
    }

    @Test fun blankReasonFallsBackToExceptionType() {
        val message = PreparationDiagnostics.message(
            ExecutionTarget.CPU, portable, "", "", UnsatisfiedLinkError()
        )

        assertTrue(message.endsWith("UnsatisfiedLinkError"))
    }
}
