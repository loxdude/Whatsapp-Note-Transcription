package com.local.voicenotes.inference

import kotlinx.coroutines.ensureActive
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext

internal data class MistralStagedAudio(
    val file: File,
    /** Neutral multipart name; never the user's original file name. */
    val fileName: String,
    val mimeType: String,
    val temporary: Boolean
)

/**
 * Android-free request/staging boundary shared by the URI and PCM entry points.
 * One request per backend; overlap is rejected rather than replacing its cancel marker.
 * A stage function owns partial-file cleanup until it successfully returns its source.
 */
internal class MistralTranscriptionRequests {
    private val active = AtomicReference<Request?>()

    internal class Request(private val checkJob: () -> Unit) {
        private val cancelled = AtomicBoolean(false)
        @Volatile private var cancelCall: (() -> Unit)? = null

        fun ensureActive() {
            checkJob()
            if (cancelled.get()) throw CancellationException("Transcription cancelled")
        }

        fun attachCall(cancel: () -> Unit) {
            cancelCall = cancel
            if (cancelled.get()) cancel()
        }

        fun cancel() {
            cancelled.set(true)
            cancelCall?.invoke()
        }
    }

    fun cancel() { active.get()?.cancel() }

    suspend fun <T> run(
        readKey: () -> String,
        stage: (ensureActive: () -> Unit) -> MistralStagedAudio,
        send: suspend (String, MistralStagedAudio, Request) -> T
    ): T {
        val jobContext = coroutineContext
        val request = Request { jobContext.ensureActive() }
        // Publish before the potentially uninterruptible Keystore read, never after it.
        check(active.compareAndSet(null, request)) { "A transcription is already active." }
        var source: MistralStagedAudio? = null
        try {
            request.ensureActive()
            val key = readKey()
            request.ensureActive()
            val staged = stage(request::ensureActive)
            source = staged
            request.ensureActive()
            return send(key, staged, request)
        } finally {
            try {
                source?.takeIf { it.temporary }?.file?.delete()
            } finally {
                active.compareAndSet(request, null)
            }
        }
    }
}
