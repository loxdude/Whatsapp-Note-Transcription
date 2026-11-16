package com.local.voicenotes.inference

import com.local.voicenotes.concurrent.copyCancellable
import java.io.File
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale

/**
 * Pure-JVM helpers for staging an audio file before it is uploaded to Mistral. They hold
 * no Android dependencies so the limit, cancellation and naming rules are unit-testable.
 */
internal object MistralUploadStaging {
    /**
     * Upper bound for a single upload. This is an app-side cap chosen to stay within the
     * size Mistral's transcription endpoint accepts (oversized requests are rejected with
     * HTTP 413 anyway); enforcing it locally avoids filling the cache directory and
     * uploading data that can only fail.
     */
    const val MAX_UPLOAD_BYTES: Long = 100L * 1024 * 1024

    /** Prefix of every staging file this backend writes into the cache directory. */
    const val STAGING_PREFIX = "mistral-"

    /**
     * Staging files modified before this instant cannot belong to the current process.
     * The margin covers file systems with coarse (1–2 s) modification-time granularity.
     */
    private val processEpochMillis: Long = System.currentTimeMillis() - 2_000

    @Volatile
    private var staleFilesCleaned = false

    fun tooLargeMessage(limitBytes: Long = MAX_UPLOAD_BYTES): String =
        "The selected audio file is larger than the ${limitBytes / (1024 * 1024)} MB Mistral upload limit."

    /** Rejects a file whose provider-reported size already exceeds [limitBytes]. */
    fun checkDeclaredSize(sizeBytes: Long?, limitBytes: Long = MAX_UPLOAD_BYTES) {
        if (sizeBytes != null && sizeBytes > limitBytes) throw UploadTooLargeException(limitBytes)
    }

    /**
     * Copies [input] to [output], calling [ensureActive] before every buffer and failing
     * with [UploadTooLargeException] as soon as more than [limitBytes] have been read.
     * The limit is enforced on the actual stream, so it also holds when the provider
     * reports no (or a wrong) size. Returns the number of bytes copied.
     */
    fun copyWithLimit(
        input: InputStream,
        output: OutputStream,
        limitBytes: Long = MAX_UPLOAD_BYTES,
        bufferSize: Int = 256 * 1024,
        ensureActive: () -> Unit
    ): Long {
        require(limitBytes >= 0) { "limitBytes must not be negative" }
        val limited = LimitedOutputStream(output, limitBytes)
        return copyCancellable(input, limited, bufferSize, ensureActive)
    }

    /**
     * Neutral multipart filename (`audio.<ext>`) so the user's original file name is not
     * sent to the cloud. The extension comes from the MIME type when known, otherwise
     * from a well-formed extension of [displayName]; without either it is plain `audio`.
     */
    fun uploadFileName(displayName: String?, mimeType: String?): String {
        val extension = mimeType?.let(::extensionForMime) ?: displayName?.let(::safeExtension)
        return if (extension != null) "audio.$extension" else "audio"
    }

    /** Suffix for a staging temp file; never contains user-controlled characters. */
    fun stagingSuffix(displayName: String?, mimeType: String?): String =
        uploadFileName(displayName, mimeType).substringAfter("audio").ifEmpty { ".audio" }

    internal fun safeExtension(fileName: String): String? {
        if (!fileName.contains('.')) return null
        return fileName.substringAfterLast('.')
            .takeIf { it.matches(Regex("[A-Za-z0-9]{1,10}")) }
            ?.lowercase(Locale.ROOT)
    }

    internal fun extensionForMime(mimeType: String): String? =
        when (mimeType.substringBefore(';').trim().lowercase(Locale.ROOT)) {
            "audio/mpeg", "audio/mp3", "audio/mpeg3", "audio/x-mpeg-3" -> "mp3"
            "audio/mp4", "audio/m4a", "audio/x-m4a", "audio/aac-adts" -> "m4a"
            "audio/aac", "audio/x-aac" -> "aac"
            "audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave" -> "wav"
            "audio/ogg", "application/ogg" -> "ogg"
            "audio/opus" -> "opus"
            "audio/flac", "audio/x-flac" -> "flac"
            "audio/webm" -> "webm"
            "video/webm" -> "webm"
            "audio/3gpp", "video/3gpp" -> "3gp"
            "audio/amr", "audio/amr-wb" -> "amr"
            "video/mp4" -> "mp4"
            else -> null
        }

    /**
     * Deletes `mistral-*` staging files left behind by a previous process (for example
     * after a crash or process death mid-upload). Files created by this process are newer
     * than [processEpochMillis] and are never touched. Runs at most once per process.
     */
    fun cleanStaleStagingFilesOnce(cacheDir: File) {
        if (staleFilesCleaned) return
        synchronized(this) {
            if (staleFilesCleaned) return
            staleFilesCleaned = true
        }
        runCatching { deleteStaleStagingFiles(cacheDir, processEpochMillis) }
    }

    /** Returns the number of deleted files. */
    internal fun deleteStaleStagingFiles(cacheDir: File, olderThanMillis: Long): Int {
        val stale = cacheDir.listFiles { file ->
            file.isFile && file.name.startsWith(STAGING_PREFIX) && file.lastModified() < olderThanMillis
        } ?: return 0
        return stale.count { it.delete() }
    }

    private class LimitedOutputStream(out: OutputStream, private val limit: Long) : FilterOutputStream(out) {
        private var written = 0L

        override fun write(b: Int) {
            reserve(1)
            out.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            reserve(len.toLong())
            out.write(b, off, len)
        }

        private fun reserve(count: Long) {
            if (written + count > limit) throw UploadTooLargeException(limit)
            written += count
        }
    }
}

/** The audio exceeds the upload limit; the message is suitable for the UI. */
class UploadTooLargeException(val limitBytes: Long) :
    IllegalStateException(MistralUploadStaging.tooLargeMessage(limitBytes))
