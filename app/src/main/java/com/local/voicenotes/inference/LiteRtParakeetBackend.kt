package com.local.voicenotes.inference

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.Build
import android.os.SystemClock
import android.provider.DocumentsContract
import android.util.Log
import com.local.voicenotes.concurrent.runCatchingCancellable
import com.local.voicenotes.domain.ImportedModel
import com.local.voicenotes.domain.LanguageOption
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.local.voicenotes.model.ParakeetUltraModels
import com.local.voicenotes.model.BackgroundVerification
import com.local.voicenotes.model.ModelFileCache
import com.local.voicenotes.model.VerifiedResultGate
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
import kotlin.math.roundToInt

/**
 * Parakeet Ultra on the CPU through LiteRT: the imported i8 encoder plus the bundled mel and
 * decoder graphs. The only on-device backend (Parakeet TDT v3 and the Qualcomm NPU path were
 * removed).
 */
class LiteRtParakeetBackend(private val context: Context) : TranscriptionBackend {

    companion object {
        private const val TAG = "LiteRtParakeet"
        private const val ULTRA_HIDDEN = 640
        private const val ULTRA_STATE_VALUES = 2 * ULTRA_HIDDEN
        private const val ULTRA_MEL_BINS = 128
        private const val SERVING_DEFAULT = "serving_default"
        // Serialize native operations, including close(), so ViewModel disposal
        // cannot free a session while an inference call is still using it.
        private val inferenceExecutor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "ParakeetLiteRt")
        }
        private val inferenceDispatcher = inferenceExecutor.asCoroutineDispatcher()
        // Background integrity checks of fast-path model files. Never the inference thread;
        // cached so the source and the blob can be hashed concurrently.
        private val verificationExecutor = Executors.newCachedThreadPool { task ->
            Thread(task, "ParakeetVerify").apply { isDaemon = true }
        }
    }

    /** Job of the transcription currently running on the inference thread; cancelled by [cancel]. */
    @Volatile private var activeCall: Job? = null
    @Volatile private var activeSession: Session? = null
    @Volatile private var preparedModelId: String? = null
    @Volatile private var preparedSession: Session? = null
    @Volatile private var preparedModelPath: String? = null
    /** Source change marker observed when [preparedSession] was verified; null = unknown. */
    @Volatile private var preparedStamp: String? = null
    /**
     * Non-null while [preparedSession] was built by the cold-start fast path from a blob whose
     * bytes have not been verified in this process yet. Every result of such a session is
     * gated on [preparedVerification] (see [VerifiedResultGate]).
     */
    @Volatile private var preparedRecorded: ModelFileCache.RecordedBlob? = null
    @Volatile private var preparedVerification: BackgroundVerification? = null
    private val sessionLock = Any()
    private val vocabulary by lazy {
        ParakeetVocabulary.read(context.assets.open(ParakeetVocabulary.ASSET))
    }
    private val modelCache by lazy { ModelFileCache(File(context.noBackupFilesDir, "litert-models")) }
    /** Fallback copies of bundled graphs, only used if LiteRT cannot load them from assets. */
    private val bundledFiles by lazy { BundledModelFiles(File(context.noBackupFilesDir, "bundled-models")) }

    override suspend fun validate(model: ImportedModel): Result<Unit> = withContext(Dispatchers.IO) {
        runCatchingCancellable {
            require(!ParakeetUltraModels.isReplacedV3(model)) { ParakeetUltraModels.V3_REPLACED_NOTE }
            require(model.architecture == ParakeetUltraModels.ARCHITECTURE &&
                model.backend == ParakeetUltraModels.CPU_BACKEND) {
                "Select a Parakeet Ultra encoder (parakeet_ultra_encoder_{10|20|30}s_i8.tflite)."
            }
            val encoder = requireNotNull(ParakeetUltraModels.parseEncoder(model.displayName)) {
                "Select a parakeet_ultra_encoder_{10|20|30}s_i8.tflite encoder."
            }
            require(encoder.isCpu) { ParakeetUltraModels.F16_NOTE }
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
                sessionFor(model, verifySource = true, allowFastPath = true) { job.ensureActive() }
                Unit
            }
        }

    override suspend fun transcribe(
        model: ImportedModel,
        pcm16KhzMono: FloatArray,
        language: LanguageOption,
        onProgress: (Float) -> Unit
    ): String = withContext(inferenceDispatcher) {
        // Parakeet identifies the language acoustically; the parameter is
        // retained for parity with the baseline UI and benchmark harness.
        @Suppress("UNUSED_VARIABLE") val selectedLanguage = language
        // Cancellation is per call: a cancel() aimed at an earlier run can never leak into this
        // one, and the job is cancelled with the caller's coroutine as well.
        val callJob = coroutineContext.job
        activeCall = callJob
        try {
            val totalStarted = SystemClock.elapsedRealtimeNanos()
            // Verification gating the session of the first attempt; consulted if it throws.
            var firstVerification: BackgroundVerification? = null
            // A result is only returned once the bytes it was computed from are verified; a
            // failed background verification discards the session and re-runs once on a
            // session from the full (re-hashing) path. An error thrown while the check is
            // pending is only reported if the bytes turn out to be verified.
            VerifiedResultGate.run(
                checkCancelled = { callJob.ensureActive() },
                onUnverified = { outcome ->
                    Log.w(TAG, "model verification outcome=$outcome; discarding session and re-running on the verified path")
                    synchronized(sessionLock) { if (preparedRecorded != null) discardPreparedLocked() }
                },
                onAwaited = { outcome, waitMillis ->
                    Log.i(TAG, "result gated on verification outcome=$outcome wait=${waitMillis}ms")
                },
                pendingVerification = { firstVerification }
            ) { retry ->
                // A cheap provider stamp decides whether the prepared session may be reused
                // as-is; a changed or unavailable stamp forces a full content re-verification.
                val verify = retry || sourceMayHaveChanged(model)
                val prepared = sessionFor(model, verifySource = verify, allowFastPath = !retry) { callJob.ensureActive() }
                if (!retry) firstVerification = prepared.verification
                Log.i(
                    TAG,
                    "reusePreparedSession=${!verify} retry=$retry verificationPending=${prepared.verification != null} " +
                        "accelerator=CPU audioSamples=${pcm16KhzMono.size}"
                )
                VerifiedResultGate.Attempt(infer(prepared.session, pcm16KhzMono, onProgress), prepared.verification)
            }.also { Log.i(TAG, "transcribe total=${elapsedMillis(totalStarted)}ms") }
        } catch (cancellation: CancellationException) {
            // Stops a pending check and releases its lease; the next use restarts it.
            preparedVerification?.cancel()
            throw cancellation
        } finally {
            if (activeCall === callJob) activeCall = null
        }
    }

    private suspend fun infer(session: Session, pcm16KhzMono: FloatArray, onProgress: (Float) -> Unit): String {
        activeSession = session
        try {
            val job = coroutineContext.job
            val checkCancelled = { job.ensureActive() }
            return inferUltra(session, pcm16KhzMono, onProgress, checkCancelled)
        } finally {
            activeSession = null
        }
    }

    /**
     * Parakeet Ultra: overlapping windows (hop = window - 2 s), each through the bundled mel
     * graph, the imported encoder and the greedy TDT loop from a fresh decoder state; tokens are
     * kept by the window whose own region contains them (see [UltraWindows]).
     */
    private fun inferUltra(
        session: Session,
        pcm16KhzMono: FloatArray,
        onProgress: (Float) -> Unit,
        checkCancelled: () -> Unit
    ): String {
        val totalStarted = SystemClock.elapsedRealtimeNanos()
        val windows = UltraWindows.plan(pcm16KhzMono.size, session.windowSeconds)
        var melNanos = 0L
        var encoderNanos = 0L
        var decoderNanos = 0L
        var validFramesTotal = 0
        val emissions = ArrayList<List<UltraEmission>>(windows.size)
        windows.forEachIndexed { index, window ->
            checkCancelled()
            if (window.lengthSamples < UltraWindows.MIN_WINDOW_SAMPLES) {
                emissions.add(emptyList())
            } else {
                val melStarted = SystemClock.elapsedRealtimeNanos()
                val mel = session.mel(pcm16KhzMono, window.startSample, window.lengthSamples)
                melNanos += SystemClock.elapsedRealtimeNanos() - melStarted
                checkCancelled()
                val encoderStarted = SystemClock.elapsedRealtimeNanos()
                val encoded = session.encode(mel)
                encoderNanos += SystemClock.elapsedRealtimeNanos() - encoderStarted
                validFramesTotal += mel.validFrames
                val decoderStarted = SystemClock.elapsedRealtimeNanos()
                emissions.add(UltraTdt.decode(mel.validFrames, session.network(encoded), checkCancelled))
                decoderNanos += SystemClock.elapsedRealtimeNanos() - decoderStarted
            }
            onProgress((index + 1f) / windows.size)
        }
        val tokens = UltraWindows.stitch(windows, emissions)
        return vocabulary.detokenize(tokens).also {
            Log.i(
                TAG,
                "ultra windows=${windows.size} window=${session.windowSeconds}s mel=${melNanos / 1_000_000}ms " +
                    "encode=${encoderNanos / 1_000_000}ms decode=${decoderNanos / 1_000_000}ms " +
                    "frames=$validFramesTotal tokens=${tokens.size} total=${elapsedMillis(totalStarted)}ms"
            )
        }
    }

    private fun elapsedMillis(startedNanos: Long): Long =
        (SystemClock.elapsedRealtimeNanos() - startedNanos) / 1_000_000

    private class Prepared(
        val session: Session,
        /** Non-null when [session]'s bytes are not verified yet: results must await it. */
        val verification: BackgroundVerification?
    )

    /**
     * Returns the session for [model]. Without [verifySource] an existing session for the
     * same model id is reused as-is (per-transcription fast path). With it, the source is
     * re-hashed and the session is only kept if it was built from the identical bytes.
     * A cached blob that would back a *new* session is re-hashed as well, so same-length
     * corruption of the private copy is detected instead of loaded.
     *
     * Cold-start fast path ([allowFastPath], no live session for the model): when the cache's
     * verified record still matches the provider stamp and the blob's length/mtime, the
     * session is built without hashing and a background verification starts in parallel;
     * the returned [Prepared.verification] must gate every result.
     */
    private fun sessionFor(
        model: ImportedModel,
        verifySource: Boolean,
        allowFastPath: Boolean,
        checkCancelled: () -> Unit
    ): Prepared = synchronized(sessionLock) {
        val existing = preparedSession
        val sameModel = existing != null && preparedModelId == model.id
        val recorded = preparedRecorded
        if (existing != null && sameModel && recorded != null) {
            // Unverified fast-path session: reusable while its check has not failed and the
            // stamp still equals the recorded one (the pending check re-hashes the source).
            val failed = preparedVerification?.result == BackgroundVerification.Outcome.FAILED
            val stampMatches = !verifySource || (allowFastPath && sourceStamp(model) == recorded.stamp)
            if (!failed && stampMatches) return@synchronized Prepared(existing, pendingVerificationLocked(recorded))
            // Never reuse it after a full resolution: a corrupt blob is replaced under the same
            // path, so path equality would not prove what this session loaded.
            discardPreparedLocked()
        } else if (existing != null && sameModel && !verifySource) {
            return@synchronized Prepared(existing, null)
        }
        if (allowFastPath && !(preparedSession != null && preparedModelId == model.id)) {
            fastPathSession(model, checkCancelled)?.let { return@synchronized it }
        }
        val current = preparedSession
        val modelFileStarted = SystemClock.elapsedRealtimeNanos()
        val stampBefore = sourceStamp(model)
        val trustedPath = if (current != null && preparedModelId == model.id) preparedModelPath else null
        val modelFile = try {
            openModelFile(model, checkCancelled, trustedPath)
        } catch (error: Throwable) {
            // Failed or cancelled verification must not leave a possibly stale session
            // behind for the next transcription to reuse unchecked.
            discardPreparedLocked()
            throw error
        }
        val modelFileMillis = elapsedMillis(modelFileStarted)
        if (current != null && preparedModelId == model.id && preparedModelPath == modelFile.path) {
            Log.i(TAG, "verified modelFile=${modelFileMillis}ms; reusing session model=${model.displayName}")
            modelFile.close() // the existing session keeps its own lease on the same blob
            preparedStamp = stampBefore
            return@synchronized Prepared(current, null)
        }
        discardPreparedLocked()
        val sessionStarted = SystemClock.elapsedRealtimeNanos()
        val created = createSession(model, modelFile)
        val sessionMillis = elapsedMillis(sessionStarted)
        preparedModelId = model.id
        preparedModelPath = modelFile.path
        preparedStamp = stampBefore
        preparedSession = created
        Log.i(
            TAG,
            "prepared accelerator=CPU modelFile=${modelFileMillis}ms session=${sessionMillis}ms " +
                "fastPath=false model=${model.displayName}"
        )
        Prepared(created, null)
    }

    /**
     * Builds a session on the recorded blob without hashing, or returns null to take the full
     * path. Session creation runs while the background verification hashes source and blob.
     * Residual risk (accepted, gated): a source rewritten with identical size AND identical
     * provider lastModified, or a blob corrupted with its length/mtime preserved, is loaded
     * unverified here and only caught by the background verification, which still gates
     * every result before it is returned.
     */
    private fun fastPathSession(model: ImportedModel, checkCancelled: () -> Unit): Prepared? {
        val uri = Uri.parse(model.path)
        if (uri.scheme != "content") return null
        val modelFileStarted = SystemClock.elapsedRealtimeNanos()
        val recorded = try {
            modelCache.acquireRecorded(SafModelSource(model, uri), checkCancelled)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.w(TAG, "fast path unavailable: ${error.message}")
            null
        } ?: return null
        val modelFileMillis = elapsedMillis(modelFileStarted)
        discardPreparedLocked()
        val verification = recorded.startVerification(verificationExecutor, ::onVerificationFinished)
        val modelFile = ModelFile(recorded.file.absolutePath, null, recorded.lease)
        val sessionStarted = SystemClock.elapsedRealtimeNanos()
        val created = try {
            createSession(model, modelFile)
        } catch (error: Throwable) {
            verification.cancel()
            if (error is VirtualMachineError || error is CancellationException) throw error
            // Possibly caused by unverified bytes: let the full path re-verify and retry.
            Log.w(TAG, "fast-path session failed; falling back to full verification", error)
            recorded.invalidateRecord()
            return null
        }
        val sessionMillis = elapsedMillis(sessionStarted)
        preparedModelId = model.id
        preparedModelPath = modelFile.path
        preparedStamp = recorded.stamp
        preparedSession = created
        preparedRecorded = recorded
        preparedVerification = verification
        Log.i(
            TAG,
            "prepared accelerator=CPU modelFile=${modelFileMillis}ms session=${sessionMillis}ms " +
                "fastPath=true model=${model.displayName}"
        )
        return Prepared(created, verification)
    }

    /** Closes [modelFile] on failure. */
    private fun createSession(model: ImportedModel, modelFile: ModelFile): Session {
        return try {
            val encoder = requireNotNull(ParakeetUltraModels.parseEncoder(model.displayName)) {
                "Not a Parakeet Ultra encoder: ${model.displayName}"
            }
            Session(modelFile, encoder.windowSeconds, ::createBundledModel)
        } catch (error: Throwable) {
            modelFile.close()
            // LinkageError (e.g. UnsatisfiedLinkError from CompiledModel.create) also gets
            // diagnostics; fatal VM errors and cancellation propagate.
            if (error is VirtualMachineError || error is CancellationException ||
                (error !is Exception && error !is LinkageError)) throw error
            Log.e(TAG, "CPU preparation failed: soc=${Build.SOC_MODEL} model=${model.displayName}", error)
            throw IllegalStateException(PreparationDiagnostics.message(model.displayName, error), error)
        }
    }

    /** The running check of the unverified session, restarted if a cancel stopped it. */
    private fun pendingVerificationLocked(recorded: ModelFileCache.RecordedBlob): BackgroundVerification {
        val current = preparedVerification
        if (current != null &&
            (!current.isCancelRequested || current.result == BackgroundVerification.Outcome.VERIFIED)
        ) return current
        return recorded.startVerification(verificationExecutor, ::onVerificationFinished)
            .also { preparedVerification = it }
    }

    private fun onVerificationFinished(
        verification: BackgroundVerification,
        outcome: BackgroundVerification.Outcome,
        millis: Long
    ) {
        Log.i(TAG, "background verification ok=${outcome == BackgroundVerification.Outcome.VERIFIED} outcome=$outcome in ${millis}ms")
        if (outcome != BackgroundVerification.Outcome.VERIFIED) return
        synchronized(sessionLock) {
            if (preparedVerification === verification) {
                // From now on the session counts as verified, exactly like a full-path one.
                preparedRecorded = null
                preparedVerification = null
            }
        }
    }

    /** Must hold [sessionLock]. Cancels a pending check and closes the prepared session. */
    private fun discardPreparedLocked() {
        preparedVerification?.cancel()
        preparedVerification = null
        preparedRecorded = null
        preparedSession?.close()
        preparedSession = null
        preparedModelId = null
        preparedModelPath = null
        preparedStamp = null
    }

    /**
     * Releases the private cache copies of model sources that will never be loaded again
     * (e.g. replaced Parakeet v3 models). Blocking file I/O; call off the main thread.
     */
    fun forgetCachedCopies(sourcePaths: Collection<String>) {
        modelCache.forget(sourcePaths)
    }

    override fun cancel() {
        activeCall?.cancel(CancellationException("Transcription cancelled."))
    }

    /**
     * Releases the session on the inference thread, after any inference already running there has
     * stopped. Work queued later (for example a subsequent prepare) creates a new session.
     */
    override fun close() {
        cancel()
        // Without the session lock (may be held for seconds on the inference thread): stops
        // the pending check now; closeSession() below cleans up the rest in order.
        preparedVerification?.cancel()
        inferenceExecutor.execute { closeSession() }
    }

    private fun closeSession() {
        val active = activeSession
        activeSession = null
        synchronized(sessionLock) {
            if (preparedSession !== active) active?.close()
            discardPreparedLocked()
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
            // The cache is content-addressed: the source is hashed on every call of this
            // full path (the cold-start fast path is fastPathSession()).
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

    /**
     * Creates a CPU model from a graph bundled in the APK's assets. LiteRT loads it from the
     * (uncompressed) asset directly; if that fails, the asset is extracted once to
     * noBackupFilesDir, verified by size and SHA-256, and loaded by path.
     */
    private fun createBundledModel(bundled: BundledModel, options: CompiledModel.Options, environment: Environment): CompiledModel =
        try {
            CompiledModel.create(context.assets, bundled.assetName, options, environment)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.w(TAG, "loading ${bundled.assetName} from assets failed; extracting it", error)
            val file = bundledFiles.materialize(bundled, { context.assets.open(bundled.assetName) })
            CompiledModel.create(file.absolutePath, options, environment)
        }

    /** Output of the mel graph for one window. */
    private class UltraMel(val features: FloatArray, val melMask: FloatArray, val validFrames: Int)

    /** Decoder state: dec_out of the last predict and its h/c outputs. */
    private class UltraState(val decOut: FloatArray, val h: FloatArray, val c: FloatArray)

    /**
     * A prepared Parakeet Ultra session on the CPU: the bundled mel graph of the imported
     * encoder's window size, the imported encoder (from [modelFile], i.e. the verified private
     * copy) and the bundled int8 decoder, all in one LiteRT environment, plus their buffers.
     * Only this one encoder is loaded. Closed on the inference thread only.
     */
    private class Session(
        private val modelFile: ModelFile,
        val windowSeconds: Int,
        createBundled: (BundledModel, CompiledModel.Options, Environment) -> CompiledModel
    ) : AutoCloseable {
        private val graphSamples = UltraWindows.graphSamples(windowSeconds)
        private val melFrames = graphSamples / 160
        private val encoderFrames = melFrames / 8
        private val models = ArrayList<CompiledModel>()
        private val buffers = ArrayList<TensorBuffer>()
        private var environment: Environment? = null
        private val mel: CompiledModel
        private val encoder: CompiledModel
        private val decoder: CompiledModel
        private val melInputs: Map<String, TensorBuffer>
        private val melOutputs: Map<String, TensorBuffer>
        private val encoderInputs: Map<String, TensorBuffer>
        private val encoderOutputs: Map<String, TensorBuffer>
        private val predictInputs: Map<String, TensorBuffer>
        private val predictOutputs: Map<String, TensorBuffer>
        private val jointInputs: Map<String, TensorBuffer>
        private val jointOutputs: Map<String, TensorBuffer>
        private val audio = FloatArray(graphSamples)
        private val frameRow = FloatArray(ULTRA_HIDDEN)
        private var closed = false

        init {
            try {
                val env = Environment.create().also { environment = it }
                val cores = Runtime.getRuntime().availableProcessors()
                // Threads as in the model card's runner: encoder 4, mel 2, decoder 1.
                mel = createBundled(ParakeetUltraBundle.mel(windowSeconds), cpuOptions(minOf(2, cores)), env).also { models += it }
                decoder = createBundled(ParakeetUltraBundle.decoder, cpuOptions(1), env).also { models += it }
                encoder = CompiledModel.create(modelFile.path, cpuOptions(minOf(4, cores)), env).also { models += it }
                melInputs = inputs(mel, SERVING_DEFAULT, "audio", "n_valid")
                melOutputs = outputs(mel, SERVING_DEFAULT, "features", "mel_mask", "frame_mask")
                encoderInputs = inputs(encoder, SERVING_DEFAULT, "features", "mel_mask")
                encoderOutputs = outputs(encoder, SERVING_DEFAULT, "enc")
                predictInputs = inputs(decoder, "predict", "token", "h", "c")
                predictOutputs = outputs(decoder, "predict", "dec_out", "h_out", "c_out")
                jointInputs = inputs(decoder, "joint", "enc_frame", "dec_out")
                jointOutputs = outputs(decoder, "joint", "logits")
            } catch (error: Throwable) {
                close()
                throw error
            }
        }

        private fun cpuOptions(threads: Int) = CompiledModel.Options(Accelerator.CPU).apply {
            cpuOptions = CompiledModel.CpuOptions(threads, null, null)
        }

        private fun inputs(model: CompiledModel, signature: String, vararg names: String) =
            names.associateWith { name -> model.createInputBuffer(name, signature).also { buffers += it } }

        private fun outputs(model: CompiledModel, signature: String, vararg names: String) =
            names.associateWith { name -> model.createOutputBuffer(name, signature).also { buffers += it } }

        /** Runs the mel graph on [length] samples from [start], zero-padded to the graph size. */
        fun mel(pcm: FloatArray, start: Int, length: Int): UltraMel {
            require(length in 1..graphSamples) { "Parakeet Ultra window does not fit its graph." }
            audio.fill(0f, length, audio.size)
            System.arraycopy(pcm, start, audio, 0, length)
            melInputs.getValue("audio").writeFloat(audio)
            melInputs.getValue("n_valid").writeInt(intArrayOf(length))
            mel.run(melInputs, melOutputs, SERVING_DEFAULT)
            val features = melOutputs.getValue("features").readFloat()
            val melMask = melOutputs.getValue("mel_mask").readFloat()
            val frameMask = melOutputs.getValue("frame_mask").readFloat()
            require(features.size == ULTRA_MEL_BINS * melFrames && melMask.size == melFrames &&
                frameMask.size == encoderFrames) { "Unexpected Parakeet Ultra mel output size." }
            var valid = 0.0
            for (value in frameMask) valid += value
            return UltraMel(features, melMask, valid.roundToInt())
        }

        /** Encoder output [frames x 640]; rows past the valid frames are undefined. */
        fun encode(mel: UltraMel): FloatArray {
            encoderInputs.getValue("features").writeFloat(mel.features)
            encoderInputs.getValue("mel_mask").writeFloat(mel.melMask)
            encoder.run(encoderInputs, encoderOutputs, SERVING_DEFAULT)
            return encoderOutputs.getValue("enc").readFloat().also {
                require(it.size == encoderFrames * ULTRA_HIDDEN) { "Unexpected Parakeet Ultra encoder output size." }
            }
        }

        /** The decoder graph as the greedy loop's network, over one window's encoder output. */
        fun network(encoded: FloatArray): UltraTdtNetwork<UltraState> = object : UltraTdtNetwork<UltraState> {
            override fun start(): UltraState =
                predict(UltraTdt.BLANK, FloatArray(ULTRA_STATE_VALUES), FloatArray(ULTRA_STATE_VALUES))

            override fun predict(token: Int, state: UltraState): UltraState = predict(token, state.h, state.c)

            override fun joint(frame: Int, state: UltraState): FloatArray {
                require(frame in 0 until encoderFrames) { "Parakeet Ultra frame out of range." }
                System.arraycopy(encoded, frame * ULTRA_HIDDEN, frameRow, 0, ULTRA_HIDDEN)
                jointInputs.getValue("enc_frame").writeFloat(frameRow)
                jointInputs.getValue("dec_out").writeFloat(state.decOut)
                decoder.run(jointInputs, jointOutputs, "joint")
                return jointOutputs.getValue("logits").readFloat()
            }
        }

        private fun predict(token: Int, h: FloatArray, c: FloatArray): UltraState {
            predictInputs.getValue("token").writeInt(intArrayOf(token))
            predictInputs.getValue("h").writeFloat(h)
            predictInputs.getValue("c").writeFloat(c)
            decoder.run(predictInputs, predictOutputs, "predict")
            val decOut = predictOutputs.getValue("dec_out").readFloat()
            val hOut = predictOutputs.getValue("h_out").readFloat()
            val cOut = predictOutputs.getValue("c_out").readFloat()
            require(decOut.size == ULTRA_HIDDEN && hOut.size == ULTRA_STATE_VALUES && cOut.size == ULTRA_STATE_VALUES) {
                "Unexpected Parakeet Ultra predict output size."
            }
            return UltraState(decOut, hOut, cOut)
        }

        override fun close() {
            if (closed) return
            closed = true
            buffers.forEach { runCatching { it.close() } }
            models.forEach { runCatching { it.close() } }
            environment?.let { runCatching { it.close() } }
            runCatching { modelFile.close() }
        }
    }
}
