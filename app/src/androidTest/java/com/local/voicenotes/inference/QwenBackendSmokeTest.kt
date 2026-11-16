package com.local.voicenotes.inference

import android.net.Uri
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.local.voicenotes.audio.AndroidAudioDecoder
import com.local.voicenotes.domain.LanguageOption
import com.local.voicenotes.model.ModelRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class QwenBackendSmokeTest {
    @Test fun importedQwenTranscribesFixture(): Unit = runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue("Opt in with importedQwen=true",args.getString("importedQwen")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val repo=ModelRepository(context)
        val model=repo.models().first { it.id==repo.selectedModelId() }
        require(model.backend=="qwen-asr-cpu")
        val backend=QwenAsrBackend(context)
        try {
            backend.validate(model).getOrThrow()
            backend.prepare(model).getOrThrow()
            val pcm=AndroidAudioDecoder(context.contentResolver).decode(
                Uri.parse("file:///data/user/0/com.local.voicenotes.litert/files/qwen17-probe/jfk.wav")) {}
            val language=if(args.getString("forcedEnglish")=="true") LanguageOption.ENGLISH else LanguageOption.AUTO
            val text=backend.transcribe(model,pcm.copyOf(minOf(pcm.size,80000)),language) {}
            Log.i("QwenBackendTest","model=${model.displayName} language=$language text=$text")
            assertTrue(text.contains("fellow Americans",ignoreCase=true))
            assertTrue(!text.contains("<asr_text>"))
        } finally { backend.close() }
    }
}
