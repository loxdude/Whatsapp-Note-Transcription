package com.local.voicenotes.inference

import com.local.voicenotes.domain.ImportedModel
import com.local.voicenotes.domain.LanguageOption

interface TranscriptionBackend : AutoCloseable {
    suspend fun validate(model: ImportedModel): Result<Unit>
    suspend fun transcribe(
        model: ImportedModel,
        pcm16KhzMono: FloatArray,
        language: LanguageOption,
        onProgress: (Float) -> Unit
    ): String
    fun cancel()
}

