package com.local.voicenotes.inference

import com.local.voicenotes.domain.ImportedModel
import com.local.voicenotes.domain.LanguageOption

/**
 * Cancellation contract: implementations must honour cancellation of the calling coroutine
 * (throwing [kotlinx.coroutines.CancellationException]) and must never wrap cancellation in a
 * failed [Result]. [cancel] aborts the call that is currently running, if any; [close] releases
 * native resources but leaves the backend usable for a later [prepare].
 */
interface TranscriptionBackend : AutoCloseable {
    suspend fun validate(model: ImportedModel): Result<Unit>
    suspend fun prepare(model: ImportedModel): Result<Unit> = validate(model)
    suspend fun transcribe(
        model: ImportedModel,
        pcm16KhzMono: FloatArray,
        language: LanguageOption,
        onProgress: (Float) -> Unit
    ): String
    fun cancel()
}

/** A backend that uploads the selected source file without locally decoding it. */
interface UriTranscriptionBackend : TranscriptionBackend {
    /** [uri] is the content:// or file:// URI string of the selected audio. */
    suspend fun transcribeUri(
        model: ImportedModel,
        uri: String,
        language: LanguageOption,
        onProgress: (Float) -> Unit
    ): String
}
