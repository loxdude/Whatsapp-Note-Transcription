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

class ParakeetBackend : TranscriptionBackend {
    @Volatile private var cancelled = false

    override suspend fun validate(model: ImportedModel): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(File(model.path).isFile) { "The imported model file is missing." }
            require(NativeParakeetBridge.isSupportedModel(model.path)) {
                "Import an official whisper.cpp Parakeet ggml .bin model."
            }
        }
    }

    suspend fun preload(model: ImportedModel): Result<Unit> = withContext(Dispatchers.Default) {
        runCatching {
            validate(model).getOrThrow()
            NativeParakeetBridge.preloadNative(model.path)
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
                onProgress(NativeParakeetBridge.progressNative().coerceIn(0, 100) / 100f)
                delay(150)
            }
        }
        try {
            withContext(Dispatchers.Default) {
                // Use every schedulable core for the explicit high-performance mode requested
                // during benchmarking. The native bridge caps this at the model runtime's 8-core limit.
                val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
                NativeParakeetBridge.transcribeNative(model.path, pcm16KhzMono, threads)
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
        NativeParakeetBridge.cancelNative()
    }

    override fun close() = NativeParakeetBridge.releaseNative()
}
