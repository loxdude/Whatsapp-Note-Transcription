package com.local.voicenotes

import com.local.voicenotes.domain.ImportedModel
import com.local.voicenotes.domain.LanguageOption
import com.local.voicenotes.inference.TranscriptionBackend
import com.local.voicenotes.inference.UriTranscriptionBackend
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds

internal fun model(id: String, backend: String = "local", enabled: Boolean = true, note: String = "") =
    ImportedModel(
        id = id, displayName = "$id.tflite", path = "file:///models/$id.tflite", sizeBytes = 1L,
        architecture = "test", backend = backend, enabled = enabled, note = note
    )

internal val MODEL_A = model("a")
internal val MODEL_B = model("b")
internal val MODEL_CLOUD = model("cloud", backend = "cloud").copy(isApiModel = true)

/**
 * Simulates work that cannot be interrupted (a native LiteRT call, blocking I/O): it ignores
 * cancellation until the test releases it, then returns a value late.
 */
internal class Gate {
    val started = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val finished = CompletableDeferred<Unit>()

    suspend fun <T> nonCancellable(block: () -> T): T = withContext(NonCancellable) {
        started.complete(Unit)
        release.await()
        try { block() } finally { finished.complete(Unit) }
    }
}

internal open class FakeBackend : TranscriptionBackend {
    val prepared = CopyOnWriteArrayList<String>()
    val transcribeCalls = AtomicInteger()
    val cancelCount = AtomicInteger()
    val closeCount = AtomicInteger()
    @Volatile var onPrepare: suspend (ImportedModel) -> Result<Unit> = { Result.success(Unit) }
    @Volatile var onTranscribe: suspend (FloatArray, (Float) -> Unit) -> String = { _, _ -> "local text" }

    override suspend fun validate(model: ImportedModel): Result<Unit> = Result.success(Unit)
    override suspend fun prepare(model: ImportedModel): Result<Unit> {
        prepared += model.id
        return onPrepare(model)
    }
    override suspend fun transcribe(
        model: ImportedModel, pcm16KhzMono: FloatArray, language: LanguageOption, onProgress: (Float) -> Unit
    ): String {
        transcribeCalls.incrementAndGet()
        return onTranscribe(pcm16KhzMono, onProgress)
    }
    override fun cancel() { cancelCount.incrementAndGet() }
    override fun close() { closeCount.incrementAndGet() }
}

internal class FakeUriBackend : FakeBackend(), UriTranscriptionBackend {
    @Volatile var onTranscribeUri: suspend (String, (Float) -> Unit) -> String = { _, _ -> "cloud text" }
    override suspend fun transcribeUri(
        model: ImportedModel, uri: String, language: LanguageOption, onProgress: (Float) -> Unit
    ): String = onTranscribeUri(uri, onProgress)
}

internal class FakeCatalog(initial: List<ImportedModel>) : ModelCatalog {
    val stored = CopyOnWriteArrayList(initial)
    @Volatile var saved: String? = null
    val selections = CopyOnWriteArrayList<String>()
    @Volatile var selectGate: CompletableDeferred<Unit>? = null
    @Volatile var onImport: suspend (String, (Float) -> Unit) -> ImportedModel = { _, _ -> error("unexpected import") }

    override suspend fun models(): List<ImportedModel> = stored.toList()
    override suspend fun selectedModelId(): String? = saved
    override suspend fun select(id: String) {
        selectGate?.await()
        selections += id
        saved = id
    }
    override suspend fun import(source: String, onProgress: (Float) -> Unit): ImportedModel = onImport(source, onProgress)
}

internal class Harness(scope: CoroutineScope) {
    val catalog = FakeCatalog(listOf(MODEL_A, MODEL_B, MODEL_CLOUD))
    val local = FakeBackend()
    val cloud = FakeUriBackend()
    @Volatile var onDecode: suspend (String, (Float) -> Unit) -> FloatArray = { _, progress ->
        progress(1f)
        FloatArray(16)
    }
    val now = AtomicLong(1_000)
    val coordinator = TranscriptionCoordinator(
        scope = scope,
        catalog = catalog,
        decoder = PcmDecoder { source, progress -> onDecode(source, progress) },
        // The same backend under two keys mirrors the LiteRT CPU/QNN aliases in AppViewModel.
        backends = mapOf("local" to local, "local-alias" to local, "cloud" to cloud),
        monotonicMillis = { now.get() },
        clockInterval = 5.milliseconds
    )

    val state: AppUiState get() = coordinator.state.value

    suspend fun await(predicate: (AppUiState) -> Boolean): AppUiState =
        withTimeout(5_000) { coordinator.state.first(predicate) }

    /** Loads models, waits for the selected model to be prepared and selects audio. */
    suspend fun ready(selected: String = MODEL_A.id) {
        catalog.saved = selected
        coordinator.refreshModels().join()
        await { !it.busy }
        coordinator.setAudio("file:///voice.ogg", "voice.ogg")
    }
}

/** Lets every queued continuation on the single-threaded test event loop run. */
internal suspend fun settle() = repeat(50) { yield() }
