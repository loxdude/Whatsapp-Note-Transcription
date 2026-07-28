package com.local.voicenotes.inference

import android.net.Uri
import com.local.voicenotes.domain.ImportedModel
import com.local.voicenotes.domain.LanguageOption

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
    suspend fun transcribeUri(
        model: ImportedModel,
        uri: Uri,
        language: LanguageOption,
        onProgress: (Float) -> Unit
    ): String
}
