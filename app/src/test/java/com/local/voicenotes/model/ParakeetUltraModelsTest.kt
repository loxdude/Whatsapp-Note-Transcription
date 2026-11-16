package com.local.voicenotes.model

import com.local.voicenotes.domain.ImportedModel
import com.local.voicenotes.inference.ExecutionTarget
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
            val evaluation = QualcommModels.evaluate(name, "SM8475")
            assertTrue(name, evaluation.enabled)
            assertEquals("litert-ultra-cpu", evaluation.backend)
            assertEquals("parakeet-ultra", evaluation.architecture)
            assertEquals("Parakeet Ultra · CPU · $seconds s windows", evaluation.note)
            assertEquals(ParakeetUltraModels.Encoder(seconds, "i8"), ParakeetUltraModels.parseEncoder(name))
            assertEquals(ExecutionTarget.CPU, ExecutionTarget.forBackend(evaluation.backend))
        }
    }

    @Test fun f16EncodersAreShownButDisabled() {
        val evaluation = QualcommModels.evaluate("parakeet_ultra_encoder_30s_f16.tflite", "SM8650")
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
            assertTrue(name, QualcommModels.evaluate(name, "SM8475").enabled)
            assertEquals("litert-ultra-cpu", QualcommModels.backend(name))
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
            val evaluation = QualcommModels.evaluate(name, "SM8475")
            assertFalse(name, evaluation.enabled)
            assertEquals(name, "parakeet-ultra", evaluation.architecture)
            assertNull(name, ParakeetUltraModels.parseEncoder(name))
        }
        assertTrue(QualcommModels.evaluate("parakeet_ultra_decoder_i8.tflite", "SM8475").note.contains("Bundled"))
    }

    @Test fun v3ModelsKeepTheirRouting() {
        val v3 = QualcommModels.evaluate("parakeet_tdt_0.6b_v3_5s_i8_stateful.tflite", "SM8475")
        assertEquals("litert-cpu", v3.backend)
        assertEquals("parakeet-tdt-0.6b-v3", v3.architecture)
        assertTrue(v3.enabled)
        assertFalse(ParakeetUltraModels.isUltraName("parakeet_tdt_0.6b_v3_5s_i8_stateful.tflite"))
    }

    private fun record(name: String, backend: String, architecture: String = "parakeet-tdt-0.6b-v3") =
        ImportedModel("id", name, "content://models/id", 1L, architecture, backend, enabled = false)

    @Test fun catalogRefreshReEvaluatesUltraRecords() {
        val refreshed = ModelRecords.refresh(record("parakeet_ultra_encoder_20s_i8.tflite", "litert-ultra-cpu"), "SM8475")
        assertTrue(refreshed.enabled)
        assertEquals("parakeet-ultra", refreshed.architecture)
        assertEquals("Parakeet Ultra · CPU · 20 s windows", refreshed.note)
        assertFalse(ModelRecords.refresh(record("parakeet_ultra_encoder_20s_f16.tflite", "litert-ultra-cpu"), "SM8475").enabled)
        // Existing v3 records are unchanged in kind.
        val v3 = ModelRecords.refresh(record("parakeet_tdt_0.6b_v3_5s_i8_stateful.tflite", "litert-cpu"), "SM8475")
        assertEquals("parakeet-tdt-0.6b-v3", v3.architecture)
        assertEquals("litert-cpu", v3.backend)
        assertTrue(v3.enabled)
    }

    @Test fun mainScreenSummaryNamesTheModelFamily() {
        val ultra = ModelRecords.refresh(record("parakeet_ultra_encoder_30s_i8.tflite", "litert-ultra-cpu"), "SM8475")
        assertEquals("On-device model: Parakeet Ultra", modelSummary(ultra))
        assertEquals("On-device model: Parakeet",
            modelSummary(record("parakeet_tdt_0.6b_v3_5s_i8_stateful.tflite", "litert-cpu")))
        assertEquals("Cloud model: Mistral", modelSummary(ModelRecords.builtInMistral))
        assertTrue(modelSummary(null).startsWith("No model selected"))
    }
}
