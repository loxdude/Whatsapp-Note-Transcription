package com.local.voicenotes.inference

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import com.local.voicenotes.audio.ParakeetFeatureExtractor
import com.local.voicenotes.domain.ImportedModel
import com.local.voicenotes.domain.LanguageOption
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.BuiltinNpuAcceleratorProvider
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.MappedByteBuffer
import kotlin.coroutines.coroutineContext

class LiteRtParakeetBackend(private val context: Context) : TranscriptionBackend {
    companion object {
        private const val TAG = "LiteRtParakeet"
        private const val BLANK = 8192
        private const val VOCAB_WITH_BLANK = 8193
        private const val LOGITS = 8198
        private const val ENCODER_FRAMES = 63
        private const val ENCODER_VALUES = 1024 * ENCODER_FRAMES
        private const val STATE_VALUES = 2 * 640
        private const val MAX_SYMBOLS_PER_FRAME = 10
        private val BYTE_TOKEN = Regex("""<0x([0-9A-Fa-f]{2})>""")
        private val WHITESPACE = Regex("""\s+""")
    }

    @Volatile private var cancelled = false
    @Volatile private var activeSession: Session? = null
    @Volatile private var preparedModelId: String? = null
    @Volatile private var preparedSession: Session? = null
    private val sessionLock = Any()
    private val assets by lazy { ParakeetAssets.load(context) }
    private val frontend by lazy { ParakeetFeatureExtractor(assets) }

