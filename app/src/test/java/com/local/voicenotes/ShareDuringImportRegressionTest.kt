package com.local.voicenotes

import com.local.voicenotes.domain.TranscriptionProgress
import com.local.voicenotes.share.SharedAutoStart
import com.local.voicenotes.share.SharedWorkController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

/**
 * Import must publish the model and LoadingModel atomically: an intermediate Idle could
 * start a waiting share whose transcription the following preparation would then discard.
 * Exercise the production shared start path, not an overspecified StateFlow event trace.
 */
class ShareDuringImportRegressionTest {
    @Test fun shareWaitingOnImportIsTranscribedAfterThePreparationAndSaved() = runBlocking {
        val executor = Executors.newSingleThreadExecutor()
        val mainThread = executor.submit<Thread> { Thread.currentThread() }.get()
        val immediate = object : CoroutineDispatcher() {
            override fun isDispatchNeeded(context: CoroutineContext) = Thread.currentThread() !== mainThread
            override fun dispatch(context: CoroutineContext, block: Runnable) = executor.execute(block)
        }
        val scope = CoroutineScope(SupervisorJob() + immediate)
        val releaseImport = CompletableDeferred<Unit>()
        val releasePrepare = CompletableDeferred<Unit>()
        val releaseTranscribe = CompletableDeferred<Unit>()
        try {
            val completed = CopyOnWriteArrayList<CompletedTranscript>()
            val h = Harness(scope, onTranscriptCompleted = { completed += it })
            withContext(immediate) { h.ready() }

            val preparing = AtomicBoolean()
            val overlapped = AtomicBoolean()
            val prepareEntered = CompletableDeferred<Unit>()
            val transcribeEntered = CompletableDeferred<Unit>()
            h.local.onPrepare = {
                preparing.set(true)
                prepareEntered.complete(Unit)
                releasePrepare.await()
                preparing.set(false)
                Result.success(Unit)
            }
            h.local.onTranscribe = { _, _ ->
                if (preparing.get()) overlapped.set(true)
                transcribeEntered.complete(Unit)
                releaseTranscribe.await()
                "local text"
            }
            val imported = model("c")
            h.catalog.onImport = { _, _ ->
                releaseImport.await()
                h.catalog.stored += imported
                imported
            }
            val reservations = AtomicInteger()
            val releases = AtomicInteger()
            val handoffBusy = AtomicBoolean()
            val work = SharedWorkController(
                scope, h.coordinator,
                loadAudioName = { _, _ -> "shared.ogg" },
                awaitInitialRefresh = {},
                cloudConsent = { false },
                reserveService = {
                    reservations.incrementAndGet()
                    val release: () -> Unit = {
                        handoffBusy.set(h.state.busy)
                        reservations.decrementAndGet()
                        releases.incrementAndGet()
                    }
                    release
                },
                startService = { error("share should use the already reserved foreground service") },
                autoStart = { SharedAutoStart.START_IN_RUNNING_SERVICE }
            )
            val share = withContext(immediate) {
                check(h.coordinator.importModel("content://m"))
                // Main.immediate starts the state collector inline, before the import is released.
                work.onSharedAudio("content://shared")
            }
            assertEquals(1, reservations.get())
            releaseImport.complete(Unit)
            withTimeout(5_000) { prepareEntered.await() }
            assertEquals(0, h.local.transcribeCalls.get())
            assertEquals(1, reservations.get())
            releasePrepare.complete(Unit)
            withTimeout(5_000) { transcribeEntered.await(); share.join() }
            assertEquals(0, reservations.get())
            assertEquals(1, releases.get())
            assertTrue("service reservation carries over into running transcription", handoffBusy.get())
            releaseTranscribe.complete(Unit)
            val done = h.await {
                it.progress is TranscriptionProgress.Completed || it.progress is TranscriptionProgress.Failed
            }
            // The completion callback follows the state write; drain the main dispatcher before reading it.
            withContext(immediate) { Unit }
            assertTrue("prepared the imported model", "c" in h.local.prepared)
            assertEquals("local text", done.transcript)
            assertEquals(listOf("local text"), completed.map { it.text })
            assertEquals("c", completed.single().modelId)
            assertEquals("content://shared", completed.single().audioUri)
            assertEquals(1, h.local.transcribeCalls.get())
            assertFalse("prepare and transcribe ran concurrently", overlapped.get())
        } finally {
            releaseImport.complete(Unit)
            releasePrepare.complete(Unit)
            releaseTranscribe.complete(Unit)
            scope.cancel()
            executor.shutdownNow()
        }
    }
}
