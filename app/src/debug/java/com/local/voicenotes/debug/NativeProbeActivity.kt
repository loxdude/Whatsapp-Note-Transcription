package com.local.voicenotes.debug

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.TextView
import com.local.voicenotes.inference.CohereControl
import com.local.voicenotes.inference.CohereText
import com.local.voicenotes.inference.CpuCores
import com.local.voicenotes.inference.NativeCohere
import com.local.voicenotes.translate.GemmaTranslator
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/**
 * Debug-only device check that both native stacks load and run. It shows over the lock screen so
 * the process gets the top-app CPU set (the big cores):
 * adb shell am start -n com.local.voicenotes.litert/com.local.voicenotes.debug.NativeProbeActivity \
 *   [--es cohere <gguf> --es pcm <f32le 16 kHz mono>] [--es gemma <litertlm> --es text <text>]
 * Paths are relative to the app's files dir. Results go to logcat tag VoiceNotesProbe.
 */
class NativeProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(TextView(this).apply { text = "Native probe running" })
        val files = filesDir
        val cohere = intent.getStringExtra("cohere")
        val pcm = intent.getStringExtra("pcm")
        val gemma = intent.getStringExtra("gemma")
        val text = intent.getStringExtra("text") ?: "مرحبا، كيف حالك اليوم؟ سأصل إلى البيت بعد ساعة."
        thread(name = "NativeProbe") {
            try {
                Log.i(TAG, "cohere lib loadError=${NativeCohere.loadError}")
                Log.i(TAG, "litertlm lib=" + runCatching { System.loadLibrary("litertlm_jni"); "ok" }.getOrElse { it.toString() })
                val threads = CpuCores.inferenceThreads(CpuCores.readMaxFreqsKhz())
                Log.i(TAG, "threads=$threads freqs=${CpuCores.readMaxFreqsKhz()}")
                if (cohere != null && pcm != null) probeCohere(File(files, cohere), File(files, pcm), threads)
                if (gemma != null) probeGemma(File(files, gemma), text)
            } catch (error: Throwable) {
                Log.e(TAG, "probe failed", error)
            }
            Log.i(TAG, "probe done")
            runOnUiThread { finish() }
        }
    }

    private fun probeCohere(model: File, pcmFile: File, threads: Int) {
        val bytes = pcmFile.readBytes()
        val samples = FloatArray(bytes.size / 4)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(samples)
        var started = System.nanoTime()
        val handle = NativeCohere.open(model.absolutePath, threads)
        Log.i(TAG, "cohere open handle=${handle != 0L} ms=${(System.nanoTime() - started) / 1_000_000}")
        if (handle == 0L) return
        try {
            started = System.nanoTime()
            val out = NativeCohere.transcribe(handle, samples, "ar", CohereControl { done, total ->
                Log.i(TAG, "cohere window $done/$total"); true
            })
            val ms = (System.nanoTime() - started) / 1_000_000
            val audioMs = samples.size / 16
            Log.i(TAG, "cohere audio=${audioMs}ms took=${ms}ms speed=${"%.2f".format(audioMs.toDouble() / ms)}x")
            Log.i(TAG, "cohere raw=" + out?.let { String(it, Charsets.UTF_8) })
            Log.i(TAG, "cohere clean=" + out?.let { CohereText.clean(String(it, Charsets.UTF_8)) })
            // Cancelling from the first window callback must return null without a transcript.
            val cancelled = NativeCohere.transcribe(handle, samples, "ar", CohereControl { _, _ -> false })
            Log.i(TAG, "cohere cancelled result=${cancelled == null}")
        } finally {
            NativeCohere.close(handle)
        }
    }

    private fun probeGemma(model: File, text: String) {
        val translator = GemmaTranslator(cacheDir)
        runBlocking {
            for (language in listOf("German", "English")) {
                val engine = translator.forModel(model, language)
                var started = System.nanoTime()
                engine.load()
                Log.i(TAG, "gemma load ms=${(System.nanoTime() - started) / 1_000_000}")
                started = System.nanoTime()
                val out = engine.translate(text)
                Log.i(TAG, "gemma $language ms=${(System.nanoTime() - started) / 1_000_000} out=$out")
            }
            translator.release()
        }
    }

    private companion object { const val TAG = "VoiceNotesProbe" }
}
