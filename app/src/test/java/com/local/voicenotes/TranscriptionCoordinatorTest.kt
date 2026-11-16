package com.local.voicenotes

import com.local.voicenotes.domain.TranscriptionProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.ContinuationInterceptor

class TranscriptionCoordinatorTest {

    /**
     * Runs the coordinator on the runBlocking event loop (a single thread, like the main
     * dispatcher) so interleavings are deterministic. Uncaught coroutine failures fail the test.
     */
    private fun coordinatorTest(block: suspend CoroutineScope.(Harness) -> Unit) = runBlocking {
        val uncaught = CopyOnWriteArrayList<Throwable>()
        val scope = CoroutineScope(
            SupervisorJob() + coroutineContext[ContinuationInterceptor]!! +
                CoroutineExceptionHandler { _, error -> uncaught += error }
        )
        try {
            withTimeout(10_000) { block(Harness(scope)) }
            settle()
        } finally {
            scope.cancel()
        }
        assertTrue("Uncaught coroutine failures: $uncaught", uncaught.isEmpty())
    }

    private val AppUiState.isCancelled get() = progress == TranscriptionProgress.Cancelled

    // ---- Transcription cancellation ---------------------------------------------------

    @Test fun cancelDuringDecodingIsReportedAsCancelledAndReachesTheDecoder() = coordinatorTest { h ->
        h.ready()
        val decoding = CompletableDeferred<Unit>()
        val decoderSawCancellation = CompletableDeferred<Throwable>()
        h.onDecode = { _, progress ->
            progress(0.25f)
            decoding.complete(Unit)
            try { awaitCancellation() } catch (e: CancellationException) {
                decoderSawCancellation.complete(e); throw e
            }
        }
        assertTrue(h.coordinator.transcribe())
        decoding.await()
        assertEquals(TranscriptionProgress.Decoding(0.25f), h.state.progress)

        h.coordinator.cancel()
        // Responsive: the state changes immediately, before the work has unwound.
        assertTrue(h.state.isCancelled)
        assertFalse(h.state.busy)

        withTimeout(1_000) { decoderSawCancellation.await() }
        settle()
        assertTrue(h.state.isCancelled)
        assertEquals(0, h.local.transcribeCalls.get())
        assertTrue(h.state.canTranscribe)
    }

    @Test fun lateProgressAndResultOfANonInterruptibleRunCannotOverwriteCancellation() = coordinatorTest { h ->
        h.ready()
        val gate = Gate()
        h.local.onTranscribe = { _, progress -> gate.nonCancellable { progress(0.9f); "stale text" } }
        h.coordinator.transcribe()
        gate.started.await()

        h.coordinator.cancel()
        h.coordinator.editTranscript("my own notes")
        gate.release.complete(Unit)
        gate.finished.await()
        settle()

        assertTrue(h.state.isCancelled)
        assertEquals("my own notes", h.state.transcript)
    }

    @Test fun newRunStartedAfterCancelIsNotOverwrittenByTheUnwindingRun() = coordinatorTest { h ->
        h.ready()
        val stale = Gate()
        val fresh = CompletableDeferred<String>()
        var call = 0
        h.local.onTranscribe = { _, progress ->
            if (++call == 1) stale.nonCancellable { progress(0.5f); "stale" } else fresh.await()
        }
        h.coordinator.transcribe()
        stale.started.await()
        h.coordinator.cancel()

        // The cancelled run is still stuck in "native" code, yet a new run may start at once.
        assertTrue(h.coordinator.transcribe())
        h.await { it.progress == TranscriptionProgress.Transcribing(0f) }
        val startedAt = h.now.get()

        stale.release.complete(Unit)
        stale.finished.await()
        settle()
        assertEquals(TranscriptionProgress.Transcribing(0f), h.state.progress)

        // The old run's cleanup must not stop the new run's clock.
        h.now.addAndGet(4_000)
        h.await { it.elapsedMillis == h.now.get() - startedAt }

        fresh.complete("fresh text")
        val done = h.await { it.progress is TranscriptionProgress.Completed }
        assertEquals("fresh text", done.transcript)
        assertEquals(2, h.local.transcribeCalls.get())
    }

