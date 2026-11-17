package com.local.voicenotes.model

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/** A model file published at a fixed URL with a pinned size and SHA-256. */
data class DownloadSpec(val url: String, val fileName: String, val sizeBytes: Long, val sha256: String)

object ParakeetUltraDownload {
    /** CC-BY-4.0 export of litert-community/parakeet-ultra; pinned to the evaluated file. */
    val ENCODER_30S = DownloadSpec(
        url = "https://huggingface.co/litert-community/parakeet-ultra/resolve/main/parakeet_ultra_encoder_30s_i8.tflite",
        fileName = "parakeet_ultra_encoder_30s_i8.tflite",
        sizeBytes = 665_458_528L,
        sha256 = "706675fdf2a128c62072f2db02bb70dec5c4ca1c48298e808f0b58fff2a2e5cd"
    )

    /** Stable record id of the downloaded encoder (independent of the storage path). */
    const val MODEL_ID = "download:parakeet_ultra_encoder_30s_i8"
}

/** Progress of the one-tap model download, observed by UI and work service. */
sealed interface DownloadState {
    data object Idle : DownloadState
    data class Running(val downloadedBytes: Long, val totalBytes: Long) : DownloadState {
        val fraction: Float get() = if (totalBytes <= 0) 0f else (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
    }
    data object Verifying : DownloadState
    data class Failed(val message: String) : DownloadState
}

val DownloadState.active: Boolean get() = this is DownloadState.Running || this is DownloadState.Verifying

/** An opened HTTP body. [startOffset] is where the body begins (0 for a full 200 response). */
class RangeResponse(val body: InputStream, val startOffset: Long, val totalBytes: Long) : AutoCloseable {
    override fun close() = body.close()
}

/** Opens [url] at [offset]; servers may ignore the range and answer from byte 0. */
fun interface RangeSource {
    fun open(url: String, offset: Long): RangeResponse
}

/**
 * Downloads [spec] into [directory] resumably: bytes go to `<name>.partial`; an interrupted
 * download continues from its length (the partial bytes are re-hashed, so the final SHA-256
 * still covers the whole file). Only a file whose size and SHA-256 match the pin is renamed
 * to its final name; a mismatch deletes the partial file so a retry starts clean.
 *
 * Blocking; [checkCancelled] is called between buffers and must throw to stop.
 */
class ModelDownload(
    private val directory: File,
    private val spec: DownloadSpec,
    private val source: RangeSource,
    private val freeBytes: (File) -> Long = { it.usableSpace },
    private val bufferSize: Int = 256 * 1024
) {
    val target: File get() = File(directory, spec.fileName)
    private val partial: File get() = File(directory, spec.fileName + ".partial")

    /**
     * Cheap size check for the UI. [run] re-verifies the SHA-256 of an existing target before
     * returning it, so a file corrupted after its rename is never handed out as complete.
     */
    fun isComplete(): Boolean = target.isFile && target.length() == spec.sizeBytes

    fun run(
        checkCancelled: () -> Unit,
        onProgress: (downloaded: Long, total: Long) -> Unit,
        onVerifying: () -> Unit = {}
    ): File {
        if (target.isFile && target.length() == spec.sizeBytes) {
            onVerifying()
            if (matchesPin(target, checkCancelled)) return target
        }
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create the model folder.")
        target.delete()
        if (partial.isFile && partial.length() == spec.sizeBytes) {
            // The process died after the final sync but before the rename: verify instead of refetching.
            onVerifying()
            if (matchesPin(partial, checkCancelled)) {
                if (!partial.renameTo(target)) throw IOException("Cannot store the downloaded model.")
                return target
            }
            partial.delete()
        }
        var offset = partial.length().takeIf { partial.isFile && it < spec.sizeBytes } ?: 0L
        if (offset == 0L) partial.delete()

        val needed = spec.sizeBytes - offset
        val free = freeBytes(directory)
        if (free in 0 until needed + SPACE_MARGIN) {
            throw IOException("Not enough free storage: ${formatBytes(needed + SPACE_MARGIN)} needed, ${formatBytes(free)} free.")
        }

        val digest = MessageDigest.getInstance("SHA-256")
        source.open(spec.url, offset).use { response ->
            if (response.startOffset != offset) {
                // The server ignored the range: start over from byte 0.
                if (response.startOffset != 0L) throw IOException("Unexpected download offset ${response.startOffset}.")
                offset = 0L
                partial.delete()
            }
            if (response.totalBytes > 0 && response.totalBytes != spec.sizeBytes) {
                throw IOException("The server offered ${formatBytes(response.totalBytes)}, expected ${formatBytes(spec.sizeBytes)}.")
            }
            if (offset > 0) hashPrefix(digest, offset, checkCancelled)
            onProgress(offset, spec.sizeBytes)
            FileOutputStream(partial, offset > 0).use { output ->
                val buffer = ByteArray(bufferSize)
                var written = offset
                while (true) {
                    checkCancelled()
                    val read = response.body.read(buffer)
                    if (read < 0) break
                    if (written + read > spec.sizeBytes) {
                        partial.delete()
                        throw IOException("The download is larger than expected.")
                    }
                    output.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    written += read
                    onProgress(written, spec.sizeBytes)
                }
                output.fd.sync()
                if (written != spec.sizeBytes) throw IOException("The download ended early; tap Retry to resume.")
            }
        }
        onVerifying()
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (!actual.equals(spec.sha256, ignoreCase = true)) {
            partial.delete()
            throw IOException("The downloaded model failed its integrity check and was deleted.")
        }
        if (!partial.renameTo(target)) throw IOException("Cannot store the downloaded model.")
        return target
    }

    /** Deletes the finished file and any partial download. */
    fun delete() {
        target.delete()
        partial.delete()
    }

    private fun matchesPin(file: File, checkCancelled: () -> Unit): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        hashPrefix(digest, file.length(), checkCancelled, file)
        return digest.digest().joinToString("") { "%02x".format(it) }.equals(spec.sha256, ignoreCase = true)
    }

    private fun hashPrefix(digest: MessageDigest, length: Long, checkCancelled: () -> Unit, file: File = partial) {
        FileInputStream(file).use { input ->
            val buffer = ByteArray(bufferSize)
            var remaining = length
            while (remaining > 0) {
                checkCancelled()
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read < 0) throw IOException("The model file shrank while it was verified; tap Retry.")
                digest.update(buffer, 0, read)
                remaining -= read
            }
        }
    }

    companion object {
        /** Headroom so the download never fills the device completely. */
        const val SPACE_MARGIN = 200L * 1000 * 1000
    }
}

/** Decimal units, matching Android's Files/Settings apps. */
fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> String.format(java.util.Locale.ROOT, "%.1f GB", bytes / 1e9)
    bytes >= 1_000_000 -> "${bytes / 1_000_000} MB"
    bytes >= 1_000 -> "${bytes / 1_000} kB"
    else -> "$bytes B"
}
