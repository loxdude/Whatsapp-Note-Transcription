package com.local.voicenotes

import com.local.voicenotes.domain.TranscriptionProgress
import com.local.voicenotes.share.SharedAudioDecision
import com.local.voicenotes.share.decideSharedAudio
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

/**
 * Review finding: audio shared while a model import runs waits for the first non-WAIT
 * decision. The import used to publish a transient Idle before its follow-up preparation
 * published LoadingModel; on a Main.immediate-like dispatcher the share started a
 * transcription on that Idle, which the preparation then superseded (empty transcript,
 * nothing saved, prepare and transcribe on the backend concurrently).
 */
class ShareDuringImportRegressionTest {
    @Test fun shareWaitingOnImportIsTranscribedAfterThePreparationAndSaved() = runBlocking {
        val executor = Executors.newSingleThreadExecutor()
        val mainThread = executor.submit<Thread> { Thread.currentThread() }.get()
        // Like Dispatchers.Main.immediate: resumes inline when already on the main thread.
        val immediate = object : CoroutineDispatcher() {
            override fun isDispatchNeeded(context: CoroutineContext) = Thread.currentThread() !== mainThread
            override fun dispatch(context: CoroutineContext, block: Runnable) = executor.execute(block)
        }
        val scope = CoroutineScope(SupervisorJob() + immediate)
        try {
            val completed = CopyOnWriteArrayList<CompletedTranscript>()
            val h = Harness(scope, onTranscriptCompleted = { completed += it })
            withContext(immediate) { h.ready() }

            val preparing = AtomicBoolean()
            val overlapped = AtomicBoolean()
            h.local.onPrepare = {
                preparing.set(true)
                delay(50)
                preparing.set(false)
                Result.success(Unit)
            }
            h.local.onTranscribe = { _, _ ->
                if (preparing.get()) overlapped.set(true)
                "local text"
            }
            h.onDecode = { _, _ -> delay(20); FloatArray(16) }
            val gate = CompletableDeferred<Unit>()
            val imported = model("c")
            h.catalog.onImport = { _, _ -> gate.await(); h.catalog.stored += imported; imported }

            withContext(immediate) {
                check(h.coordinator.importModel("content://m"))
                h.coordinator.setAudio("content://shared", "shared.ogg")
            }
            val trace = CopyOnWriteArrayList<TranscriptionProgress>()
            val watcher = scope.launch { h.coordinator.state.collect { trace += it.progress } }
            val decision = CompletableDeferred<SharedAudioDecision>()
            val started = CompletableDeferred<Boolean>()
            // Mirrors AppViewModel.onSharedAudio.
            scope.launch {
                val first = h.coordinator.state.map { decideSharedAudio(it, "content://shared", false) }
                    .first { it != SharedAudioDecision.WAIT }
                decision.complete(first)
                started.complete(first == SharedAudioDecision.TRANSCRIBE && h.coordinator.transcribe())
            }
            gate.complete(Unit)

            assertEquals(SharedAudioDecision.TRANSCRIBE, withTimeout(5_000) { decision.await() })
            assertTrue(withTimeout(5_000) { started.await() })
            val done = h.await { it.progress is TranscriptionProgress.Completed || it.progress is TranscriptionProgress.Failed }
            watcher.cancel()

            assertTrue("prepared the imported model", "c" in h.local.prepared)
            assertEquals("local text", done.transcript)
            assertEquals(listOf("local text"), completed.map { it.text })
            assertEquals("c", completed.single().modelId)
            assertEquals(1, h.local.transcribeCalls.get())
            assertFalse("prepare and transcribe ran concurrently", overlapped.get())
            // No Idle between the end of the import and the preparation.
            val afterImport = trace.dropWhile { it !is TranscriptionProgress.Importing }
                .dropWhile { it is TranscriptionProgress.Importing }
            assertEquals(TranscriptionProgress.LoadingModel, afterImport.first())
        } finally {
            scope.cancel()
            executor.shutdownNow()
        }
    }
}
