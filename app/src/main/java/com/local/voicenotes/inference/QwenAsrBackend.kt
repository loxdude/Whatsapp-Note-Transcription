package com.local.voicenotes.inference

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.local.voicenotes.audio.QwenFeatureExtractor
import com.local.voicenotes.domain.ImportedModel
import com.local.voicenotes.domain.LanguageOption
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlin.coroutines.coroutineContext

/** Serializes native loading, inference and disposal; all processing stays local. */
class QwenAsrBackend(private val context: Context) : TranscriptionBackend {
    companion object {
        private val worker = Executors.newSingleThreadExecutor { Thread(it, "QwenAsr") }
        private val dispatcher = worker.asCoroutineDispatcher()
    }
    @Volatile private var cancelled = false
    private val conversationLock = Any()
    private var activeConversation: Conversation? = null
    private var preparedId: String? = null
    private var engine: Engine? = null
    private var tensorSession: CachedSession? = null
    private val frontend by lazy { QwenFeatureExtractor() }

    override suspend fun validate(model: ImportedModel): Result<Unit> = withContext(dispatcher) {
        runCatching {
            require(model.backend == "qwen-asr-cpu" && model.architecture in setOf("qwen3-asr-0.6b", "qwen3-asr-1.7b")) {
                "Choose a supported Qwen3-ASR LiteRT-LM package."
            }
            open(model).use { input ->
                val header = ByteArray(8)
                java.io.DataInputStream(input).readFully(header)
                require(header.contentEquals("LITERTLM".toByteArray())) { "Invalid LiteRT-LM header" }
            }
        }
    }
    override suspend fun prepare(model: ImportedModel): Result<Unit> = withContext(dispatcher) {
        runCatching { coroutineContext.ensureActive(); session(model); Unit }
    }
    private fun open(model: ImportedModel) = if (Uri.parse(model.path).scheme == "content") {
        context.contentResolver.openInputStream(Uri.parse(model.path)) ?: error("Cannot open the Qwen model")
    } else File(model.path).inputStream()

