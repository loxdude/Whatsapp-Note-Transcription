package com.local.voicenotes.inference

import android.util.Log
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue

/** Opt-in test for the three graphs extracted from the Qwen LiteRT-LM package. */
class QwenCachedDecoderSmokeTest {
    @Test fun cachedDecoderTranscribesFixture() {
        val directory = InstrumentationRegistry.getArguments().getString("qwenDirectory")
        assumeTrue("Supply qwenDirectory with extracted package and fixture", directory != null)
        val root = File(directory!!)
        val buffers = ArrayList<TensorBuffer>()
        val models = ArrayList<CompiledModel>()
        val environment = Environment.create()
        fun model(name: String): CompiledModel = CompiledModel.create(
            File(root, "$name.tflite").absolutePath, CompiledModel.Options(Accelerator.CPU), environment
        ).also { models.add(it) }
        fun inputs(model: CompiledModel, signature: String) = model.createInputBuffers(signature).also { buffers.addAll(it) }
        fun outputs(model: CompiledModel, signature: String) = model.createOutputBuffers(signature).also { buffers.addAll(it) }
        try {
            val started = SystemClock.elapsedRealtime()
            val encoder = model("tf_lite_audio_encoder_hw")
            val embedder = model("tf_lite_embedder")
            val lm = model("tf_lite_prefill_decode")
            val encIn = inputs(encoder, "encode")
            val encOut = outputs(encoder, "encode")
            val embIn = inputs(embedder, "embedder")
            val embOut = outputs(embedder, "embedder")
            val preIn = inputs(lm, "prefill_128")
            val preOut = outputs(lm, "prefill_128")
            val decIn = inputs(lm, "decode")
            val decOut = outputs(lm, "decode")
            Log.i("QwenSmokeTest", "Load took ${SystemClock.elapsedRealtime()-started}ms")
            require(preIn.size == 59 && preOut.size == 56 && decIn.size == 59 && decOut.size == 57)
            val melBytes = ByteBuffer.wrap(File(root, "mel.bin").readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            encIn[0].writeFloat(FloatArray(melBytes.remaining()).also { melBytes.get(it) })
            val inferenceStarted = SystemClock.elapsedRealtime()
            encoder.run(encIn, encOut, "encode")
            val encoded = encOut[0].readFloat()
            require(encoded.size == 73 * 1024)
            Log.i("QwenSmokeTest", "Encoder took ${SystemClock.elapsedRealtime()-inferenceStarted}ms")
            val padded = FloatArray(128 * 1024)
            encoded.copyInto(padded, endIndex = 72 * 1024)
            preIn[0].writeFloat(padded)
            preIn[1].writeInt(IntArray(128) { it })
            val zeroCache = FloatArray(8 * 512 * 128)
            for (slot in 3 until 59) preIn[slot].writeFloat(zeroCache)
            preIn[2].writeFloat(FloatArray(128*512) { if (it%512 <= it/512) 0f else Float.NEGATIVE_INFINITY })
            lm.run(preIn, preOut, "prefill_128")
            for (slot in 0 until 56) decIn[slot+3].writeFloat(preOut[slot].readFloat())
            decIn[0].writeFloat(encoded.copyOfRange(72*1024,73*1024))
            val tokens = ArrayList<Int>()
            for (position in 72 until 172) {
                decIn[1].writeInt(intArrayOf(position))
                decIn[2].writeFloat(FloatArray(512) { if (it <= position) 0f else Float.NEGATIVE_INFINITY })
                lm.run(decIn, decOut, "decode")
                val logits = decOut[56].readFloat()
                val token = logits.indices.maxBy { logits[it] }
                tokens.add(token)
                Log.i("QwenSmokeTest", "token=$token elapsed=${SystemClock.elapsedRealtime()-inferenceStarted}ms")
                if (token == 151643 || token == 151645) break
                for (slot in 0 until 56) decIn[slot+3].writeFloat(decOut[slot].readFloat())
                embIn[0].writeInt(intArrayOf(token))
                embedder.run(embIn, embOut, "embedder")
                decIn[0].writeFloat(embOut[0].readFloat())
            }
            Log.i("QwenSmokeTest", "tokens=$tokens total=${SystemClock.elapsedRealtime()-inferenceStarted}ms")
            assertEquals(listOf(11528,6364,151704,3036,773,11,847,12357,8877,11,2548,537,13,151645),tokens)
        } finally {
            buffers.asReversed().forEach { it.close() }
            models.asReversed().forEach { it.close() }
            environment.close()
        }
    }
}
