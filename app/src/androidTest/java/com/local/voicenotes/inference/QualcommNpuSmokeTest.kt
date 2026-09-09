package com.local.voicenotes.inference

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import androidx.test.platform.app.InstrumentationRegistry
import com.local.voicenotes.audio.AndroidAudioDecoder
import com.local.voicenotes.domain.LanguageOption
import com.local.voicenotes.model.ModelRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeNotNull
import org.junit.Test

/** Uses the user's already imported and selected local model; never calls an API. */
class QualcommNpuSmokeTest {
    @Test fun selectedModelRunsOneChunk(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = ModelRepository(context)
        val selected = repository.selectedModelId()
        var model = repository.models().firstOrNull {
            it.id == selected && it.enabled && (it.backend.startsWith("litert-qnn") || it.backend == "litert-cpu")
        }
        assumeNotNull(model)
        val cpuPath = InstrumentationRegistry.getArguments().getString("cpuModelPath")
        if (cpuPath != null) model = model!!.copy(id = "cpu-test", path = cpuPath, sizeBytes = File(cpuPath).length(), backend = "litert-cpu")
        val backend = LiteRtParakeetBackend(context)
        try {
            repeat(3) {
                backend.prepare(model!!).getOrThrow()
                backend.close()
            }
            backend.prepare(model!!).getOrThrow()
            if (InstrumentationRegistry.getArguments().getString("selectedAudio") == "true") {
                val uri = InstrumentationRegistry.getArguments().getString("audioPath")?.let {
                    Uri.fromFile(File(it)).toString()
                } ?: context.getSharedPreferences("audio_selection", Context.MODE_PRIVATE).getString("uri", null)
                requireNotNull(uri) { "Select an audio file before running the selectedAudio test." }
                val pcm = AndroidAudioDecoder(context.contentResolver).decode(Uri.parse(uri)) {}
                require(pcm.isNotEmpty())
                val start = pcm.indices.step(80_000).maxBy { offset ->
                    (offset until minOf(offset + 80_000, pcm.size)).sumOf { pcm[it].toDouble() * pcm[it] }
                }
                Log.i("NpuSmokeTest", "samples=${pcm.size} peak=${pcm.maxOf { kotlin.math.abs(it) }} excerptStart=$start")
                val text = backend.transcribe(model, pcm.copyOfRange(start, minOf(pcm.size, start + 80_000)), LanguageOption.AUTO) {}
                check(text.isNotBlank()) { "The selected speech excerpt produced no transcript." }
            } else backend.transcribe(model, FloatArray(80_000), LanguageOption.AUTO) {}
        } finally {
            backend.close()
        }
    }
}