    @Test fun cancellationReportedByTheBackendIsCancelledNotFailed() = coordinatorTest { h ->
        h.ready(MODEL_CLOUD.id)
        // MistralTranscriptionBackend maps an aborted HTTP call to CancellationException.
        h.cloud.onTranscribeUri = { _, _ -> throw CancellationException("Transcription cancelled") }
        h.coordinator.transcribe()
        val state = h.await { !it.busy }
        assertTrue(state.progress.toString(), state.isCancelled)
    }

    @Test fun ordinaryErrorsAreStillReportedAsFailures() = coordinatorTest { h ->
        h.ready()
        h.local.onTranscribe = { _, _ -> throw IllegalStateException("NPU exploded") }
        h.coordinator.transcribe()
        assertEquals(TranscriptionProgress.Failed("NPU exploded"), h.await { !it.busy }.progress)

        h.onDecode = { _, _ -> FloatArray(0) }
        h.coordinator.transcribe()
        assertEquals(
            TranscriptionProgress.Failed("The audio contains no decoded samples."),
            h.await { !it.busy }.progress
        )
    }

    @Test fun errorThrownBecauseOfCancellationIsNotShownAsFailure() = coordinatorTest { h ->
        h.ready()
        val gate = Gate()
        // e.g. a native call failing because its session was torn down during cancellation.
        h.local.onTranscribe = { _, _ -> gate.nonCancellable { throw IllegalStateException("session closed") } }
        h.coordinator.transcribe()
        gate.started.await()
        h.coordinator.cancel()
        gate.release.complete(Unit)
        gate.finished.await()
        settle()
        assertTrue(h.state.progress.toString(), h.state.isCancelled)
    }

    @Test fun successfulCloudAndLocalRunsComplete() = coordinatorTest { h ->
        h.ready()
        h.coordinator.transcribe()
        val local = h.await { it.progress is TranscriptionProgress.Completed }
        assertEquals("local text", local.transcript)
        assertEquals(MODEL_A.id, (local.progress as TranscriptionProgress.Completed).result.modelId)

        assertTrue(h.coordinator.selectModel(MODEL_CLOUD.id))
        h.await { !it.busy }
        h.coordinator.transcribe()
        assertEquals("cloud text", h.await { it.progress is TranscriptionProgress.Completed }.transcript)
    }

    @Test fun secondTranscribeWhileRunningIsRejected() = coordinatorTest { h ->
        h.ready()
        val release = CompletableDeferred<String>()
        h.local.onTranscribe = { _, _ -> release.await() }
        assertTrue(h.coordinator.transcribe())
        assertFalse(h.coordinator.transcribe())
        settle()
        assertFalse(h.coordinator.transcribe())
        release.complete("once")
        h.await { it.progress is TranscriptionProgress.Completed }
        assertEquals(1, h.local.transcribeCalls.get())
    }

    @Test fun cancelWhenIdleKeepsTheCompletedResult() = coordinatorTest { h ->
        h.ready()
        h.coordinator.transcribe()
        val done = h.await { it.progress is TranscriptionProgress.Completed }
        h.coordinator.cancel()
        assertEquals(done, h.state)
    }

    // ---- Audio and model selection changes --------------------------------------------

    @Test fun changingAudioCancelsTheRunningTranscriptionAndDiscardsItsResult() = coordinatorTest { h ->
        h.ready()
        val gate = Gate()
        h.local.onTranscribe = { _, progress -> gate.nonCancellable { progress(1f); "old audio text" } }
        h.coordinator.transcribe()
        gate.started.await()

        h.coordinator.setAudio("file:///other.ogg", "other.ogg")
        assertEquals(TranscriptionProgress.Idle, h.state.progress)
        gate.release.complete(Unit)
        gate.finished.await()
        settle()

        assertEquals("file:///other.ogg", h.state.audioUri)
        assertEquals("", h.state.transcript)
        assertEquals(TranscriptionProgress.Idle, h.state.progress)
    }

