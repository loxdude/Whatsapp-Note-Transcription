package com.local.voicenotes.share

import com.local.voicenotes.AppUiState
import com.local.voicenotes.domain.TranscriptionProgress
import com.local.voicenotes.isCloud

/** What to do with audio that another app shared to us, given the current state. */
enum class SharedAudioDecision {
    /** A model import or preparation is still running; decide again on the next state. */
    WAIT,
    /** The audio was replaced, or the user already started/finished a run for it. */
    ABORT,
    /** Keep the audio loaded; the main screen shows the no-model hint. */
    NO_USABLE_MODEL,
    /** A cloud model is selected and the user has not agreed to uploads: ask first. */
    NEEDS_CONSENT,
    TRANSCRIBE
}

/**
 * Only content:// URIs are accepted from ACTION_SEND: they come with a per-URI read grant.
 * file:// (and any other scheme) would make the app read paths with its own permissions on
 * behalf of the sender (e.g. its private files), so such shares are rejected.
 */
fun isAcceptedShareScheme(scheme: String?): Boolean = scheme.equals("content", ignoreCase = true)

/** How an automatic transcription of shared audio may begin. */
enum class SharedAutoStart {
    /** The app is visible: start the transcription and its foreground service normally. */
    START,
    /** In the background, but the service reserved for the share runs in the foreground. */
    START_IN_RUNNING_SERVICE,
    /** In the background without a running service: a start would be refused; only hint. */
    HINT_ONLY
}

fun sharedAutoStart(appVisible: Boolean, serviceInForeground: Boolean): SharedAutoStart = when {
    appVisible -> SharedAutoStart.START
    serviceInForeground -> SharedAutoStart.START_IN_RUNNING_SERVICE
    else -> SharedAutoStart.HINT_ONLY
}

/** MIME types accepted from ACTION_SEND; mirrors the manifest intent filters. */
fun isShareableAudioType(mimeType: String?): Boolean {
    val type = mimeType?.lowercase() ?: return true // let the decoder decide
    return type.startsWith("audio/") || type == "application/ogg"
}

/**
 * Decides whether shared audio should be transcribed automatically. Must only be called once
 * the model list was loaded (otherwise "no model" is reported prematurely). Never returns
 * [SharedAudioDecision.TRANSCRIBE] for a cloud model without [cloudConsent].
 */
fun decideSharedAudio(state: AppUiState, sharedUri: String, cloudConsent: Boolean): SharedAudioDecision {
    if (state.audioUri != sharedUri) return SharedAudioDecision.ABORT
    when (state.progress) {
        TranscriptionProgress.Preparing, is TranscriptionProgress.Decoding,
        is TranscriptionProgress.Transcribing, is TranscriptionProgress.Completed -> return SharedAudioDecision.ABORT
        // setAudio() resets a terminal state to Idle, so these mean the user cancelled, or
        // an import/preparation failed, after the share: keep that outcome visible.
        TranscriptionProgress.Cancelled, is TranscriptionProgress.Failed -> return SharedAudioDecision.ABORT
        else -> Unit
    }
    if (state.transcript.isNotBlank()) return SharedAudioDecision.ABORT
    if (state.busy) return SharedAudioDecision.WAIT
    val model = state.selectedModel
    if (model == null || !model.enabled) return SharedAudioDecision.NO_USABLE_MODEL
    if (model.isCloud() && !cloudConsent) return SharedAudioDecision.NEEDS_CONSENT
    return SharedAudioDecision.TRANSCRIBE
}
