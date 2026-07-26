package com.local.voicenotes.inference

import android.content.Context
import android.net.Uri
import android.util.Log
import com.local.voicenotes.domain.ImportedModel
import com.local.voicenotes.domain.LanguageOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class MistralTranscriptionBackend(private val context: Context) : TranscriptionBackend {
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

    @Volatile
    private var cancelled = false

    override suspend fun validate(model: ImportedModel): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(model.backend == "mistral-api") {
                "This is not a Mistral API model."
            }
            require(model.architecture == "voxtral-mini-latest") {
                "Mistral API only supports voxtral-mini-latest model."
            }
        }
    }

    override suspend fun prepare(model: ImportedModel): Result<Unit> = validate(model)

    override suspend fun transcribe(
        model: ImportedModel,
        pcm16KhzMono: FloatArray,
        language: LanguageOption,
        onProgress: (Float) -> Unit
    ): String = withContext(Dispatchers.IO) {
        cancelled = false
        onProgress(0f)

        try {
            Log.i(TAG, "Starting Mistral transcription with ${pcm16KhzMono.size} samples")
            
            // Convert FloatArray to ByteArray (PCM16)
            val pcmBytes = FloatArrayToByteArray(pcm16KhzMono)
            Log.i(TAG, "Converted to ${pcmBytes.size} bytes of PCM data")
            
            // Create temporary file for audio
            val tempFile = File.createTempFile("audio", ".wav", context.cacheDir)
            tempFile.deleteOnExit()
            
            // Write WAV header and PCM data
            FileOutputStream(tempFile).use { output ->
                writeWavHeader(output, pcmBytes.size)
                output.write(pcmBytes)
            }
            Log.i(TAG, "Created WAV file: ${tempFile.absolutePath}, size: ${tempFile.length()}")

            onProgress(0.3f)

            // Get API key from preferences or environment
            val apiKey = getApiKey() ?: throw IllegalStateException("Mistral API key not configured")
            Log.i(TAG, "API key found, length: ${apiKey.length}")

            // Build request
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    "audio.wav",
                    tempFile.asRequestBody("audio/wav".toMediaType())
                )
                .addFormDataPart("model", model.architecture)
                .apply {
                    if (language != LanguageOption.AUTO) {
                        addFormDataPart("language", language.code)
                    }
                }
                .build()

            val request = Request.Builder()
                .url(API_URL)
                .header("Authorization", "Bearer $apiKey")
                .post(requestBody)
                .build()

            onProgress(0.5f)

            // Execute request
            Log.i(TAG, "Executing API request to $API_URL")
            try {
                client.newCall(request).execute().use { response ->
                    Log.i(TAG, "API response code: ${response.code}, message: ${response.message}")
                    if (!response.isSuccessful) {
                        val errorBody = response.body?.string() ?: "No error body"
                        Log.e(TAG, "API request failed: ${response.code} - ${response.message}, body: $errorBody")
                        throw Exception("API request failed: ${response.code} - ${response.message} - $errorBody")
                    }

                    val responseBody = response.body?.string()
                        ?: throw Exception("Empty response from API")
                    Log.i(TAG, "API response body: $responseBody")

                    val json = JSONObject(responseBody)
                    val text = json.getString("text")
                    Log.i(TAG, "Extracted text: $text")

                    onProgress(1f)
                    text
                }
            } catch (e: java.net.SocketTimeoutException) {
                Log.e(TAG, "Network timeout: ${e.message}")
                throw Exception("Network timeout - unable to connect to Mistral API. Please check your internet connection.")
            } catch (e: java.net.UnknownHostException) {
                Log.e(TAG, "DNS resolution failed: ${e.message}")
                throw Exception("DNS resolution failed - unable to connect to Mistral API. Please check your internet connection.")
            } catch (e: java.net.ConnectException) {
                Log.e(TAG, "Connection failed: ${e.message}")
                throw Exception("Connection failed - unable to connect to Mistral API. Please check your internet connection and API key.")
            }
        } catch (e: Exception) {
            if (cancelled) {
                throw CancellationException("Transcription cancelled")
            }
            Log.e(TAG, "Transcription failed", e)
            throw e
        }
    }

    override fun cancel() {
        cancelled = true
    }

    override fun close() {
        cancelled = true
    }

    private fun FloatArrayToByteArray(floatArray: FloatArray): ByteArray {
        val byteArray = ByteArray(floatArray.size * 2)
        for (i in floatArray.indices) {
            val floatValue = floatArray[i].coerceIn(-1f, 1f)
            val intValue = (floatValue * 32767).toInt()
            byteArray[i * 2] = (intValue ushr 8).toByte()
            byteArray[i * 2 + 1] = intValue.toByte()
        }
        return byteArray
    }

    private fun writeWavHeader(output: FileOutputStream, dataSize: Int) {
        val sampleRate = 16000
        val bitsPerSample = 16
        val channels = 1
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8

        val header = ByteArray(44)
        // RIFF header
        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        // File size
        val fileSize = dataSize + 36
        header[4] = (fileSize and 0xFF).toByte()
        header[5] = ((fileSize ushr 8) and 0xFF).toByte()
        header[6] = ((fileSize ushr 16) and 0xFF).toByte()
        header[7] = ((fileSize ushr 24) and 0xFF).toByte()
        // WAVE format
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        // fmt chunk
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        // fmt chunk size (16 bytes)
        header[16] = 16
        header[17] = 0
        header[18] = 0
        header[19] = 0
        // Audio format (1 = PCM)
        header[20] = 1
        header[21] = 0
        // Channels
        header[22] = channels.toByte()
        header[23] = 0
        // Sample rate
        header[24] = (sampleRate and 0xFF).toByte()
        header[25] = ((sampleRate ushr 8) and 0xFF).toByte()
        header[26] = ((sampleRate ushr 16) and 0xFF).toByte()
        header[27] = ((sampleRate ushr 24) and 0xFF).toByte()
        // Byte rate
        header[28] = (byteRate and 0xFF).toByte()
        header[29] = ((byteRate ushr 8) and 0xFF).toByte()
        header[30] = ((byteRate ushr 16) and 0xFF).toByte()
        header[31] = ((byteRate ushr 24) and 0xFF).toByte()
        // Block align
        header[32] = blockAlign.toByte()
        header[33] = 0
        // Bits per sample
        header[34] = bitsPerSample.toByte()
        header[35] = 0
        // data chunk
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        // data chunk size
        header[40] = (dataSize and 0xFF).toByte()
        header[41] = ((dataSize ushr 8) and 0xFF).toByte()
        header[42] = ((dataSize ushr 16) and 0xFF).toByte()
        header[43] = ((dataSize ushr 24) and 0xFF).toByte()

        output.write(header)
    }

    private fun getApiKey(): String? {
        // Try to get from environment first
        System.getenv("MISTRAL_API_KEY")?.let { return it }
        
        // Try to get from Android preferences
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        return prefs.getString("mistral_api_key", null)
    }
}