    @Test fun changingAudioDuringModelLoadingKeepsTheLoadingState() = coordinatorTest { h ->
        val prepare = CompletableDeferred<Result<Unit>>()
        h.local.onPrepare = { prepare.await() }
        h.catalog.saved = MODEL_A.id
        h.coordinator.refreshModels().join()
        assertEquals(TranscriptionProgress.LoadingModel, h.state.progress)

        h.coordinator.setAudio("file:///voice.ogg", "voice.ogg")
        // Previously this reset progress to Idle and allowed a transcription mid-preparation.
        assertEquals(TranscriptionProgress.LoadingModel, h.state.progress)
        assertFalse(h.state.canTranscribe)
        prepare.complete(Result.success(Unit))
        assertTrue(h.await { !it.busy }.canTranscribe)
    }

    @Test fun supersededPreparationCannotOverwriteTheNewerSelection() = coordinatorTest { h ->
        val gateA = Gate()
        h.local.onPrepare = { model ->
            if (model.id == MODEL_A.id) gateA.nonCancellable { Result.failure(IllegalStateException("A failed")) }
            else Result.success(Unit)
        }
        h.catalog.saved = MODEL_A.id
        h.coordinator.refreshModels().join()
        gateA.started.await()

        assertTrue(h.coordinator.selectModel(MODEL_B.id))
        h.await { !it.busy }
        gateA.release.complete(Unit)
        gateA.finished.await()
        settle()

        assertEquals(MODEL_B.id, h.state.selectedModelId)
        assertEquals(TranscriptionProgress.Idle, h.state.progress)
        assertEquals(listOf(MODEL_B.id), h.catalog.selections)
    }

    @Test fun cancelDuringModelLoadingThenTranscribingIsNotOverwrittenByLatePreparation() = coordinatorTest { h ->
        val prepareGate = Gate()
        h.local.onPrepare = { prepareGate.nonCancellable { Result.success(Unit) } }
        h.catalog.saved = MODEL_A.id
        h.coordinator.setAudio("file:///voice.ogg", "voice.ogg")
        h.coordinator.refreshModels().join()
        prepareGate.started.await()

        h.coordinator.cancel()
        assertTrue(h.state.isCancelled)
        val transcription = CompletableDeferred<String>()
        h.local.onTranscribe = { _, _ -> transcription.await() }
        assertTrue(h.coordinator.transcribe())
        h.await { it.progress == TranscriptionProgress.Transcribing(0f) }

        // Previously the late prepare success reset progress to Idle here, re-enabling the
        // button and allowing a second concurrent transcription.
        prepareGate.release.complete(Unit)
        prepareGate.finished.await()
        settle()
        assertEquals(TranscriptionProgress.Transcribing(0f), h.state.progress)
        assertFalse(h.coordinator.transcribe())

        transcription.complete("done")
        assertEquals("done", h.await { it.progress is TranscriptionProgress.Completed }.transcript)
    }

    @Test fun preparationFailureAndCancellationAreDistinguished() = coordinatorTest { h ->
        h.local.onPrepare = { Result.failure(IllegalStateException("NPU preparation failed")) }
        h.catalog.saved = MODEL_A.id
        h.coordinator.refreshModels().join()
        assertEquals(TranscriptionProgress.Failed("NPU preparation failed"), h.await { !it.busy }.progress)

        // A backend that wraps cancellation in a Result must not produce a failure message.
        h.local.onPrepare = { Result.failure(CancellationException("prepare cancelled")) }
        h.coordinator.selectModel(MODEL_B.id)
        assertTrue(h.await { !it.busy }.isCancelled)
    }