    override suspend fun validate(model: ImportedModel): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(model.architecture == "parakeet-tdt-0.6b-v3") {
                "Select the stateful Parakeet TDT 0.6B v3 LiteRT model."
            }
            require(model.backend.startsWith("litert-qnn")) { "This model is not configured for QNN." }
            openModel(model).let { mapped ->
                require(mapped.remaining() > 8 && mapped.get(4) == 'T'.code.toByte() &&
                    mapped.get(5) == 'F'.code.toByte() && mapped.get(6) == 'L'.code.toByte() &&
                    mapped.get(7) == '3'.code.toByte()) { "Invalid TFLite model header." }
            }
        }
    }

    override suspend fun prepare(model: ImportedModel): Result<Unit> =
        withContext(Dispatchers.Default) {
            runCatching {
                validate(model).getOrThrow()
                sessionFor(model)
                Unit
            }
        }

    override suspend fun transcribe(
        model: ImportedModel,
        pcm16KhzMono: FloatArray,
        language: LanguageOption,
        onProgress: (Float) -> Unit
    ): String = withContext(Dispatchers.Default) {
        // Parakeet v3 identifies the language acoustically; the parameter is
        // retained for parity with the baseline UI and benchmark harness.
        @Suppress("UNUSED_VARIABLE") val selectedLanguage = language
        cancelled = false
        val totalStarted = SystemClock.elapsedRealtimeNanos()
        val session = sessionFor(model)
        Log.i(TAG, "reusePreparedSession=true audioSamples=${pcm16KhzMono.size}")
        activeSession = session
        try {
            val emitted = ArrayList<Int>()
            var predictor: Predictor? = null
            var frontendNanos = 0L
            var encoderNanos = 0L
            var decoderNanos = 0L
            var decoderCalls = 0
            val chunkCount = (pcm16KhzMono.size + ParakeetFeatureExtractor.CHUNK_SAMPLES - 1) /
                ParakeetFeatureExtractor.CHUNK_SAMPLES
            repeat(chunkCount) { chunk ->
                checkActive()
                val frontendStarted = SystemClock.elapsedRealtimeNanos()
                val features = frontend.extract(
                    pcm16KhzMono,
                    chunk * ParakeetFeatureExtractor.CHUNK_SAMPLES
                )
                frontendNanos += SystemClock.elapsedRealtimeNanos() - frontendStarted
                checkActive()
                val encoderStarted = SystemClock.elapsedRealtimeNanos()
                val encoder = session.encode(features)
                encoderNanos += SystemClock.elapsedRealtimeNanos() - encoderStarted
                val initialDecoderStarted = SystemClock.elapsedRealtimeNanos()
                predictor = session.bindEncoder(encoder, predictor)
                decoderNanos += SystemClock.elapsedRealtimeNanos() - initialDecoderStarted
                decoderCalls++
                var frame = 0
                var inner = 0
                while (frame < ENCODER_FRAMES) {
                    checkActive()
                    val logits = predictor!!.logits
                    val row = frame * LOGITS
                    var token = 0
                    var tokenValue = logits[row]
                    for (id in 1 until VOCAB_WITH_BLANK) {
                        val value = logits[row + id]
                        if (value > tokenValue) {
                            tokenValue = value
                            token = id
                        }
                    }
                    var duration = 0
                    var durationValue = logits[row + VOCAB_WITH_BLANK]
                    for (id in 1 until 5) {
                        val value = logits[row + VOCAB_WITH_BLANK + id]
                        if (value > durationValue) {
                            durationValue = value
                            duration = id
                        }
                    }
                    if (token == BLANK) {
                        if (duration > 0) {
                            frame += duration
                            inner = 0
                        } else {
                            inner++
                            if (inner >= MAX_SYMBOLS_PER_FRAME) {
                                frame++
                                inner = 0
                            }
                        }
                    } else {
                        emitted += token
                        val decoderStarted = SystemClock.elapsedRealtimeNanos()
                        predictor = session.advance(encoder, token, predictor!!)
                        decoderNanos += SystemClock.elapsedRealtimeNanos() - decoderStarted
                        decoderCalls++
                        if (duration > 0) {
                            frame += duration
                            inner = 0
                        } else {
                            inner++
                            if (inner >= MAX_SYMBOLS_PER_FRAME) {
                                frame++
                                inner = 0
                            }
                        }
                    }
                }
                onProgress((chunk + 1f) / chunkCount)
            }
            detokenize(emitted).also {
                Log.i(
                    TAG,
                    "chunks=$chunkCount frontend=${frontendNanos / 1_000_000}ms " +
                        "encode=${encoderNanos / 1_000_000}ms decode=${decoderNanos / 1_000_000}ms " +
                        "decodeCalls=$decoderCalls tokens=${emitted.size} total=${elapsedMillis(totalStarted)}ms"
                )
            }
        } finally {
            activeSession = null
        }
    }

    private suspend fun checkActive() {
        coroutineContext.ensureActive()
        if (cancelled) throw CancellationException("Transcription cancelled.")
    }

    private fun elapsedMillis(startedNanos: Long): Long =
        (SystemClock.elapsedRealtimeNanos() - startedNanos) / 1_000_000

    private fun sessionFor(model: ImportedModel): Session = synchronized(sessionLock) {
        preparedSession?.let { existing ->
            if (preparedModelId == model.id) return@synchronized existing
            existing.close()
        }
        val modelFileStarted = SystemClock.elapsedRealtimeNanos()
        val modelFile = openModelFile(model)
        val modelFileMillis = elapsedMillis(modelFileStarted)
        val sessionStarted = SystemClock.elapsedRealtimeNanos()
        val created = Session(context, modelFile)
        val sessionMillis = elapsedMillis(sessionStarted)
        preparedModelId = model.id
        preparedSession = created
        Log.i(TAG, "prepared modelFile=${modelFileMillis}ms session=${sessionMillis}ms model=${model.displayName}")
        created
    }

    private fun detokenize(ids: List<Int>): String {
        val output = StringBuilder()
        val bytes = ArrayList<Byte>()
        fun flushBytes() {
            if (bytes.isNotEmpty()) {
                output.append(bytes.toByteArray().toString(Charsets.UTF_8))
                bytes.clear()
            }
        }
        for (id in ids) {
            if (id !in assets.vocabulary.indices || id < 274) continue
            val piece = assets.vocabulary[id]
            val match = BYTE_TOKEN.matchEntire(piece)
            if (match != null) {
                bytes += match.groupValues[1].toInt(16).toByte()
            } else {
                flushBytes()
                if (!piece.startsWith("<") || !piece.endsWith(">")) output.append(piece)
            }
        }
        flushBytes()
        return output.toString().replace('\u2581', ' ').replace(WHITESPACE, " ").trim()
    }

    override fun cancel() {
        cancelled = true
    }

    override fun close() {
        cancelled = true
        val active = activeSession
        activeSession = null
        synchronized(sessionLock) {
            if (preparedSession !== active) active?.close()
            preparedSession?.close()
            preparedSession = null
            preparedModelId = null
        }
    }

    private fun openModel(model: ImportedModel): MappedByteBuffer {
        val uri = Uri.parse(model.path)
        return if (uri.scheme == "content") {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                require(descriptor.length > 0) { "The selected model does not expose a seekable file." }
                FileInputStream(descriptor.fileDescriptor).channel.use { channel ->
                    channel.map(
                        java.nio.channels.FileChannel.MapMode.READ_ONLY,
                        descriptor.startOffset,
                        descriptor.length
                    )
                }
            } ?: error("The selected model cannot be opened.")
        } else {
            FileInputStream(File(model.path)).channel.use { channel ->
                channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, channel.size())
            }
        }
    }

    private fun openModelFile(model: ImportedModel): ModelFile {
        val uri = Uri.parse(model.path)
        return if (uri.scheme == "content") {
            // CompiledModel's native loader requires a regular filesystem
            // path; unlike Interpreter it cannot consume a SAF descriptor.
            val directory = File(context.noBackupFilesDir, "litert-models").apply { mkdirs() }
            val target = File(directory, "${model.id}.tflite")
            if (!target.isFile || target.length() != model.sizeBytes) {
                val temporary = File(directory, "${model.id}.partial")
                if (temporary.exists()) temporary.delete()
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(temporary).use { output ->
                        input.copyTo(output, 8 * 1024 * 1024)
                        output.fd.sync()
                    }
                } ?: error("The selected model cannot be opened.")
                require(temporary.length() == model.sizeBytes) {
                    "The cached model copy is incomplete."
                }
                require(temporary.renameTo(target)) { "Could not finalize the local model cache." }
            }
            ModelFile(target.absolutePath, null)
        } else {
            ModelFile(File(model.path).absolutePath, null)
        }
    }

    private data class ModelFile(
        val path: String,
        val descriptor: ParcelFileDescriptor?
    ) : AutoCloseable {
        override fun close() {
            descriptor?.close()
        }
    }

    private data class Predictor(
        val label: Int,
        val inputH: FloatArray,
        val inputC: FloatArray,
        val stateH: FloatArray,
        val stateC: FloatArray,
        val logits: FloatArray
    )

    private class Session(
        context: Context,
        private val modelFile: ModelFile
    ) : AutoCloseable {
        private val environment: Environment
        private val model: CompiledModel
        private val encodeInputs: List<TensorBuffer>
        private val encodeOutputs: List<TensorBuffer>
        private val stepInputs: List<TensorBuffer>
        private val stepOutputs: List<TensorBuffer>

        init {
            val provider = BuiltinNpuAcceleratorProvider(context)
            require(provider.isDeviceSupported()) {
                "LiteRT does not recognize this device as a supported Qualcomm NPU."
            }
            require(provider.isLibraryReady()) {
                "The LiteRT Qualcomm dispatch runtime is not available."
            }
            environment = Environment.create(provider)
            require(Accelerator.NPU in environment.getAvailableAccelerators()) {
                "LiteRT could not register the Qualcomm NPU accelerator."
            }
            model = CompiledModel.create(
                modelFile.path,
                CompiledModel.Options(Accelerator.NPU),
                environment
            )
            encodeInputs = model.createInputBuffers("encode")
            encodeOutputs = model.createOutputBuffers("encode")
            stepInputs = model.createInputBuffers("decode_1")
            stepOutputs = model.createOutputBuffers("decode_1")
            require(encodeInputs.size == 1 && encodeOutputs.size == 1 &&
                stepInputs.size == 4 && stepOutputs.size == 3) {
                "Unexpected stateful Parakeet signature contract."
            }
        }

        fun encode(features: FloatArray): FloatArray {
            encodeInputs[0].writeFloat(features)
            model.run(encodeInputs, encodeOutputs, "encode")
            return encodeOutputs[0].readFloat().also {
                require(it.size == ENCODER_VALUES) { "Unexpected Parakeet encoder output size." }
            }
        }

        fun bindEncoder(encoder: FloatArray, previous: Predictor?): Predictor =
            if (previous == null) {
                step(encoder, BLANK, zeroState(), zeroState())
            } else {
                step(encoder, previous.label, previous.inputH, previous.inputC)
            }

        fun advance(encoder: FloatArray, token: Int, previous: Predictor): Predictor =
            step(encoder, token, previous.stateH, previous.stateC)

        private fun step(
            encoder: FloatArray,
            label: Int,
            inputH: FloatArray,
            inputC: FloatArray
        ): Predictor {
            stepInputs[0].writeFloat(encoder)
            stepInputs[1].writeInt(intArrayOf(label))
            stepInputs[2].writeFloat(inputH)
            stepInputs[3].writeFloat(inputC)
            model.run(stepInputs, stepOutputs, "decode_1")
            val logits = stepOutputs[0].readFloat()
            val outputH = stepOutputs[1].readFloat()
            val outputC = stepOutputs[2].readFloat()
            require(logits.size == ENCODER_FRAMES * LOGITS &&
                outputH.size == STATE_VALUES && outputC.size == STATE_VALUES) {
                "Unexpected Parakeet decoder output size."
            }
            return Predictor(label, inputH, inputC, outputH, outputC, logits)
        }

        override fun close() {
            (encodeInputs + encodeOutputs + stepInputs + stepOutputs).forEach {
                runCatching { it.close() }
            }
            runCatching { model.close() }
            runCatching { environment.close() }
            runCatching { modelFile.close() }
        }

        private fun zeroState(): FloatArray = FloatArray(STATE_VALUES)
    }
}
