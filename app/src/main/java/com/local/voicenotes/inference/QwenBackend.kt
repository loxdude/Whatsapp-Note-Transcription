package com.local.voicenotes.inference

import com.local.voicenotes.domain.ImportedModel
import com.local.voicenotes.domain.LanguageOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class QwenBackend : TranscriptionBackend {
    @Volatile private var cancelled = false

    override suspend fun validate(model: ImportedModel): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(File(model.path).isFile) { "The imported model file is missing." }
            val backend = NativeQwenBridge.detectBackend(model.path)
            require(backend == "qwen3") {
                "This GGUF is '$backend', not a self-contained CrispASR Qwen3-ASR model."
            }
        }
    }

    override suspend fun transcribe(
        model: ImportedModel,
        pcm16KhzMono: FloatArray,
        language: LanguageOption,
        onProgress: (Float) -> Unit
    ): String = coroutineScope {
        cancelled = false
        val poller = launch(Dispatchers.Default) {
            while (isActive) {
                val raw = runCatching { NativeQwenBridge.progressNative() }.getOrDefault(0)
                onProgress((raw.coerceIn(0, 100) / 100f))
                delay(250)
            }
        }
        try {
            withContext(Dispatchers.Default) {
                val cores = Runtime.getRuntime().availableProcessors()
                val threads = (cores - 2).coerceIn(2, 6)
                NativeQwenBridge.transcribeNative(model.path, pcm16KhzMono, language.code, threads)
            }.also {
                if (cancelled) throw kotlinx.coroutines.CancellationException("Cancelled")
                onProgress(1f)
            }
        } finally {
            poller.cancel()
        }
    }

    override fun cancel() {
        cancelled = true
        NativeQwenBridge.cancelNative()
    }

    override fun close() = NativeQwenBridge.releaseNative()
}