    @Test fun refreshKeepsANewerInMemorySelection() = coordinatorTest { h ->
        h.ready()
        h.catalog.selectGate = CompletableDeferred() // persistence of the new choice is slow
        assertTrue(h.coordinator.selectModel(MODEL_B.id))
        h.await { !it.busy }
        h.coordinator.refreshModels().join() // still reads the stale persisted "a"
        assertEquals(MODEL_B.id, h.state.selectedModelId)
        h.catalog.selectGate!!.complete(Unit)
        settle()
        assertEquals(listOf(MODEL_B.id), h.catalog.selections)
    }

    @Test fun selectionIsRejectedWhileTranscribing() = coordinatorTest { h ->
        h.ready()
        val release = CompletableDeferred<String>()
        h.local.onTranscribe = { _, _ -> release.await() }
        h.coordinator.transcribe()
        h.await { it.progress is TranscriptionProgress.Transcribing }

        assertFalse(h.coordinator.selectModel(MODEL_B.id))
        assertEquals(MODEL_A.id, h.state.selectedModelId)
        assertEquals(listOf(MODEL_A.id), h.local.prepared)
        release.complete("text")
        assertEquals("text", h.await { it.progress is TranscriptionProgress.Completed }.transcript)
    }

    // ---- Import -----------------------------------------------------------------------

    @Test fun cancelDuringImportIsCancelledAndTheLateResultIsNotApplied() = coordinatorTest { h ->
        h.ready()
        val imported = model("imported")
        val gate = Gate()
        h.catalog.onImport = { _, progress ->
            gate.nonCancellable {
                h.catalog.stored += imported // persisted just before cancellation took effect
                progress(1f)
                imported
            }
        }
        assertTrue(h.coordinator.importModel("content://models/imported"))
        gate.started.await()
        h.coordinator.cancel()
        assertTrue(h.state.isCancelled)

        gate.release.complete(Unit)
        gate.finished.await()
        settle()

        assertTrue(h.state.progress.toString(), h.state.isCancelled)
        assertEquals(MODEL_A.id, h.state.selectedModelId)
        assertTrue(h.catalog.selections.isEmpty())
        assertFalse(imported.id in h.local.prepared)
        // The in-memory list is reconciled with storage without touching selection/progress.
        assertTrue(h.state.models.any { it.id == imported.id })
    }

    @Test fun importFailureIsReportedAndCancellationIsNotMistakenForIt() = coordinatorTest { h ->
        h.ready()
        h.catalog.onImport = { _, _ -> throw IllegalArgumentException("Choose a .tflite model") }
        h.coordinator.importModel("content://models/bad")
        assertEquals(TranscriptionProgress.Failed("Choose a .tflite model"), h.await { !it.busy }.progress)

        h.catalog.onImport = { _, _ -> awaitCancellation() }
        h.coordinator.importModel("content://models/slow")
        h.coordinator.cancel()
        settle()
        assertTrue(h.state.progress.toString(), h.state.isCancelled)
    }

    @Test fun successfulImportSelectsPersistsAndPreparesTheModel() = coordinatorTest { h ->
        h.ready()
        val imported = model("imported")
        h.catalog.onImport = { _, progress ->
            progress(0.5f)
            h.catalog.stored += imported
            imported
        }
        assertTrue(h.coordinator.importModel("content://models/imported"))
        assertFalse("busy import must reject a second import", h.coordinator.importModel("content://x"))
        val state = h.await { !it.busy && it.selectedModelId == imported.id }
        assertEquals(TranscriptionProgress.Idle, state.progress)
        assertEquals(listOf(imported.id), h.catalog.selections)
        assertEquals(imported.id, h.local.prepared.last())
    }

