package com.local.voicenotes.work

import com.local.voicenotes.AppUiState
import com.local.voicenotes.domain.TranscriptionProgress
import com.local.voicenotes.domain.TranscriptionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkNotificationPolicyTest {
    private fun state(progress: TranscriptionProgress, transcript: String = "", audioName: String = "PTT-1.opus") =
        AppUiState(progress = progress, transcript = transcript, audioName = audioName)

    private fun completed(text: String) =
        TranscriptionProgress.Completed(TranscriptionResult("auto", text, 10, "m"))

    @Test fun serviceRunsOnlyForImportAndTranscription() {
        assertTrue(WorkNotificationPolicy.shouldRun(state(TranscriptionProgress.Importing(0.1f))))
        assertTrue(WorkNotificationPolicy.shouldRun(state(TranscriptionProgress.Preparing)))
        assertTrue(WorkNotificationPolicy.shouldRun(state(TranscriptionProgress.Decoding(0.5f))))
        assertTrue(WorkNotificationPolicy.shouldRun(state(TranscriptionProgress.Transcribing(0f))))

        assertFalse(WorkNotificationPolicy.shouldRun(state(TranscriptionProgress.Idle)))
        // Preparing a model after selection is not user-started background work.
        assertFalse(WorkNotificationPolicy.shouldRun(state(TranscriptionProgress.LoadingModel)))
        assertFalse(WorkNotificationPolicy.shouldRun(state(TranscriptionProgress.Cancelled)))
        assertFalse(WorkNotificationPolicy.shouldRun(state(TranscriptionProgress.Failed("x"))))
        assertFalse(WorkNotificationPolicy.shouldRun(state(completed("hi"))))
    }

    @Test fun ongoingTextAndProgress() {
        assertEquals(
            OngoingNotice(WorkKind.TRANSCRIPTION, "Transcribing PTT-1.opus", "Decoding audio", 43),
            WorkNotificationPolicy.ongoing(state(TranscriptionProgress.Decoding(0.426f)))
        )
        assertEquals(
            OngoingNotice(WorkKind.TRANSCRIPTION, "Transcribing PTT-1.opus", "Transcribing", null),
            WorkNotificationPolicy.ongoing(state(TranscriptionProgress.Transcribing(0f)))
        )
        assertEquals(
            OngoingNotice(WorkKind.TRANSCRIPTION, "Transcribing voice note", "Preparing", null),
            WorkNotificationPolicy.ongoing(state(TranscriptionProgress.Preparing, audioName = ""))
        )
        val import = WorkNotificationPolicy.ongoing(state(TranscriptionProgress.Importing(1.5f)))!!
        assertEquals(WorkKind.IMPORT, import.kind)
        assertEquals(100, import.percent)
    }

    @Test fun percentIsIndeterminateWithoutProgress() {
        assertNull(WorkNotificationPolicy.percent(0f))
        assertNull(WorkNotificationPolicy.percent(Float.NaN))
        assertEquals(1, WorkNotificationPolicy.percent(0.005f))
        assertEquals(100, WorkNotificationPolicy.percent(1f))
    }

    @Test fun finishedTranscriptionShowsAPreview() {
        val long = "word ".repeat(60)
        val notice = WorkNotificationPolicy.finished(WorkKind.TRANSCRIPTION, state(completed(long), transcript = long))!!
        assertEquals("Transcription finished", notice.title)
        assertTrue(notice.text.length <= WorkNotificationPolicy.PREVIEW_CHARS)
        assertTrue(notice.text.endsWith("…"))

        val blank = WorkNotificationPolicy.finished(WorkKind.TRANSCRIPTION, state(completed("")))!!
        assertEquals("No speech was recognized.", blank.text)

        val multiLine = WorkNotificationPolicy.finished(WorkKind.TRANSCRIPTION, state(completed("a\n\n b"), "a\n\n b"))!!
        assertEquals("a b", multiLine.text)
    }

    @Test fun failuresAreReportedCancellationIsNot() {
        assertEquals(
            FinishedNotice("Transcription failed", "No audio track"),
            WorkNotificationPolicy.finished(WorkKind.TRANSCRIPTION, state(TranscriptionProgress.Failed("No audio track")))
        )
        assertEquals(
            FinishedNotice("Model import failed", "Bad header"),
            WorkNotificationPolicy.finished(WorkKind.IMPORT, state(TranscriptionProgress.Failed("Bad header")))
        )
        assertNull(WorkNotificationPolicy.finished(WorkKind.TRANSCRIPTION, state(TranscriptionProgress.Cancelled)))
        assertNull(WorkNotificationPolicy.finished(WorkKind.IMPORT, state(TranscriptionProgress.Idle)))
        // Nothing was observed running (e.g. work finished before the service started).
        assertNull(WorkNotificationPolicy.finished(null, state(completed("hi"))))
    }
}
