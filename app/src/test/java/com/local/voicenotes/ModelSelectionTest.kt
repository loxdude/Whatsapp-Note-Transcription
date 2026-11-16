package com.local.voicenotes

import com.local.voicenotes.domain.ImportedModel
import com.local.voicenotes.domain.TranscriptionProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelSelectionTest {
    private val mistral = model("mistral-api", backend = "mistral-api").copy(isApiModel = true)
    private val backendOnlyCloud = model("cloud-by-backend", backend = "mistral-api")
    private val disabled = model("disabled", enabled = false)

    @Test fun cloudIsNeverPickedAutomatically() {
        assertNull(chooseSelection(listOf(mistral), current = null, saved = null))
        assertNull(chooseSelection(listOf(mistral, backendOnlyCloud, disabled), null, null))
        assertEquals(MODEL_A.id, chooseSelection(listOf(mistral, MODEL_A, MODEL_B), null, null))
    }

    @Test fun explicitCloudChoiceIsKept() {
        assertEquals(mistral.id, chooseSelection(listOf(mistral, MODEL_A), current = null, saved = mistral.id))
        assertEquals(mistral.id, chooseSelection(listOf(mistral, MODEL_A), current = mistral.id, saved = MODEL_A.id))
    }

    @Test fun currentBeatsSavedAndInvalidEntriesFallThrough() {
        val models = listOf(mistral, MODEL_A, MODEL_B, disabled)
        assertEquals(MODEL_B.id, chooseSelection(models, current = MODEL_B.id, saved = MODEL_A.id))
        assertEquals(MODEL_A.id, chooseSelection(models, current = "gone", saved = MODEL_A.id))
        assertEquals(MODEL_A.id, chooseSelection(models, current = disabled.id, saved = disabled.id))
    }

    @Test fun unreadableSavedLocalModelDoesNotFallBackToCloud() {
        // The saved local model is missing from the list (e.g. URI permission revoked).
        assertNull(chooseSelection(listOf(mistral), current = null, saved = MODEL_A.id))
    }

    @Test fun freshInstallWithOnlyCloudSelectsNothingAndCannotTranscribe() = withCoordinator(listOf(MODEL_CLOUD)) { h ->
        h.catalog.saved = null
        h.coordinator.refreshModels().join()
        settle()
        assertNull(h.state.selectedModelId)
        h.coordinator.setAudio("file:///voice.ogg", "voice.ogg")
        assertFalse(h.state.canTranscribe)
        assertFalse(h.coordinator.transcribe())
        assertEquals(0, h.cloud.transcribeCalls.get())
        assertTrue(h.cloud.prepared.isEmpty())
    }

    @Test fun refreshPrefersFirstLocalModelOverEarlierCloudEntry() = withCoordinator(listOf(MODEL_CLOUD, MODEL_A)) { h ->
        h.catalog.saved = null
        h.coordinator.refreshModels().join()
        h.await { !it.busy }
        assertEquals(MODEL_A.id, h.state.selectedModelId)
        assertTrue(h.catalog.selections.isEmpty())
    }

    @Test fun savedCloudSelectionIsRestored() = withCoordinator(listOf(MODEL_A, MODEL_CLOUD)) { h ->
        h.catalog.saved = MODEL_CLOUD.id
        h.coordinator.refreshModels().join()
        h.await { !it.busy }
        assertEquals(MODEL_CLOUD.id, h.state.selectedModelId)
    }

    @Test fun disabledImportWithOnlyCloudLeftSelectsNothing() = withCoordinator(listOf(MODEL_CLOUD)) { h ->
        h.coordinator.refreshModels().join()
        settle()
        val stateless = model("stateless", enabled = false, note = "Stateless export")
        h.catalog.onImport = { _, _ -> h.catalog.stored += stateless; stateless }
        assertTrue(h.coordinator.importModel("content://models/stateless"))
        val state = h.await { !it.busy }
        assertEquals(TranscriptionProgress.Failed("Stateless export"), state.progress)
        assertNull(state.selectedModelId)
    }

    @Test fun savedV3SelectionAfterUpgradeSelectsNothingAndShowsTheNoModelHint() {
        val v3 = ImportedModel(
            "v3", "parakeet_tdt_0.6b_v3_5s_i8_stateful.tflite", "content://models/v3", 1L,
            "parakeet-tdt-0.6b-v3", "litert-cpu", enabled = true
        )
        val listed = com.local.voicenotes.model.ModelRecords.refresh(v3)
        withCoordinator(listOf(MODEL_CLOUD, listed)) { h ->
            h.catalog.saved = v3.id
            h.coordinator.refreshModels().join()
            settle()
            assertNull(h.state.selectedModelId)
            assertEquals(listOf(MODEL_CLOUD.id, v3.id), h.state.models.map { it.id })
            assertFalse(h.state.models.first { it.id == v3.id }.enabled)
            assertTrue(modelSummary(h.state.selectedModel).startsWith("No model selected"))
            h.coordinator.setAudio("file:///voice.ogg", "voice.ogg")
            assertFalse(h.state.canTranscribe)
            assertFalse(h.coordinator.selectModel(v3.id))
            assertTrue(h.cloud.prepared.isEmpty())
            assertTrue(h.local.prepared.isEmpty())
        }
    }

    @Test fun importingAV3ModelFailsWithThePointerToUltraAndKeepsTheSelection() = withCoordinator(listOf(MODEL_CLOUD, MODEL_A)) { h ->
        h.coordinator.refreshModels().join()
        h.await { !it.busy }
        val name = "parakeet_tdt_0.6b_v3_5s_i8_stateful.tflite"
        // ModelRepository.import throws this before persisting anything.
        h.catalog.onImport = { _, _ ->
            throw IllegalArgumentException(com.local.voicenotes.model.ParakeetUltraModels.importError(name))
        }
        assertTrue(h.coordinator.importModel("content://models/v3"))
        val state = h.await { !it.busy }
        val failed = state.progress as TranscriptionProgress.Failed
        assertTrue(failed.message, failed.message.contains("parakeet_ultra_encoder_30s_i8.tflite"))
        assertTrue(failed.message, failed.message.contains("no longer supported"))
        assertEquals(MODEL_A.id, state.selectedModelId)
        assertEquals(listOf(MODEL_CLOUD.id, MODEL_A.id), state.models.map { it.id })
    }

    private fun withCoordinator(models: List<ImportedModel>, block: suspend (Harness) -> Unit) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + coroutineContext[kotlin.coroutines.ContinuationInterceptor]!!)
        try {
            val h = Harness(scope)
            h.catalog.stored.clear()
            h.catalog.stored.addAll(models)
            block(h)
        } finally { scope.cancel() }
    }
}
