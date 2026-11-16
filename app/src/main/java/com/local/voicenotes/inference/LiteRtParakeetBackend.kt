package com.local.voicenotes.inference

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.Build
import android.os.SystemClock
import android.provider.DocumentsContract
import android.util.Log
import com.local.voicenotes.audio.ParakeetFeatureExtractor
import com.local.voicenotes.concurrent.runCatchingCancellable
import com.local.voicenotes.domain.ImportedModel
import com.local.voicenotes.domain.LanguageOption
import com.google.ai.edge.litert.CompiledModel
import com.local.voicenotes.model.ModelFileCache
import com.google.ai.edge.litert.TensorBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.MappedByteBuffer
import kotlin.coroutines.coroutineContext

class LiteRtParakeetBackend internal constructor(
    private val context: Context,
    private val environments: AcceleratorEnvironmentFactory<LiteRtEnvironment>
) : TranscriptionBackend {
    /** CPU models never construct the Qualcomm provider; NPU models are checked explicitly. */
    constructor(context: Context) : this(context, liteRtEnvironmentFactory(context))

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
        // Serialize native operations, including close(), so ViewModel disposal
        // cannot free a session while an inference call is still using it.
        // Shared by CPU and NPU sessions; named for the runtime, not an accelerator.
        private val inferenceExecutor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "ParakeetLiteRt")
        }
        private val inferenceDispatcher = inferenceExecutor.asCoroutineDispatcher()
    }

    /** Job of the transcription currently running on the NPU thread; cancelled by [cancel]. */
    @Volatile private var activeCall: Job? = null
    @Volatile private var activeSession: Session? = null
    @Volatile private var preparedModelId: String? = null
    @Volatile private var preparedSession: Session? = null
    @Volatile private var preparedModelPath: String? = null
    /** Source change marker observed when [preparedSession] was verified; null = unknown. */
    @Volatile private var preparedStamp: String? = null
    private val sessionLock = Any()
    private val assets by lazy { ParakeetAssets.load(context) }
    private val frontend by lazy { ParakeetFeatureExtractor(assets) }
    private val modelCache by lazy { ModelFileCache(File(context.noBackupFilesDir, "litert-models")) }

    override suspend fun validate(model: ImportedModel): Result<Unit> = withContext(Dispatchers.IO) {
        runCatchingCancellable {
            require(model.architecture == "parakeet-tdt-0.6b-v3") {
                "Select the stateful Parakeet TDT 0.6B v3 LiteRT model."
            }
            ExecutionTarget.forBackend(model.backend)
            openModel(model).let { mapped ->
                require(mapped.remaining() > 8 && mapped.get(4) == 'T'.code.toByte() &&
                    mapped.get(5) == 'F'.code.toByte() && mapped.get(6) == 'L'.code.toByte() &&
                    mapped.get(7) == '3'.code.toByte()) { "Invalid TFLite model header." }
            }
        }
    }

    override suspend fun prepare(model: ImportedModel): Result<Unit> =
        withContext(inferenceDispatcher) {
            runCatchingCancellable {
                validate(model).getOrThrow()
                // Preparation (selection, import, app start) re-verifies the source
                // content, so an edited model is never served from a stale session.
                val job = coroutineContext.job
                sessionFor(model, verifySource = true) { job.ensureActive() }
                Unit
            }
        }

    override suspend fun transcribe(
        model: ImportedModel,
        pcm16KhzMono: FloatArray,
        language: LanguageOption,
        onProgress: (Float) -> Unit
    ): String = withContext(inferenceDispatcher) {
        // Parakeet v3 identifies the language acoustically; the parameter is
        // retained for parity with the baseline UI and benchmark harness.
        @Suppress("UNUSED_VARIABLE") val selectedLanguage = language
        // Cancellation is per call: a cancel() aimed at an earlier run can never leak into this
        // one, and the job is cancelled with the caller's coroutine as well.
        val callJob = coroutineContext.job
        activeCall = callJob
        try {
            val totalStarted = SystemClock.elapsedRealtimeNanos()
            // A cheap provider stamp decides whether the prepared session may be reused
            // as-is; a changed or unavailable stamp forces a full content re-verification.
            val verify = sourceMayHaveChanged(model)
            val session = sessionFor(model, verifySource = verify) { callJob.ensureActive() }
            Log.i(TAG, "reusePreparedSession=${!verify} accelerator=${session.target.label} audioSamples=${pcm16KhzMono.size}")
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
        } finally {
            if (activeCall === callJob) activeCall = null
        }
    }

    private suspend fun checkActive() {
        coroutineContext.ensureActive()
    }

    private fun elapsedMillis(startedNanos: Long): Long =
        (SystemClock.elapsedRealtimeNanos() - startedNanos) / 1_000_000

    /**
     * Returns the session for [model]. Without [verifySource] an existing session for the
     * same model id is reused as-is (per-transcription fast path). With it, the source is
     * re-hashed and the session is only kept if it was built from the identical bytes.
     * A cached blob that would back a *new* session is re-hashed as well, so same-length
     * corruption of the private copy is detected instead of loaded.
     */
    private fun sessionFor(
        model: ImportedModel,
        verifySource: Boolean,
        checkCancelled: () -> Unit
    ): Session = synchronized(sessionLock) {
        val existing = preparedSession
        if (existing != null && preparedModelId == model.id && !verifySource) return@synchronized existing
        val modelFileStarted = SystemClock.elapsedRealtimeNanos()
        val stampBefore = sourceStamp(model)
        val trustedPath = if (existing != null && preparedModelId == model.id) preparedModelPath else null
        val modelFile = try {
            openModelFile(model, checkCancelled, trustedPath)
        } catch (error: Throwable) {
            // Failed or cancelled verification must not leave a possibly stale session
            // behind for the next transcription to reuse unchecked.
            existing?.close()
            preparedSession = null
            preparedModelId = null
            preparedModelPath = null
            preparedStamp = null
            throw error
        }
        val modelFileMillis = elapsedMillis(modelFileStarted)
        if (existing != null && preparedModelId == model.id && preparedModelPath == modelFile.path) {
            Log.i(TAG, "verified modelFile=${modelFileMillis}ms; reusing session model=${model.displayName}")
            modelFile.close() // the existing session keeps its own lease on the same blob
            preparedStamp = stampBefore
            return@synchronized existing
        }
        existing?.close()
        preparedSession = null
        preparedModelId = null
        preparedModelPath = null
        preparedStamp = null
        val sessionStarted = SystemClock.elapsedRealtimeNanos()
        // Decided by the model's backend only. NPU failures are reported as NPU
        // failures; there is deliberately no automatic CPU fallback.
        val target = ExecutionTarget.forBackend(model.backend)
        val created = try {
            Session(environments, modelFile, target)
        } catch (error: Throwable) {
            modelFile.close()
            // LinkageError (e.g. UnsatisfiedLinkError from CompiledModel.create) also gets
            // accelerator-specific diagnostics; fatal VM errors and cancellation propagate.
            if (error is VirtualMachineError || error is CancellationException ||
                (error !is Exception && error !is LinkageError)) throw error
            Log.e(TAG, "${target.label} preparation failed: soc=${Build.SOC_MODEL} model=${model.displayName}", error)
            throw IllegalStateException(
                PreparationDiagnostics.message(
                    target, model.displayName, Build.SOC_MANUFACTURER, Build.SOC_MODEL, error
                ),
                error
            )
        }
        val sessionMillis = elapsedMillis(sessionStarted)
        preparedModelId = model.id
        preparedModelPath = modelFile.path
        preparedStamp = stampBefore
        preparedSession = created
        Log.i(TAG, "prepared accelerator=${target.label} modelFile=${modelFileMillis}ms session=${sessionMillis}ms model=${model.displayName}")
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
        activeCall?.cancel(CancellationException("Transcription cancelled."))
    }

    /**
     * Releases the session on the NPU thread, after any inference already running there has
     * stopped. Work queued later (for example a subsequent prepare) creates a new session.
     */
    override fun close() {
        cancel()
        inferenceExecutor.execute { closeSession() }
    }

    private fun closeSession() {
        val active = activeSession
        activeSession = null
        synchronized(sessionLock) {
            if (preparedSession !== active) active?.close()
            preparedSession?.close()
            preparedSession = null
            preparedModelId = null
            preparedModelPath = null
            preparedStamp = null
        }
    }

    /** Cheap change marker of the model source: provider size + last-modified, or file stat. */
    private fun sourceStamp(model: ImportedModel): String? {
        val uri = Uri.parse(model.path)
        return if (uri.scheme == "content") {
            SafModelSource(model, uri).stamp()
        } else {
            File(model.path).takeIf { it.isFile }?.let { "${it.length()}:${it.lastModified()}" }
        }
    }

    /** True when the prepared session cannot be trusted without re-hashing the source. */
    private fun sourceMayHaveChanged(model: ImportedModel): Boolean {
        if (preparedModelId != model.id) return true
        val prepared = preparedStamp ?: return true
        return sourceStamp(model) != prepared
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

    private fun openModelFile(
        model: ImportedModel,
        checkCancelled: () -> Unit,
        trustedBlobPath: String?
    ): ModelFile {
        val uri = Uri.parse(model.path)
        return if (uri.scheme == "content") {
            // CompiledModel's native loader requires a regular filesystem
            // path; unlike Interpreter it cannot consume a SAF descriptor.
            // The cache is content-addressed: the source is hashed on every call.
            val lease = modelCache.acquire(SafModelSource(model, uri), trustedBlobPath, checkCancelled)
            ModelFile(lease.file.absolutePath, null, lease)
        } else {
            ModelFile(File(model.path).absolutePath, null, null)
        }
    }

    private inner class SafModelSource(model: ImportedModel, private val uri: Uri) : ModelFileCache.Source {
        override val key: String = model.path
        override val expectedSize: Long = model.sizeBytes

        override fun open(): InputStream =
            context.contentResolver.openInputStream(uri) ?: error("The selected model cannot be opened.")

        /** Provider size + last-modified, when exposed; null means no torn-copy hint. */
        override fun stamp(): String? = runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED),
                null, null, null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val size = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                val modified = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                if (modified < 0 || cursor.isNull(modified)) return@use null
                val length = if (size >= 0 && !cursor.isNull(size)) cursor.getLong(size) else -1L
                "${length}:${cursor.getLong(modified)}"
            }
        }.getOrNull()
    }

    private data class ModelFile(
        val path: String,
        val descriptor: ParcelFileDescriptor?,
        /** Keeps the cached blob from being cleaned up while the session uses it. */
        val cacheLease: ModelFileCache.Lease?
    ) : AutoCloseable {
        override fun close() {
            try {
                descriptor?.close()
            } finally {
                cacheLease?.close()
            }
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
        environments: AcceleratorEnvironmentFactory<LiteRtEnvironment>,
        private val modelFile: ModelFile,
        val target: ExecutionTarget
    ) : AutoCloseable {
        private lateinit var environment: LiteRtEnvironment
        private lateinit var model: CompiledModel
        private var encodeInputs: List<TensorBuffer> = emptyList()
        private var encodeOutputs: List<TensorBuffer> = emptyList()
        private var stepInputs: List<TensorBuffer> = emptyList()
        private var stepOutputs: List<TensorBuffer> = emptyList()
        private var closed = false

        init {
            try {
                // Verified for exactly this target; CPU never touches the Qualcomm provider.
                environment = environments.create(target)
                model = CompiledModel.create(
                    modelFile.path,
                    CompiledModel.Options(target.toLiteRtAccelerator()),
                    environment.environment
                )
                encodeInputs = model.createInputBuffers("encode")
                encodeOutputs = model.createOutputBuffers("encode")
                stepInputs = model.createInputBuffers("decode_1")
                stepOutputs = model.createOutputBuffers("decode_1")
                require(encodeInputs.size == 1 && encodeOutputs.size == 1 &&
                    stepInputs.size == 4 && stepOutputs.size == 3) {
                    "Unexpected stateful Parakeet signature contract."
                }
            } catch (error: Throwable) {
                close()
                throw error
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
            if (closed) return
            closed = true
            (encodeInputs + encodeOutputs + stepInputs + stepOutputs).forEach {
                runCatching { it.close() }
            }
            if (::model.isInitialized) runCatching { model.close() }
            if (::environment.isInitialized) runCatching { environment.close() }
            runCatching { modelFile.close() }
        }

        private fun zeroState(): FloatArray = FloatArray(STATE_VALUES)
    }
}
