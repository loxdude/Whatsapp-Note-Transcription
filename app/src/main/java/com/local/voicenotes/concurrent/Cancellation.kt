package com.local.voicenotes.concurrent

import java.io.InputStream
import java.io.OutputStream
import kotlin.coroutines.cancellation.CancellationException

/**
 * Like [runCatching], but never converts coroutine cancellation into a [Result.failure].
 * Swallowing [CancellationException] would make a cancelled operation look like an
 * ordinary error (and break structured cancellation of the caller).
 */
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        Result.failure(error)
    }

/**
 * Copies [input] to [output] while calling [ensureActive] before every buffer so a
 * long blocking copy (model caching, audio staging) stops promptly after cancellation.
 * Returns the number of bytes copied.
 */
fun copyCancellable(
    input: InputStream,
    output: OutputStream,
    bufferSize: Int = 1024 * 1024,
    ensureActive: () -> Unit
): Long {
    require(bufferSize > 0) { "bufferSize must be positive" }
    val buffer = ByteArray(bufferSize)
    var total = 0L
    while (true) {
        ensureActive()
        val read = input.read(buffer)
        if (read < 0) break
        output.write(buffer, 0, read)
        total += read
    }
    return total
}
