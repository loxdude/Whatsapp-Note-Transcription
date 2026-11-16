package com.local.voicenotes

import com.local.voicenotes.domain.TranscriptionProgress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.ContinuationInterceptor

/** Completion/edit hooks that the app uses to persist the transcript history. */
class CoordinatorHistoryHooksTest {
    private val completed = CopyOnWriteArrayList<CompletedTranscript>()
    private val edits = CopyOnWriteArrayList<Pair<String, String>>()

    private fun hooksTest(
        onCompleted: (CompletedTranscript) -> Unit = { completed += it },
        block: suspend CoroutineScope.(Harness) -> Unit
    ) = runBlocking {
        val uncaught = CopyOnWriteArrayList<Throwable>()
        val scope = CoroutineScope(
            SupervisorJob() + coroutineContext[ContinuationInterceptor]!! +
                CoroutineExceptionHandler { _, error -> uncaught += error }
        )
        try {
            withTimeout(10_000) {
                block(Harness(scope, onCompleted) { id, text -> edits += id to text })
            }
            settle()
        } finally {
            scope.cancel()
        }
        assertTrue("Uncaught coroutine failures: $uncaught", uncaught.isEmpty())
    }

    @Test fun localCompletionIsReportedOnceWithMetadata() = hooksTest { h ->
        h.ready()
        h.onDecode = { _, _ -> FloatArray(32_000) } // two seconds at 16 kHz
        h.now.set(5_000)
        h.local.onTranscribe = { _, _ -> h.now.set(5_750); "  hello world  " }
        assertTrue(h.coordinator.transcribe())
        val done = h.await { it.progress is TranscriptionProgress.Completed }
        settle()

        assertEquals("t1", done.transcriptId)
        assertEquals(1, completed.size)
        val entry = completed.single()
        assertEquals("t1", entry.id)
        assertEquals("voice.ogg", entry.audioName)
        assertEquals("file:///voice.ogg", entry.audioUri)
        assertEquals(MODEL_A.id, entry.modelId)
        assertEquals(MODEL_A.displayName, entry.modelName)
        assertEquals("hello world", entry.text)
        assertEquals(2_000L, entry.audioDurationMillis)
        assertEquals(750L, entry.processingMillis)
    }

    @Test fun cloudCompletionHasNoAudioDuration() = hooksTest { h ->
        h.ready(MODEL_CLOUD.id)
        assertTrue(h.coordinator.transcribe())
        h.await { it.progress is TranscriptionProgress.Completed }
        settle()
        assertEquals("cloud text", completed.single().text)
        assertNull(completed.single().audioDurationMillis)
    }

    @Test fun cancelledRunIsNotReported() = hooksTest { h ->
        h.ready()
        val running = CompletableDeferred<Unit>()
        h.local.onTranscribe = { _, _ -> running.complete(Unit); awaitCancellation() }
        assertTrue(h.coordinator.transcribe())
        running.await()
        h.coordinator.cancel()
        h.await { it.progress == TranscriptionProgress.Cancelled }
        settle()
        assertTrue(completed.isEmpty())
        assertNull(h.state.transcriptId)
    }

    @Test fun lateResultOfSupersededRunIsNotReported() = hooksTest { h ->
        h.ready()
        val gate = Gate()
        h.local.onTranscribe = { _, _ -> gate.nonCancellable { "stale" } }
        assertTrue(h.coordinator.transcribe())
        gate.started.await()
        h.coordinator.setAudio("file:///other.ogg", "other.ogg")
        gate.release.complete(Unit)
        gate.finished.await()
        settle()
        assertTrue(completed.isEmpty())
        assertNull(h.state.transcriptId)
        assertEquals("", h.state.transcript)
    }

    @Test fun editsAreForwardedOnlyForTheShownCompletedTranscript() = hooksTest { h ->
        h.ready()
        h.coordinator.editTranscript("typed before any result")
        assertTrue(edits.isEmpty())

        assertTrue(h.coordinator.transcribe())
        h.await { it.progress is TranscriptionProgress.Completed }
        h.coordinator.editTranscript("fixed text")
        assertEquals(listOf("t1" to "fixed text"), edits.toList())
        assertEquals("fixed text", h.state.transcript)

        // New audio detaches the transcript from the history entry.
        h.coordinator.setAudio("file:///next.ogg", "next.ogg")
        assertNull(h.state.transcriptId)
        h.coordinator.editTranscript("notes for the next file")
        assertEquals(1, edits.size)

        // A new run gets a new id; its edits go to the new entry.
        assertTrue(h.coordinator.transcribe())
        assertNull(h.state.transcriptId)
        h.await { it.progress is TranscriptionProgress.Completed }
        h.coordinator.editTranscript("second")
        assertEquals("t2" to "second", edits.last())
    }

    @Test fun failingCompletionHookDoesNotBreakTheResult() = hooksTest(onCompleted = { error("disk full") }) { h ->
        h.ready()
        assertTrue(h.coordinator.transcribe())
        val done = h.await { it.progress is TranscriptionProgress.Completed }
        settle()
        assertEquals("local text", h.state.transcript)
        assertEquals("t1", done.transcriptId)
        assertTrue(h.coordinator.transcribe())
    }
}
