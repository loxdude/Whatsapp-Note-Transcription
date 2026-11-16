package com.local.voicenotes.work

import com.local.voicenotes.AppUiState
import com.local.voicenotes.domain.TranscriptionProgress
import kotlin.math.roundToInt

enum class WorkKind { IMPORT, TRANSCRIPTION }

/** Content of the ongoing foreground-service notification. [percent] null = indeterminate. */
data class OngoingNotice(val kind: WorkKind, val title: String, val text: String, val percent: Int?)

/** Notification posted once work ended while the user was away. */
data class FinishedNotice(val title: String, val text: String)

/**
 * Pure mapping from coordinator state to foreground-service behaviour, kept free of Android
 * types so it can be unit-tested.
 *
 * The service runs exactly while an import or a transcription is in progress. Model
 * preparation after a selection ([TranscriptionProgress.LoadingModel]) is not user-started
 * long work and does not keep the service alive.
 */
object WorkNotificationPolicy {
    const val PREVIEW_CHARS = 120

    fun shouldRun(state: AppUiState): Boolean = ongoing(state) != null

    /** Null when no foreground work is running, i.e. the service should stop. */
    fun ongoing(state: AppUiState): OngoingNotice? = when (val progress = state.progress) {
        is TranscriptionProgress.Importing ->
            OngoingNotice(WorkKind.IMPORT, "Importing model", "Copying and checking the model", percent(progress.fraction))
        TranscriptionProgress.Preparing ->
            OngoingNotice(WorkKind.TRANSCRIPTION, title(state), "Preparing", null)
        is TranscriptionProgress.Decoding ->
            OngoingNotice(WorkKind.TRANSCRIPTION, title(state), "Decoding audio", percent(progress.fraction))
        is TranscriptionProgress.Transcribing ->
            OngoingNotice(WorkKind.TRANSCRIPTION, title(state), "Transcribing", percent(progress.fraction))
        TranscriptionProgress.Idle, TranscriptionProgress.LoadingModel, TranscriptionProgress.Cancelled,
        is TranscriptionProgress.Completed, is TranscriptionProgress.Failed -> null
    }

    /**
     * What to tell the user once work of [lastKind] stopped and the state is [state].
     * Cancellation is the user's own action and gets no notice; neither does a successful
     * model import (the user is in the app's settings when starting it).
     */
    fun finished(lastKind: WorkKind?, state: AppUiState): FinishedNotice? = when (lastKind) {
        null -> null
        WorkKind.TRANSCRIPTION -> when (val progress = state.progress) {
            is TranscriptionProgress.Completed -> FinishedNotice(
                "Transcription finished",
                preview(state.transcript.ifBlank { progress.result.text }).ifBlank { "No speech was recognized." }
            )
            is TranscriptionProgress.Failed -> FinishedNotice("Transcription failed", progress.message)
            else -> null
        }
        WorkKind.IMPORT -> (state.progress as? TranscriptionProgress.Failed)
            ?.let { FinishedNotice("Model import failed", it.message) }
    }

    private fun title(state: AppUiState): String =
        if (state.audioName.isBlank()) "Transcribing voice note" else "Transcribing ${state.audioName}"

    /** Whole percent in 0..100; null (indeterminate) until there is measurable progress. */
    internal fun percent(fraction: Float): Int? {
        if (fraction.isNaN() || fraction <= 0f) return null
        return (fraction.coerceAtMost(1f) * 100f).roundToInt()
    }

    internal fun preview(text: String): String {
        val singleLine = text.trim().replace(Regex("\\s+"), " ")
        return if (singleLine.length <= PREVIEW_CHARS) singleLine
        else singleLine.take(PREVIEW_CHARS - 1).trimEnd() + "…"
    }
}
