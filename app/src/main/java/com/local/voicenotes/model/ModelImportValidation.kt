package com.local.voicenotes.model

import java.io.InputStream
import java.io.DataInputStream
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** All permission/persistence side effects belong in [commit], after complete validation. */
internal suspend fun <T> validateThenImport(
    displayName: String,
    sourceLength: Long,
    openHeader: () -> InputStream,
    commit: suspend (ParakeetUltraModels.Evaluation) -> T
): T {
    require(sourceLength > 0) { "The selected model has no readable size." }
    require(displayName.endsWith(".tflite", ignoreCase = true)) {
        "Choose a Parakeet Ultra encoder (parakeet_ultra_encoder_30s_i8.tflite)."
    }
    ParakeetUltraModels.importError(displayName)?.let { throw IllegalArgumentException(it) }
    val header = openHeader().use { input ->
        ByteArray(8).also { bytes ->
            try { DataInputStream(input).readFully(bytes) }
            catch (error: java.io.EOFException) { throw IllegalArgumentException("The model header is truncated.", error) }
        }
    }
    require(header.copyOfRange(4, 8).contentEquals("TFL3".encodeToByteArray())) {
        "This is not a valid LiteRT/TFLite flatbuffer."
    }
    coroutineContext.ensureActive()
    return commit(ParakeetUltraModels.evaluate(displayName))
}
