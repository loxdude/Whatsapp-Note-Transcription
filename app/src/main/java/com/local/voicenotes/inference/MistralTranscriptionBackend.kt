package com.local.voicenotes.inference

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.local.voicenotes.domain.ImportedModel
import com.local.voicenotes.domain.LanguageOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
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
import java.net.URLConnection
import java.util.concurrent.TimeUnit
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

    @Volatile
    private var activeCall: Call? = null

    override suspend fun validate(model: ImportedModel): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
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
        val pcmBytes = floatArrayToLittleEndianPcm16(pcm16KhzMono)
        val tempFile = File.createTempFile("mistral-", ".wav", context.cacheDir)
        try {
            FileOutputStream(tempFile).use { output ->
                writeWavHeader(output, pcmBytes.size)
                output.write(pcmBytes)
            }
            transcribeFile(model, tempFile, "audio.wav", "audio/wav", language, onProgress)
        } finally {
            tempFile.delete()
        }
    }

    override suspend fun transcribeUri(
        model: ImportedModel,
        uri: Uri,
        language: LanguageOption,
        onProgress: (Float) -> Unit
    ): String = withContext(Dispatchers.IO) {
        val source = stageSourceFile(uri)
        try {
            transcribeFile(model, source.file, source.fileName, source.mimeType, language, onProgress)
        } finally {
            if (source.temporary) source.file.delete()
        }
    }

    override fun cancel() {
        activeCall?.cancel()
    }

    override fun close() {
        cancel()
    }

    private suspend fun transcribeFile(
        model: ImportedModel,
        file: File,
        fileName: String,
        mimeType: String,
        language: LanguageOption,
        onProgress: (Float) -> Unit
    ): String {
        coroutineContext.ensureActive()
        onProgress(0f)
        require(file.isFile && file.length() > 0) { "The selected audio file is empty or unavailable." }
        val apiKey = getApiKey()?.trim().takeUnless { it.isNullOrEmpty() }
            ?: throw IllegalStateException("Mistral API key not configured")

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
        val call = client.newCall(request)
        activeCall = call
        try {
            call.execute().use { response ->
                coroutineContext.ensureActive()
                if (!response.isSuccessful) {
                    Log.w(TAG, "Mistral API request failed with HTTP ${response.code}")
                    throw IllegalStateException(apiErrorMessage(response.code))
                }
                val responseBody = response.body?.string()
                    ?: throw IllegalStateException("Empty response from Mistral API")
                val result = parseMistralResponse(responseBody)
                onProgress(1f)
                return result
            }
        } catch (e: java.net.SocketTimeoutException) {
            throw IllegalStateException("Network timeout while contacting Mistral API.", e)
        } catch (e: java.net.UnknownHostException) {
            throw IllegalStateException("Unable to reach Mistral API. Check your internet connection.", e)
        } catch (e: java.net.ConnectException) {
            throw IllegalStateException("Unable to connect to Mistral API. Check your internet connection.", e)
        } catch (e: java.io.IOException) {
            if (call.isCanceled()) throw CancellationException("Transcription cancelled")
            throw IllegalStateException("Network error while contacting Mistral API.", e)
        } finally {
            if (activeCall === call) activeCall = null
        }
    }

    private fun stageSourceFile(uri: Uri): UploadSource {
        if (uri.scheme == "file") {
            val file = uri.path?.let(::File)
            if (file?.isFile == true) return UploadSource(file, file.name, mimeTypeFor(file.name), false)
        }

        val resolver = context.contentResolver
        val fileName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            ?.takeIf { it.isNotBlank() }
            ?: "audio"
        val tempFile = File.createTempFile("mistral-", fileName.safeSuffix(), context.cacheDir)
        try {
            resolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use(input::copyTo)
            } ?: throw IllegalStateException("Failed to open the selected audio file.")
            return UploadSource(tempFile, fileName, resolver.getType(uri) ?: mimeTypeFor(fileName), true)
        } catch (error: Throwable) {
            tempFile.delete()
            throw error
        }
    }

    private fun String.safeSuffix(): String {
        val extension = substringAfterLast('.', "").takeIf { it.matches(Regex("[A-Za-z0-9]{1,10}")) }
        return extension?.let { ".${it.lowercase()}" } ?: ".audio"
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

    private fun getApiKey(): String? {
        System.getenv("MISTRAL_API_KEY")?.let { return it }
        return apiKeyStore.get()
    }

    private data class UploadSource(
        val file: File,
        val fileName: String,
        val mimeType: String,
        val temporary: Boolean
    )
}