    @Test fun disabledImportReportsItsNoteAndKeepsTheSelection() = coordinatorTest { h ->
        h.ready()
        val stateless = model("stateless", enabled = false, note = "Stateless export")
        h.catalog.onImport = { _, _ -> h.catalog.stored += stateless; stateless }
        h.coordinator.importModel("content://models/stateless")
        val state = h.await { !it.busy }
        assertEquals(TranscriptionProgress.Failed("Stateless export"), state.progress)
        assertEquals(MODEL_A.id, state.selectedModelId)
        assertTrue(state.models.any { it.id == stateless.id })
        settle()
        // The failure message is not replaced by a re-preparation of the old model.
        assertEquals(TranscriptionProgress.Failed("Stateless export"), h.state.progress)
        assertEquals(listOf(MODEL_A.id), h.local.prepared)
    }

    // ---- Lifecycle --------------------------------------------------------------------

    @Test fun disposeDiscardsLateWritesAndClosesEachBackendOnce() = coordinatorTest { h ->
        h.ready()
        val gate = Gate()
        h.local.onTranscribe = { _, progress -> gate.nonCancellable { progress(1f); "after dispose" } }
        h.coordinator.transcribe()
        gate.started.await()
        val before = h.state

        h.coordinator.dispose()
        h.coordinator.dispose() // idempotent
        gate.release.complete(Unit)
        gate.finished.await()
        settle()

        assertEquals(before, h.state)
        assertEquals(1, h.local.closeCount.get()) // registered under two keys, closed once
        assertEquals(1, h.cloud.closeCount.get())
        assertFalse(h.coordinator.transcribe())
        assertFalse(h.coordinator.importModel("content://late"))
        assertFalse(h.coordinator.selectModel(MODEL_B.id))
    }

    @Test fun cancellingTheOwningScopeStopsWorkLikeViewModelClearing() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + coroutineContext[ContinuationInterceptor]!!)
        val h = Harness(scope)
        h.ready()
        val sawCancellation = CompletableDeferred<Unit>()
        h.local.onTranscribe = { _, _ ->
            try { awaitCancellation() } finally { sawCancellation.complete(Unit) }
        }
        h.coordinator.transcribe()
        h.await { it.progress is TranscriptionProgress.Transcribing }
        scope.cancel() // viewModelScope is cancelled before onCleared()
        h.coordinator.dispose()
        withTimeout(1_000) { sawCancellation.await() }
        assertEquals(1, h.local.closeCount.get())
    }

    // ---- Real threads -----------------------------------------------------------------

    @Test fun progressFromWorkerThreadsCannotResurrectACancelledRun() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        try {
            val h = Harness(scope)
            h.ready()
            repeat(30) { iteration ->
                val staleDone = CompletableDeferred<Unit>()
                val staleRunning = CompletableDeferred<Unit>()
                // Without this the stale worker could finish (and legitimately complete)
                // before the test thread gets to cancel it.
                val cancelIssued = CompletableDeferred<Unit>()
                var call = 0
                h.local.onTranscribe = { _, progress ->
                    if (++call == 1) {
                        // Ignores cancellation and keeps reporting progress from another thread.
                        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable + kotlinx.coroutines.Dispatchers.IO) {
                            staleRunning.complete(Unit)
                            cancelIssued.await()
                            repeat(2_000) { progress(it / 2_000f) }
                            staleDone.complete(Unit)
                            "stale $iteration"
                        }
                    } else "fresh $iteration"
                }
                assertTrue(h.coordinator.transcribe())
                staleRunning.await()
                h.coordinator.cancel()
                cancelIssued.complete(Unit)
                assertTrue(h.state.isCancelled)
                assertTrue(h.coordinator.transcribe())
                val done = h.await { it.progress is TranscriptionProgress.Completed }
                assertEquals("fresh $iteration", done.transcript)
                staleDone.await()
                delay(2)
                assertEquals("fresh $iteration", h.state.transcript)
                assertTrue(h.state.progress is TranscriptionProgress.Completed)
            }
        } finally {
            scope.cancel()
        }
    }
}
