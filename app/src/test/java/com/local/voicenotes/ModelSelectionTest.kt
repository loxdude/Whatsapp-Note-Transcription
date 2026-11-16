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
