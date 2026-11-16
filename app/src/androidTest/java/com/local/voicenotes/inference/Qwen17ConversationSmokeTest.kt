package com.local.voicenotes.inference

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.local.voicenotes.audio.AndroidAudioDecoder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Runs only when given qwen17ModelPath; never uploads audio or changes selections. */
class Qwen17ConversationSmokeTest {
    @Test fun transcribesSpeechWithConversationApi(): Unit = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val modelPath = arguments.getString("qwen17ModelPath")
        assumeTrue("Supply qwen17ModelPath to opt in", modelPath != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val cache = File(context.cacheDir, "qwen17-probe").apply { mkdirs() }
        val started = SystemClock.elapsedRealtime()
        val decoderThreads = arguments.getString("decoderThreads")?.toInt() ?: 4
        val audioThreads = arguments.getString("audioThreads")?.toInt() ?: 4
        require(decoderThreads in 1..8 && audioThreads in 1..8)
        val backend = if (arguments.getString("qwen17Backend") == "gpu") Backend.GPU() else Backend.CPU(threadCount = decoderThreads)
        Log.i("Qwen17SmokeTest", "CONFIG decoderThreads=$decoderThreads audioThreads=$audioThreads")
        Engine(EngineConfig(modelPath = modelPath!!, backend = backend,
            audioBackend = Backend.CPU(threadCount = audioThreads), maxNumTokens = 1024,
            cacheDir = cache.absolutePath)).use { engine ->
            engine.initialize()
            Log.i("Qwen17SmokeTest", "loadMs=${SystemClock.elapsedRealtime()-started}")
            val selectedAudio = arguments.getString("selectedAudio") == "true"
            val uri = if (selectedAudio) {
                context.getSharedPreferences("audio_selection", Context.MODE_PRIVATE).getString("uri", null)
            } else arguments.getString("audioPath")?.let { Uri.fromFile(File(it)).toString() }
            requireNotNull(uri) { "Supply audioPath or select audio in the app" }
            val decodeStarted = SystemClock.elapsedRealtime()
            val pcm = AndroidAudioDecoder(context.contentResolver).decode(Uri.parse(uri)) {}
            val decodeMs = SystemClock.elapsedRealtime()-decodeStarted
            val fullAudio = arguments.getString("fullAudio") == "true"
            val duration = arguments.getString("seconds")?.toInt() ?: 30
            require(duration in 1..30)
            val totalSamples = if (fullAudio) pcm.size else minOf(pcm.size, duration * 16000)
            val chunkSamples = 30 * 16000
            val chunks = (totalSamples + chunkSamples - 1) / chunkSamples
            var inferenceMs = 0L
            val processingStarted = SystemClock.elapsedRealtime()
            for (chunk in 0 until chunks) {
                val offset = chunk * chunkSamples
                val count = minOf(chunkSamples, totalSamples-offset)
                val wav = File(cache, "probe.wav")
                try {
                    val bytes = ByteBuffer.allocate(44 + count * 2).order(ByteOrder.LITTLE_ENDIAN)
                    bytes.put("RIFF".toByteArray()).putInt(36 + count*2).put("WAVEfmt ".toByteArray())
                    bytes.putInt(16).putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16)
                    bytes.put("data".toByteArray()).putInt(count*2)
                    for (i in offset until offset+count) bytes.putShort((pcm[i].coerceIn(-1f,1f)*32767).toInt().toShort())
                    wav.writeBytes(bytes.array())
                    engine.createConversation(ConversationConfig(
                        samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0),
                        maxOutputToken = 512,
                    )).use { conversation ->
                        val inferenceStarted = SystemClock.elapsedRealtime()
                        val response = conversation.sendMessage(Contents.of(Content.AudioFile(wav.absolutePath))).toString()
                        val elapsed = SystemClock.elapsedRealtime()-inferenceStarted
                        inferenceMs += elapsed
                        val hash = MessageDigest.getInstance("SHA-256").digest(response.toByteArray()).joinToString("") { "%02x".format(it) }
                        Log.i("Qwen17SmokeTest", "backend=${backend.name} chunk=${chunk+1}/$chunks audioSamples=$count inferenceMs=$elapsed outputChars=${response.length} outputSha256=$hash")
                        val memory = android.os.Debug.MemoryInfo()
                        android.os.Debug.getMemoryInfo(memory)
                        val peakRss = File("/proc/self/status").readLines().firstOrNull { it.startsWith("VmHWM:") }
                        Log.i("Qwen17SmokeTest", "totalPssKiB=${memory.totalPss} peakRss=$peakRss")
                        val transcript = response.substringAfter("<asr_text>", "").trim()
                        assertTrue("Missing transcript in: $response", transcript.isNotEmpty())
                        if (!selectedAudio) assertTrue("Fixture not recognized: $response", transcript.contains("fellow Americans", ignoreCase = true))
                    }
                } finally { wav.delete() }
            }
            Log.i("Qwen17SmokeTest", "SUMMARY backend=${backend.name} decoderThreads=$decoderThreads audioThreads=$audioThreads audioSamples=$totalSamples chunks=$chunks decodeMs=$decodeMs inferenceMs=$inferenceMs processingMs=${SystemClock.elapsedRealtime()-processingStarted} totalMs=${SystemClock.elapsedRealtime()-started}")
        }
    }
}
