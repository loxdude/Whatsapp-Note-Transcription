package com.local.voicenotes

import com.local.voicenotes.domain.ImportedModel
import com.local.voicenotes.domain.TranscriptionProgress
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

/**
 * Regressions found by independent review of the benchmark candidates: stale startup
 * refreshes, terminal states resurrected by late progress, and the GLM startup/audio race.
 */
class CoordinatorReviewRegressionTest {
    @Test fun startupAudioDoesNotDiscardGatedModelRefresh() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val backend = FakeBackend()
        val catalog = object : ModelCatalog {
            override suspend fun models(): List<ImportedModel> { entered.complete(Unit); release.await(); return listOf(MODEL_A) }
            override suspend fun selectedModelId(): String? = MODEL_A.id
            override suspend fun select(id: String) = Unit
            override suspend fun import(source: String, onProgress: (Float) -> Unit): ImportedModel = error("unused")
        }
        val scope = CoroutineScope(SupervisorJob() + coroutineContext[kotlin.coroutines.ContinuationInterceptor]!!)
        try {
            val c = TranscriptionCoordinator(scope, catalog, PcmDecoder { _, _ -> floatArrayOf(1f) }, mapOf("local" to backend))
            val refresh = c.refreshModels(); entered.await()
            c.setAudio("file:///audio.wav", "audio.wav")
            release.complete(Unit); refresh.join()
            withTimeout(1000) { c.state.first { it.selectedModelId == MODEL_A.id && !it.busy } }
            assertEquals(listOf(MODEL_A), c.state.value.models)
            assertEquals("file:///audio.wav", c.state.value.audioUri)
            assertEquals(listOf(MODEL_A.id), backend.prepared)
            assertTrue(c.state.value.canTranscribe)
        } finally { scope.cancel() }
    }

    @Test fun lateProgressAfterSuccessfulTranscriptionDoesNotResurrectBusyState() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + coroutineContext[kotlin.coroutines.ContinuationInterceptor]!!)
        try {
            val h = Harness(scope); h.ready()
            lateinit var callback: (Float) -> Unit
            h.local.onTranscribe = { _, progress -> callback = progress; "done" }
            assertTrue(h.coordinator.transcribe())
            val done = h.await { it.progress is TranscriptionProgress.Completed }
            assertEquals("done", done.transcript)
            callback(0.7f)
            assertEquals("late callback must not change terminal state", done, h.state)
        } finally { scope.cancel() }
    }

    @Test fun lateDecoderProgressAfterSuccessfulTranscriptionDoesNotResurrectBusyState() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + coroutineContext[kotlin.coroutines.ContinuationInterceptor]!!)
        try {
            val h = Harness(scope); h.ready()
            lateinit var callback: (Float) -> Unit
            h.onDecode = { _, progress -> callback = progress; floatArrayOf(1f) }
            assertTrue(h.coordinator.transcribe())
            val done = h.await { it.progress is TranscriptionProgress.Completed }
            callback(0.5f)
            assertEquals("late decoder callback must not change terminal state", done, h.state)
        } finally { scope.cancel() }
    }

    @Test fun startupRefreshDoesNotOverwriteImportCompletedWhileSelectionReadWaits() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val imported = model("new")
        var stored = listOf(MODEL_A)
        val catalog = object : ModelCatalog {
            override suspend fun models(): List<ImportedModel> = stored.toList()
            override suspend fun selectedModelId(): String? { entered.complete(Unit); release.await(); return MODEL_A.id }
            override suspend fun select(id: String) = Unit
            override suspend fun import(source: String, onProgress: (Float) -> Unit): ImportedModel { stored = stored + imported; return imported }
        }
        val scope = CoroutineScope(SupervisorJob() + coroutineContext[kotlin.coroutines.ContinuationInterceptor]!!)
        try {
            val c = TranscriptionCoordinator(scope, catalog, PcmDecoder { _, _ -> floatArrayOf(1f) }, mapOf("local" to FakeBackend()))
            val startup = c.refreshModels(); entered.await()
            assertTrue(c.importModel("content://new"))
            withTimeout(1000) { c.state.first { it.selectedModelId == imported.id && !it.busy } }
            release.complete(Unit); startup.join(); settle()
            assertEquals("startup snapshot must not replace newer import", imported.id, c.state.value.selectedModelId)
            assertTrue(c.state.value.models.any { it.id == imported.id })
        } finally { scope.cancel() }
    }

    @Test fun startupRefreshDoesNotEraseCancelledImportOutcome() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val importing = CompletableDeferred<Unit>()
        val catalog = object : ModelCatalog {
            override suspend fun models(): List<ImportedModel> { entered.complete(Unit); release.await(); return listOf(MODEL_A) }
            override suspend fun selectedModelId(): String? = MODEL_A.id
            override suspend fun select(id: String) = Unit
            override suspend fun import(source: String, onProgress: (Float) -> Unit): ImportedModel { importing.complete(Unit); awaitCancellation() }
        }
        val scope = CoroutineScope(SupervisorJob() + coroutineContext[kotlin.coroutines.ContinuationInterceptor]!!)
        try {
            val c = TranscriptionCoordinator(scope, catalog, PcmDecoder { _, _ -> floatArrayOf(1f) }, mapOf("local" to FakeBackend()))
            val startup = c.refreshModels(); entered.await()
            assertTrue(c.importModel("content://new")); importing.await(); c.cancel()
            assertEquals(TranscriptionProgress.Cancelled, c.state.value.progress)
            release.complete(Unit); startup.join(); settle()
            assertEquals("startup must not overwrite cancellation", TranscriptionProgress.Cancelled, c.state.value.progress)
        } finally { scope.cancel() }
    }

    @Test fun oldRefreshCannotReplaceNewlyImportedModel() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var modelsCalls = 0
        val imported = model("new")
        var stored = listOf(MODEL_A)
        val catalog = object : ModelCatalog {
            override suspend fun models(): List<ImportedModel> {
                modelsCalls++
                if (modelsCalls == 2) { val snapshot = stored; entered.complete(Unit); release.await(); return snapshot }
                return stored
            }
            override suspend fun selectedModelId(): String? = MODEL_A.id
            override suspend fun select(id: String) = Unit
            override suspend fun import(source: String, onProgress: (Float) -> Unit): ImportedModel { stored = stored + imported; return imported }
        }
        val scope = CoroutineScope(SupervisorJob() + coroutineContext[kotlin.coroutines.ContinuationInterceptor]!!)
        try {
            val c = TranscriptionCoordinator(scope, catalog, PcmDecoder { _, _ -> floatArrayOf(1f) }, mapOf("local" to FakeBackend()))
            c.refreshModels().join(); withTimeout(1000) { c.state.first { it.selectedModelId == MODEL_A.id && !it.busy } }
            val stale = c.refreshModels(); entered.await()
            assertTrue(c.importModel("content://new"))
            withTimeout(1000) { c.state.first { it.selectedModelId == imported.id && !it.busy } }
            release.complete(Unit); stale.join(); settle()
            assertEquals("new import must remain selected", imported.id, c.state.value.selectedModelId)
            assertTrue("new import must remain visible", c.state.value.models.any { it.id == imported.id })
        } finally { scope.cancel() }
    }

    @Test fun startupRefreshStillSelectsModelWhenCancelledImportReconciledTheList() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val importing = CompletableDeferred<Unit>()
        var calls = 0
        val catalog = object : ModelCatalog {
            override suspend fun models(): List<ImportedModel> {
                if (++calls == 1) { entered.complete(Unit); release.await() }
                return listOf(MODEL_A)
            }
            override suspend fun selectedModelId(): String? = MODEL_A.id
            override suspend fun select(id: String) = Unit
            override suspend fun import(source: String, onProgress: (Float) -> Unit): ImportedModel { importing.complete(Unit); awaitCancellation() }
        }
        val scope = CoroutineScope(SupervisorJob() + coroutineContext[kotlin.coroutines.ContinuationInterceptor]!!)
        try {
            val c = TranscriptionCoordinator(scope, catalog, PcmDecoder { _, _ -> floatArrayOf(1f) }, mapOf("local" to FakeBackend()))
            val startup = c.refreshModels(); entered.await()
            assertTrue(c.importModel("content://new")); importing.await(); c.cancel()
            settle() // reconcile publishes the list first
            release.complete(Unit); startup.join(); settle()
            assertEquals(TranscriptionProgress.Cancelled, c.state.value.progress)
            assertEquals("a usable model must still be selected", MODEL_A.id, c.state.value.selectedModelId)
            c.setAudio("file:///audio.wav", "audio.wav")
            assertTrue(c.state.value.canTranscribe)
        } finally { scope.cancel() }
    }
}
