package com.local.voicenotes.inference

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class HttpResult(val code: Int, val body: String?) {
    val isSuccessful: Boolean get() = code in 200..299
}

/**
 * Executes the call asynchronously and reads the whole body. Cancelling the calling
 * coroutine cancels the HTTP call immediately, including while the body is streamed,
 * so there is no window in which a cancelled transcription keeps uploading.
 */
internal suspend fun Call.awaitResult(): HttpResult = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            // Ignored by the continuation if it was already cancelled.
            continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            val result = runCatching { response.use { HttpResult(it.code, it.body?.string()) } }
            result.fold(continuation::resume, continuation::resumeWithException)
        }
    })
}
