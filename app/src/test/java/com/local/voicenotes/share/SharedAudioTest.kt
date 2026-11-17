package com.local.voicenotes.share

import com.local.voicenotes.AppUiState
import com.local.voicenotes.domain.ImportedModel
import com.local.voicenotes.domain.TranscriptionProgress
import com.local.voicenotes.domain.TranscriptionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedAudioTest {
    private val uri = "content://com.whatsapp.provider.media/item/1"
    private val local = ImportedModel("local", "Parakeet", "content://m", 1, "parakeet-ultra", "litert-ultra-cpu", enabled = true)
    private val disabled = local.copy(id = "off", enabled = false)
    private val cloud = ImportedModel("mistral", "Mistral", "", 0, "voxtral", "mistral-api", enabled = true, isApiModel = true)

    private fun state(
        selected: ImportedModel? = local,
        progress: TranscriptionProgress = TranscriptionProgress.Idle,
        audio: String? = uri,
        transcript: String = ""
    ) = AppUiState(
        models = listOfNotNull(local, disabled, cloud), selectedModelId = selected?.id,
        audioUri = audio, audioName = "PTT.opus", progress = progress, transcript = transcript
    )

    private fun decide(state: AppUiState) = decideSharedAudio(state, uri)

    @Test fun transcribesWithAUsableLocalModel() {
        assertEquals(SharedAudioDecision.TRANSCRIBE, decide(state()))
    }

    @Test fun waitsForModelPreparationOrImport() {
        assertEquals(SharedAudioDecision.WAIT, decide(state(progress = TranscriptionProgress.LoadingModel)))
        assertEquals(SharedAudioDecision.WAIT, decide(state(progress = TranscriptionProgress.Importing(0.5f))))
    }

    @Test fun onlyLoadsAudioWithoutAUsableModel() {
        assertEquals(SharedAudioDecision.NO_USABLE_MODEL, decide(state(selected = null)))
        assertEquals(SharedAudioDecision.NO_USABLE_MODEL, decide(state(selected = disabled)))
    }

    @Test fun cloudModelIsNeverTranscribedAutomatically() {
        assertEquals(SharedAudioDecision.CLOUD_NEEDS_TAP, decide(state(selected = cloud)))
    }

    @Test fun abortsWhenAudioChangedOrUserAlreadyActed() {
        assertEquals(SharedAudioDecision.ABORT, decide(state(audio = "content://other")))
        assertEquals(SharedAudioDecision.ABORT, decide(state(audio = null)))
        assertEquals(SharedAudioDecision.ABORT, decide(state(progress = TranscriptionProgress.Preparing)))
        assertEquals(SharedAudioDecision.ABORT, decide(state(progress = TranscriptionProgress.Transcribing(0.3f))))
        assertEquals(SharedAudioDecision.ABORT, decide(state(progress = TranscriptionProgress.Cancelled)))
        assertEquals(SharedAudioDecision.ABORT, decide(state(progress = TranscriptionProgress.Failed("prep failed"))))
        assertEquals(
            SharedAudioDecision.ABORT,
            decide(state(progress = TranscriptionProgress.Completed(TranscriptionResult("auto", "t", 1, "local"))))
        )
        assertEquals(SharedAudioDecision.ABORT, decide(state(transcript = "already here")))
    }

    @Test fun acceptsWhatsAppAndOggTypes() {
        assertTrue(isShareableAudioType("audio/ogg"))
        assertTrue(isShareableAudioType("audio/opus"))
        assertTrue(isShareableAudioType("Audio/MPEG"))
        assertTrue(isShareableAudioType("application/ogg"))
        assertTrue(isShareableAudioType(null))
        assertFalse(isShareableAudioType("image/jpeg"))
        assertFalse(isShareableAudioType("text/plain"))
    }

    @Test fun onlyContentUrisAreAcceptedFromShares() {
        assertTrue(isAcceptedShareScheme("content"))
        assertTrue(isAcceptedShareScheme("CONTENT"))
        assertFalse(isAcceptedShareScheme("file"))
        assertFalse(isAcceptedShareScheme("http"))
        assertFalse(isAcceptedShareScheme(null))
    }

    @Test fun autoStartOnlyFromTheForegroundOrInsideTheReservedService() {
        assertEquals(SharedAutoStart.START, sharedAutoStart(appVisible = true, serviceInForeground = false))
        assertEquals(SharedAutoStart.START, sharedAutoStart(appVisible = true, serviceInForeground = true))
        assertEquals(SharedAutoStart.START_IN_RUNNING_SERVICE, sharedAutoStart(appVisible = false, serviceInForeground = true))
        assertEquals(SharedAutoStart.HINT_ONLY, sharedAutoStart(appVisible = false, serviceInForeground = false))
    }
}