    private suspend fun session(model: ImportedModel) {
        if (preparedId == model.id) return
        dispose()
        val directory = File(context.noBackupFilesDir,"qwen-asr/${model.id}").apply { mkdirs() }
        val target = File(directory,"model.litertlm")
        val graphs = File(directory,"graphs")
        val extracted = model.architecture == "qwen3-asr-0.6b" && File(graphs,"complete").isFile &&
            File(graphs,"complete").readText() == model.sizeBytes.toString() &&
            listOf("tf_lite_audio_encoder_hw.tflite", "tf_lite_embedder.tflite", "tf_lite_prefill_decode.tflite", "tokenizer.json").all { File(graphs,it).isFile }
        if (!extracted && (!target.isFile || target.length() != model.sizeBytes)) {
            val partial = File(directory,"model.partial")
            try {
                open(model).use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(1024*1024)
                        var written = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            written += n
                            require(written <= model.sizeBytes) { "Model size changed; import it again" }
                            output.write(buffer,0,n)
                        }
                        require(written == model.sizeBytes) { "Incomplete model copy" }
                        output.fd.sync()
                    }
                }
                require(partial.renameTo(target)) { "Cannot finalize Qwen model cache" }
            } finally { partial.delete() }
        }
        try {
            if (model.architecture == "qwen3-asr-1.7b") {
                val cache = File(context.cacheDir,"qwen-asr/${model.id}").apply { mkdirs() }
                val created = Engine(EngineConfig(modelPath = target.absolutePath,
                    backend = Backend.CPU(threadCount = 4), audioBackend = Backend.CPU(threadCount = 4),
                    maxNumTokens = 1024, cacheDir = cache.absolutePath))
                created.initialize()
                engine = created
            } else {
                if (!extracted) QwenPackage.extract(target,graphs)
                tensorSession = CachedSession(graphs)
                // The original user-owned Downloads file stays in place.
                target.delete()
            }
            preparedId = model.id
        } catch (error: Throwable) { dispose(); throw error }
    }

    override suspend fun transcribe(model: ImportedModel, pcm16KhzMono: FloatArray,
        language: LanguageOption, onProgress: (Float) -> Unit): String = withContext(dispatcher) {
        coroutineContext.ensureActive()
        cancelled = false
        session(model)
        val samples = if (engine != null) 480000 else 80000
        val chunks = (pcm16KhzMono.size + samples - 1) / samples
        val transcripts = ArrayList<String>()
        val caller = coroutineContext
        val checkActive = { caller.ensureActive(); if (cancelled) throw kotlinx.coroutines.CancellationException() }
        for (chunk in 0 until chunks) {
            checkActive()
            val offset = chunk * samples
            val raw = if (engine != null) {
                val wav = File.createTempFile("qwen-audio-", ".wav", context.cacheDir)
                try {
                    writeWav(wav,pcm16KhzMono,offset,minOf(samples,pcm16KhzMono.size-offset))
                    val conversation = engine!!.createConversation(ConversationConfig(
                        samplerConfig = SamplerConfig(topK=1,topP=1.0,temperature=0.0), maxOutputToken=512))
                    synchronized(conversationLock) { activeConversation = conversation }
                    try {
                        checkActive()
                        val content = mutableListOf<Content>(Content.AudioFile(wav.absolutePath))
                        if (language != LanguageOption.AUTO) content.add(Content.Text("language ${language.label}<asr_text>"))
                        val response = conversation.sendMessage(Contents.of(content)).toString()
                        if (language == LanguageOption.AUTO) response else "<asr_text>$response"
                    } finally {
                        synchronized(conversationLock) { activeConversation = null; conversation.close() }
                    }
                } finally { wav.delete() }
            } else tensorSession!!.transcribe(frontend.extract(pcm16KhzMono,offset,checkActive),language,checkActive)
            checkActive()
            require(raw.contains("<asr_text>")) { "Qwen returned an unexpected response for chunk ${chunk+1}" }
            val text = raw.substringAfterLast("<asr_text>", "").substringBefore("<|im_end|>").substringBefore("<|endoftext|>").trim()
            if (text.isNotEmpty()) transcripts.add(text)
            onProgress((chunk+1).toFloat()/chunks)
            Log.i("QwenAsr", "model=${model.architecture} chunk=${chunk+1}/$chunks")
        }
        require(transcripts.isNotEmpty()) { "Qwen returned no speech transcript" }
        transcripts.joinToString(" ")
    }
    override fun cancel() {
        cancelled = true
        synchronized(conversationLock) { activeConversation?.let { runCatching { it.cancelProcess() } } }
    }
    override fun close() { cancel(); worker.execute { dispose() } }
    private fun dispose() {
        engine?.close(); engine = null
        tensorSession?.close(); tensorSession = null
        preparedId = null
    }
    private fun writeWav(file: File, pcm: FloatArray, offset: Int, count: Int) {
        val bytes = ByteBuffer.allocate(44+count*2).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()).putInt(36+count*2).put("WAVEfmt ".toByteArray())
        bytes.putInt(16).putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16)
        bytes.put("data".toByteArray()).putInt(count*2)
        for (i in 0 until count) bytes.putShort((pcm[offset+i].coerceIn(-1f,1f)*32767).toInt().toShort())
        file.writeBytes(bytes.array())
    }

    private class CachedSession(directory: File) : AutoCloseable {
        private val environment = Environment.create()
        private val models = ArrayList<CompiledModel>()
        private val buffers = ArrayList<TensorBuffer>()
        private lateinit var encoder: CompiledModel
        private lateinit var embedder: CompiledModel
        private lateinit var lm: CompiledModel
        private lateinit var encIn: List<TensorBuffer>
        private lateinit var encOut: List<TensorBuffer>
        private lateinit var embIn: List<TensorBuffer>
        private lateinit var embOut: List<TensorBuffer>
        private lateinit var preIn: List<TensorBuffer>
        private lateinit var preOut: List<TensorBuffer>
        private lateinit var decIn: List<TensorBuffer>
        private lateinit var decOut: List<TensorBuffer>
        private val tokens = arrayOfNulls<String>(151936)
        private val vocab = HashMap<String,Int>()
        private val byteDecoder = IntArray(512) { -1 }
        init {
            try {
                fun model(name: String) = CompiledModel.create(File(directory,"$name.tflite").absolutePath,
                    CompiledModel.Options(Accelerator.CPU),environment).also { models.add(it) }
                fun inputs(model: CompiledModel,signature: String) = model.createInputBuffers(signature).also { buffers.addAll(it) }
                fun outputs(model: CompiledModel,signature: String) = model.createOutputBuffers(signature).also { buffers.addAll(it) }
                encoder=model("tf_lite_audio_encoder_hw"); embedder=model("tf_lite_embedder"); lm=model("tf_lite_prefill_decode")
                encIn=inputs(encoder,"encode"); encOut=outputs(encoder,"encode")
                embIn=inputs(embedder,"embedder"); embOut=outputs(embedder,"embedder")
                preIn=inputs(lm,"prefill_128"); preOut=outputs(lm,"prefill_128")
                decIn=inputs(lm,"decode"); decOut=outputs(lm,"decode")
                require(encIn.size==1 && encOut.size==1 && embIn.size==1 && embOut.size==1 &&
                    preIn.size==59 && preOut.size==56 && decIn.size==59 && decOut.size==57) { "Unsupported Qwen cached-decoder contract" }
                val json = JSONObject(File(directory,"tokenizer.json").readText())
                val entries = json.getJSONObject("model").getJSONObject("vocab")
                entries.keys().forEach { token -> val id=entries.getInt(token); require(id in tokens.indices); tokens[id]=token; vocab[token]=id }
                val added = json.getJSONArray("added_tokens")
                for (i in 0 until added.length()) {
                    val token=added.getJSONObject(i); val id=token.getInt("id"); require(id in tokens.indices)
                    tokens[id]=token.getString("content"); vocab[token.getString("content")]=id
                }
                var extra = 256
                for (b in 0..255) {
                    val normal = b in 33..126 || b in 161..172 || b in 174..255
                    byteDecoder[if (normal) b else extra++] = b
                }
            } catch (error: Throwable) { close(); throw error }
        }
        fun transcribe(mel: FloatArray, language: LanguageOption, checkActive: () -> Unit): String {
            encIn[0].writeFloat(mel); encoder.run(encIn,encOut,"encode")
            checkActive()
            val encoded=encOut[0].readFloat(); require(encoded.size==73*1024)
            preIn[0].writeFloat(FloatArray(128*1024).also { encoded.copyInto(it,endIndex=72*1024) })
            preIn[1].writeInt(IntArray(128) { it })
            preIn[2].writeFloat(FloatArray(128*512) { if (it%512<=it/512) 0f else Float.NEGATIVE_INFINITY })
            val zero=FloatArray(8*512*128)
            for (slot in 3 until 59) preIn[slot].writeFloat(zero)
            lm.run(preIn,preOut,"prefill_128"); checkActive()
            for (slot in 0 until 56) decIn[slot+3].writeFloat(preOut[slot].readFloat())
            decIn[0].writeFloat(encoded.copyOfRange(72*1024,73*1024))
            val forced = if (language==LanguageOption.AUTO) emptyList() else listOf(
                vocab.getValue("language"),vocab.getValue("Ġ${language.label}"),151704)
            val generated=ArrayList<Int>()
            for (position in 72 until 328) {
                checkActive()
                decIn[1].writeInt(intArrayOf(position))
                decIn[2].writeFloat(FloatArray(512) { if (it<=position) 0f else Float.NEGATIVE_INFINITY })
                lm.run(decIn,decOut,"decode")
                val logits=decOut[56].readFloat()
                val token=if (generated.size<forced.size) forced[generated.size] else logits.indices.maxBy { logits[it] }
                generated.add(token)
                if (token==151643 || token==151645) break
                for (slot in 0 until 56) decIn[slot+3].writeFloat(decOut[slot].readFloat())
                embIn[0].writeInt(intArrayOf(token)); embedder.run(embIn,embOut,"embedder")
                decIn[0].writeFloat(embOut[0].readFloat())
                require(position<327) { "Qwen exceeded its output limit; this chunk could not be fully transcribed" }
            }
            val output=ByteArrayOutputStream()
            for (id in generated) for (c in requireNotNull(tokens[id])) {
                val b=if (c.code<byteDecoder.size) byteDecoder[c.code] else -1
                require(b>=0) { "Unsupported tokenizer character" }; output.write(b)
            }
            return output.toString("UTF-8")
        }
        override fun close() {
            buffers.asReversed().forEach { it.close() }; buffers.clear()
            models.asReversed().forEach { it.close() }; models.clear()
            environment.close()
        }
    }
}
