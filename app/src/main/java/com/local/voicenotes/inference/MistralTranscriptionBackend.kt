package com.local.voicenotes.inference

import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.local.voicenotes.concurrent.runCatchingCancellable
import com.local.voicenotes.domain.ImportedModel
import com.local.voicenotes.domain.LanguageOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URLConnection
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext

class MistralTranscriptionBackend(private val context: Context) : UriTranscriptionBackend {
    companion object {
        private const val TAG = "MistralTranscription"
        private const val API_URL = "https://api.mistral.ai/v1/audio/transcriptions"
        private const val TIMEOUT_SECONDS = 120L
    }

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }
    private val apiKeyStore = MistralApiKeyStore(context)

    /** Env-var key override is a developer convenience; never honoured in release builds. */
    private val allowEnvKeyOverride =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    @Volatile
    private var activeCall: Call? = null

    /**
     * Cancellation flag of the transcription in progress (staging copy and request); set
     * by [cancel], which may run before the HTTP call exists.
     */
    @Volatile
    private var activeRequest: AtomicBoolean? = null

    override suspend fun validate(model: ImportedModel): Result<Unit> = withContext(Dispatchers.IO) {
        runCatchingCancellable {
            require(model.backend == "mistral-api") { "This is not a Mistral API model." }
            require(model.architecture == "voxtral-mini-latest") {
                "Mistral API only supports voxtral-mini-latest model."
            }
        }
    }

    override suspend fun prepare(model: ImportedModel): Result<Unit> = validate(model)

    /**
     * Compatibility path for callers that only have locally decoded PCM. The main app uses
     * [transcribeUri] so it can upload the original source file without decoding it first.
     */
    override suspend fun transcribe(
        model: ImportedModel,
        pcm16KhzMono: FloatArray,
        language: LanguageOption,
        onProgress: (Float) -> Unit
    ): String = withContext(Dispatchers.IO) {
        val apiKey = getApiKey()
        MistralUploadStaging.cleanStaleStagingFilesOnce(context.cacheDir)
        val pcmBytes = floatArrayToLittleEndianPcm16(pcm16KhzMono)
        if (pcmBytes.size.toLong() + 44 > MistralUploadStaging.MAX_UPLOAD_BYTES) {
            throw UploadTooLargeException(MistralUploadStaging.MAX_UPLOAD_BYTES)
        }
        val tempFile = File.createTempFile(MistralUploadStaging.STAGING_PREFIX, ".wav", context.cacheDir)
        try {
            FileOutputStream(tempFile).use { output ->
                writeWavHeader(output, pcmBytes.size)
                output.write(pcmBytes)
            }
            transcribeFile(model, apiKey, tempFile, "audio.wav", "audio/wav", language, null, onProgress)
        } finally {
            tempFile.delete()
        }
    }

    override suspend fun transcribeUri(
        model: ImportedModel,
        uri: String,
        language: LanguageOption,
        onProgress: (Float) -> Unit
    ): String = withContext(Dispatchers.IO) {
        // Fail fast on a missing/unreadable key before copying possibly large audio.
        val apiKey = getApiKey()
        MistralUploadStaging.cleanStaleStagingFilesOnce(context.cacheDir)
        onProgress(0f)
        val job = coroutineContext.job
        val cancelled = AtomicBoolean(false)
        activeRequest = cancelled
        try {
            val source = stageSourceFile(Uri.parse(uri)) {
                job.ensureActive()
                if (cancelled.get()) throw CancellationException("Transcription cancelled")
            }
            try {
                transcribeFile(model, apiKey, source.file, source.fileName, source.mimeType, language, cancelled, onProgress)
            } finally {
                if (source.temporary) source.file.delete()
            }
        } finally {
            if (activeRequest === cancelled) activeRequest = null
        }
    }

    /**
     * Aborts the in-flight staging copy or request. Cancelling the calling coroutine has
     * the same effect.
     */
    override fun cancel() {
        activeRequest?.set(true)
        activeCall?.cancel()
    }

    override fun close() {
        cancel()
    }

    private suspend fun transcribeFile(
        model: ImportedModel,
        apiKey: String,
        file: File,
        fileName: String,
        mimeType: String,
        language: LanguageOption,
        cancelled: AtomicBoolean?,
        onProgress: (Float) -> Unit
    ): String {
        coroutineContext.ensureActive()
        if (cancelled?.get() == true) throw CancellationException("Transcription cancelled")
        onProgress(0f)
        require(file.isFile && file.length() > 0) { "The selected audio file is empty or unavailable." }

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", fileName, file.asRequestBody(mimeType.toMediaType()))
            .addFormDataPart("model", model.architecture)
            .apply {
                if (language != LanguageOption.AUTO) addFormDataPart("language", language.code)
            }
            .build()
        val request = Request.Builder()
            .url(API_URL)
            .header("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()

        // Mistral exposes no upload or server-side transcription progress. Report an
        // indeterminate state instead of leaving the UI falsely stuck at a fixed value.
        onProgress(0f)
        coroutineContext.ensureActive()
        val call = client.newCall(request)
        activeCall = call
        // cancel() sets the flag before reading activeCall; checking it after publishing the
        // call means a cancel() racing with call creation is never lost.
        if (cancelled?.get() == true) call.cancel()
        try {
            // awaitResult() cancels the call as soon as this coroutine is cancelled, so a
            // cancellation that races with request setup cannot leave the upload running.
            val response = call.awaitResult()
            coroutineContext.ensureActive()
            if (!response.isSuccessful) {
                Log.w(TAG, "Mistral API request failed with HTTP ${response.code}")
                throw IllegalStateException(apiErrorMessage(response.code))
            }
            val responseBody = response.body
                ?: throw IllegalStateException("Empty response from Mistral API")
            val result = parseMistralResponse(responseBody)
            onProgress(1f)
            return result
        } catch (e: IOException) {
            // cancel() aborts the call with an IOException; report it as cancellation.
            if (call.isCanceled()) {
                throw CancellationException("Transcription cancelled").apply { initCause(e) }
            }
            throw when (e) {
                is java.net.SocketTimeoutException ->
                    IllegalStateException("Network timeout while contacting Mistral API.", e)
                is java.net.UnknownHostException ->
                    IllegalStateException("Unable to reach Mistral API. Check your internet connection.", e)
                is java.net.ConnectException ->
                    IllegalStateException("Unable to connect to Mistral API. Check your internet connection.", e)
                else -> IllegalStateException("Network error while contacting Mistral API.", e)
            }
        } finally {
            if (activeCall === call) activeCall = null
        }
    }

    private fun stageSourceFile(uri: Uri, ensureActive: () -> Unit): UploadSource {
        val limit = MistralUploadStaging.MAX_UPLOAD_BYTES
        if (uri.scheme == "file") {
            val file = uri.path?.let(::File)
            if (file?.isFile == true) {
                MistralUploadStaging.checkDeclaredSize(file.length(), limit)
                val mimeType = mimeTypeFor(file.name)
                return UploadSource(file, MistralUploadStaging.uploadFileName(file.name, mimeType), mimeType, false)
            }
        }

        val resolver = context.contentResolver
        var displayName: String? = null
        var declaredSize: Long? = null
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) displayName = cursor.getString(nameIndex)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) declaredSize = cursor.getLong(sizeIndex)
                }
            }
        // Reject before copying anything; the copy below enforces the limit again in case
        // the provider reports no or a wrong size.
        MistralUploadStaging.checkDeclaredSize(declaredSize, limit)
        val mimeType = resolver.getType(uri) ?: displayName?.let(::mimeTypeFor) ?: "application/octet-stream"
        val uploadName = MistralUploadStaging.uploadFileName(displayName, mimeType)
        val tempFile = File.createTempFile(
            MistralUploadStaging.STAGING_PREFIX,
            MistralUploadStaging.stagingSuffix(displayName, mimeType),
            context.cacheDir
        )
        try {
            resolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    MistralUploadStaging.copyWithLimit(input, output, limit, ensureActive = ensureActive)
                }
            } ?: throw IllegalStateException("Failed to open the selected audio file.")
            return UploadSource(tempFile, uploadName, mimeType, true)
        } catch (error: Throwable) {
            tempFile.delete()
            throw error
        }
    }

    private fun mimeTypeFor(fileName: String): String =
        URLConnection.guessContentTypeFromName(fileName) ?: "application/octet-stream"

    private fun apiErrorMessage(statusCode: Int): String = when (statusCode) {
        401, 403 -> "Mistral API rejected the API key."
        413 -> "The selected audio file is too large for Mistral API."
        429 -> "Mistral API rate limit reached. Please try again shortly."
        in 500..599 -> "Mistral API is temporarily unavailable. Please try again shortly."
        else -> "Mistral API request failed (HTTP $statusCode)."
    }

    private fun floatArrayToLittleEndianPcm16(floatArray: FloatArray): ByteArray {
        val byteArray = ByteArray(floatArray.size * 2)
        for (i in floatArray.indices) {
            val intValue = (floatArray[i].coerceIn(-1f, 1f) * 32767).toInt()
            byteArray[i * 2] = intValue.toByte()
            byteArray[i * 2 + 1] = (intValue shr 8).toByte()
        }
        return byteArray
    }

    private fun writeWavHeader(output: FileOutputStream, dataSize: Int) {
        val sampleRate = 16_000
        val bitsPerSample = 16
        val channels = 1
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val header = ByteArray(44)

        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        writeIntLe(header, 4, dataSize + 36)
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        writeIntLe(header, 16, 16)
        writeShortLe(header, 20, 1)
        writeShortLe(header, 22, channels)
        writeIntLe(header, 24, sampleRate)
        writeIntLe(header, 28, byteRate)
        writeShortLe(header, 32, blockAlign)
        writeShortLe(header, 34, bitsPerSample)
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        writeIntLe(header, 40, dataSize)
        output.write(header)
    }

    private fun writeIntLe(bytes: ByteArray, offset: Int, value: Int) {
        repeat(4) { index -> bytes[offset + index] = (value shr (index * 8)).toByte() }
    }

    private fun writeShortLe(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value shr 8).toByte()
    }

    private fun parseMistralResponse(responseBody: String): String {
        val json = JSONObject(responseBody)
        val text = json.optString("text", "").trim()
        if (text.isEmpty()) {
            val segments = json.optJSONArray("segments")
            val message = if (segments != null && segments.length() == 0) {
                "No speech detected in audio. Please check that it contains clear speech."
            } else {
                json.optString("error").ifBlank { "Mistral API returned an empty transcription." }
            }
            throw IllegalStateException(message)
        }
        return text
    }

    /** Throws with a user-facing message when the key is missing or undecryptable. */
    private fun getApiKey(): String =
        resolveMistralApiKey(System.getenv("MISTRAL_API_KEY"), allowEnvKeyOverride, apiKeyStore::read)

    private data class UploadSource(
        val file: File,
        /** Neutral multipart name (`audio.<ext>`); never the user's original file name. */
        val fileName: String,
        val mimeType: String,
        val temporary: Boolean
    )
}
