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
import kotlinx.coroutines.withContext
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
import kotlin.coroutines.cancellation.CancellationException

class MistralTranscriptionBackend(
    private val context: Context,
    // AppGraph should supply the same store used by settings; the default keeps old callers valid.
    private val apiKeyStore: MistralApiKeyStore = MistralApiKeyStore(context)
) : UriTranscriptionBackend {
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

    /** Env-var key override is a developer convenience; never honoured in release builds. */
    private val allowEnvKeyOverride =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private val requests = MistralTranscriptionRequests()

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
        requests.run(
            readKey = ::getApiKey,
            stage = { ensureActive ->
                MistralUploadStaging.cleanStaleStagingFilesOnce(context.cacheDir)
                MistralPcmStaging.stage(context.cacheDir, pcm16KhzMono, ensureActive)
            },
            send = { apiKey, source, request ->
                transcribeFile(model, apiKey, source.file, source.fileName, source.mimeType, language, request, onProgress)
            }
        )
    }

    override suspend fun transcribeUri(
        model: ImportedModel,
        uri: String,
        language: LanguageOption,
        onProgress: (Float) -> Unit
    ): String = withContext(Dispatchers.IO) {
        requests.run(
            readKey = ::getApiKey,
            stage = { ensureActive ->
                MistralUploadStaging.cleanStaleStagingFilesOnce(context.cacheDir)
                onProgress(0f)
                stageSourceFile(Uri.parse(uri), ensureActive)
            },
            send = { apiKey, source, request ->
                transcribeFile(model, apiKey, source.file, source.fileName, source.mimeType, language, request, onProgress)
            }
        )
    }

    /** Aborts staging or the HTTP call for the current request only. */
    override fun cancel() = requests.cancel()

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
        activeRequest: MistralTranscriptionRequests.Request,
        onProgress: (Float) -> Unit
    ): String {
        activeRequest.ensureActive()
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
        activeRequest.ensureActive()
        val call = client.newCall(request)
        activeRequest.attachCall(call::cancel)
        // A cancellation racing with newCall must abort this call before execution.
        activeRequest.ensureActive()
        try {
            // awaitResult() cancels the call as soon as this coroutine is cancelled, so a
            // cancellation that races with request setup cannot leave the upload running.
            val response = call.awaitResult()
            activeRequest.ensureActive()
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
        }
    }

    private fun stageSourceFile(uri: Uri, ensureActive: () -> Unit): MistralStagedAudio {
        ensureActive()
        val limit = MistralUploadStaging.MAX_UPLOAD_BYTES
        if (uri.scheme == "file") {
            val file = uri.path?.let(::File)
            if (file?.isFile == true) {
                MistralUploadStaging.checkDeclaredSize(file.length(), limit)
                val mimeType = mimeTypeFor(file.name)
                return MistralStagedAudio(file, MistralUploadStaging.uploadFileName(file.name, mimeType), mimeType, false)
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
        ensureActive()
        // Reject before copying anything; the copy below enforces the limit again in case
        // the provider reports no or a wrong size.
        MistralUploadStaging.checkDeclaredSize(declaredSize, limit)
        val mimeType = resolver.getType(uri) ?: displayName?.let(::mimeTypeFor) ?: "application/octet-stream"
        val uploadName = MistralUploadStaging.uploadFileName(displayName, mimeType)
        ensureActive()
        val tempFile = File.createTempFile(
            MistralUploadStaging.STAGING_PREFIX,
            MistralUploadStaging.stagingSuffix(displayName, mimeType),
            context.cacheDir
        )
        try {
            ensureActive()
            resolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    MistralUploadStaging.copyWithLimit(input, output, limit, ensureActive = ensureActive)
                }
            } ?: throw IllegalStateException("Failed to open the selected audio file.")
            return MistralStagedAudio(tempFile, uploadName, mimeType, true)
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
}
