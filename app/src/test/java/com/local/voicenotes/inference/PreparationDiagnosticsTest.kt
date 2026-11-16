package com.local.voicenotes.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreparationDiagnosticsTest {
    private val encoder = "parakeet_ultra_encoder_30s_i8.tflite"

    @Test fun cpuFailureNamesModelAndReason() {
        assertEquals(
            "CPU preparation failed for $encoder. bad flatbuffer",
            PreparationDiagnostics.message(encoder, IllegalStateException("bad flatbuffer"))
        )
    }

    @Test fun blankReasonFallsBackToExceptionType() {
        val message = PreparationDiagnostics.message(encoder, UnsatisfiedLinkError())

        assertTrue(message.endsWith("UnsatisfiedLinkError"))
    }
}
