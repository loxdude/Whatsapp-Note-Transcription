package com.local.voicenotes.model

import com.local.voicenotes.domain.ImportedModel
import com.local.voicenotes.modelSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParakeetUltraModelsTest {
    @Test fun i8EncodersAreEnabledOnTheUltraCpuBackendWithTheirWindow() {
        for (seconds in listOf(10, 20, 30)) {
            val name = "parakeet_ultra_encoder_${seconds}s_i8.tflite"
            val evaluation = ParakeetUltraModels.evaluate(name)
            assertTrue(name, evaluation.enabled)
            assertEquals("litert-ultra-cpu", evaluation.backend)
            assertEquals("parakeet-ultra", evaluation.architecture)
            assertEquals("Parakeet Ultra · CPU · $seconds s windows", evaluation.note)
            assertEquals(ParakeetUltraModels.Encoder(seconds, "i8"), ParakeetUltraModels.parseEncoder(name))
            assertTrue(ParakeetUltraModels.isLocalBackend(evaluation.backend))
            assertNull(ParakeetUltraModels.importError(name))
        }
    }

    @Test fun f16EncodersAreShownButDisabled() {
        val evaluation = ParakeetUltraModels.evaluate("parakeet_ultra_encoder_30s_f16.tflite")
        assertFalse(evaluation.enabled)
        assertEquals("parakeet-ultra", evaluation.architecture)
        assertTrue(evaluation.note.contains("use the i8 encoder (CPU); f16 is for GPU/NPU and not supported yet", ignoreCase = true))
        assertFalse(ParakeetUltraModels.parseEncoder("parakeet_ultra_encoder_30s_f16.tflite")!!.isCpu)
    }

    @Test fun browserDuplicateSuffixesAreTolerated() {
        for (name in listOf(
            "parakeet_ultra_encoder_30s_i8 (1).tflite",
            "parakeet_ultra_encoder_30s_i8(2).tflite",
            "parakeet_ultra_encoder_30s_i8-1.tflite",
            "Parakeet_Ultra_Encoder_30s_I8 (1) (2).tflite"
        )) {
            assertEquals(name, ParakeetUltraModels.Encoder(30, "i8"), ParakeetUltraModels.parseEncoder(name))
            assertTrue(name, ParakeetUltraModels.evaluate(name).enabled)
            assertEquals("litert-ultra-cpu", ParakeetUltraModels.evaluate(name).backend)
        }
        assertEquals(10, ParakeetUltraModels.parseEncoder("parakeet_ultra_encoder_10s_i8 (3).tflite")!!.windowSeconds)
    }

    @Test fun otherUltraFilesAreRecognizedButDisabled() {
        for (name in listOf(
            "parakeet_ultra_mel_30s.tflite",
            "parakeet_ultra_decoder_i8.tflite",
            "parakeet_ultra_encoder_15s_i8.tflite",
            "parakeet_ultra_encoder_30s_i4.tflite"
        )) {
            val evaluation = ParakeetUltraModels.evaluate(name)
            assertFalse(name, evaluation.enabled)
            assertEquals(name, "parakeet-ultra", evaluation.architecture)
            assertNull(name, ParakeetUltraModels.parseEncoder(name))
        }
        assertTrue(ParakeetUltraModels.evaluate("parakeet_ultra_decoder_i8.tflite").note.contains("Bundled"))
    }

    @Test fun importingV3OrNpuExportsFailsWithAPointerToUltra() {
        for (name in listOf(
            "parakeet_tdt_0.6b_v3_5s_i8_stateful.tflite",
            "parakeet_tdt_0.6b_v3_5s_f32_stateful (1).tflite",
            "parakeet_tdt_0.6b_v3_5s_f32_stateful_Qualcomm_SM8650.tflite",
            "parakeet_tdt_0.6b_v3_5s_i8.tflite"
        )) {
            val error = ParakeetUltraModels.importError(name)
            assertTrue(name, error!!.contains("Parakeet v3 and NPU exports are no longer supported"))
            assertTrue(name, error.contains("parakeet_ultra_encoder_30s_i8.tflite"))
            assertFalse(ParakeetUltraModels.isUltraName(name))
        }
        val other = ParakeetUltraModels.importError("whisper_small.tflite")
        assertTrue(other!!.startsWith("Not a Parakeet Ultra model"))
        assertTrue(other.contains("parakeet_ultra_encoder_30s_i8.tflite"))
        // Ultra-family files are not rejected at import; unusable ones are listed disabled.
        assertNull(ParakeetUltraModels.importError("parakeet_ultra_encoder_30s_f16.tflite"))
    }

    private fun record(name: String, backend: String, architecture: String = "parakeet-ultra") =
        ImportedModel("id", name, "content://models/id", 1L, architecture, backend, enabled = false)

    @Test fun catalogRefreshReEvaluatesUltraRecords() {
        val refreshed = ModelRecords.refresh(record("parakeet_ultra_encoder_20s_i8.tflite", "litert-ultra-cpu"))
        assertTrue(refreshed.enabled)
        assertEquals("parakeet-ultra", refreshed.architecture)
        assertEquals("Parakeet Ultra · CPU · 20 s windows", refreshed.note)
        assertFalse(ModelRecords.refresh(record("parakeet_ultra_encoder_20s_f16.tflite", "litert-ultra-cpu")).enabled)
    }

    @Test fun mainScreenSummaryNamesTheModelFamily() {
        val ultra = ModelRecords.refresh(record("parakeet_ultra_encoder_30s_i8.tflite", "litert-ultra-cpu"))
        assertEquals("On-device model: Parakeet Ultra", modelSummary(ultra))
        assertEquals("Cloud model: Mistral", modelSummary(ModelRecords.builtInMistral))
        assertTrue(modelSummary(null).startsWith("No model selected"))
    }
}
