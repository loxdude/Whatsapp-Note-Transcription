package com.local.voicenotes.model

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * One-tap downloads of every [ModelSpecs] entry into [directory], one at a time. A download and
 * a removal never overlap: either may only start while neither runs, so an older removal can
 * never delete a file a newer download has just registered. Main thread, except where noted.
 */
class ModelDownloads(
    private val scope: CoroutineScope,
    private val directory: File,
    private val source: RangeSource,
    /** Aborts the HTTP call blocked in a read (coroutine cancellation alone cannot). */
    private val abortInFlight: () -> Unit,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val log: (String) -> Unit = {}
) {
    private val downloads = ModelSpecs.all.associate { it.id to ModelDownload(directory, it, source) }

    private val mutableStates = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    /** Per spec id; a missing id is Idle. */
    val states: StateFlow<Map<String, DownloadState>> = mutableStates.asStateFlow()

    private val mutableCurrent = MutableStateFlow<DownloadState>(DownloadState.Idle)
    /** State of the most recently started download, for the foreground service. */
    val current: StateFlow<DownloadState> = mutableCurrent.asStateFlow()

    private val mutableInstalled = MutableStateFlow<Set<String>>(emptySet())
    /** Spec ids whose verified file is present (cheap size check; the hash was verified on download). */
    val installed: StateFlow<Set<String>> = mutableInstalled.asStateFlow()

    private var job: Job? = null
    private var removeJob: Job? = null

    init { refreshInstalled() }

    fun file(spec: DownloadSpec): File = downloads.getValue(spec.id).target

    fun state(id: String): DownloadState = mutableStates.value[id] ?: DownloadState.Idle

    /** True while a download or removal runs; nothing else may start then. */
    val busy: Boolean get() = job?.isCompleted == false || removing

    /** True while files are being removed; they must not be opened then. */
    val removing: Boolean get() = removeJob?.isCompleted == false

    /**
     * Downloads and verifies [spec], then runs [onDownloaded] with the final file (still part of
     * the download, so a removal cannot interleave). Returns false if another download or a
     * removal is running.
     */
    fun start(spec: DownloadSpec, onDownloaded: suspend (File) -> Unit = {}): Boolean {
        if (busy) return false
        val download = downloads.getValue(spec.id)
        publish(spec.id, DownloadState.Running(0, spec.sizeBytes))
        job = scope.launch {
            try {
                val file = withContext(io) {
                    var lastPublished = -1L
                    download.run(
                        checkCancelled = { coroutineContext.ensureActive() },
                        onProgress = { done, total ->
                            // ~0.25 % steps keep StateFlow/notification churn low.
                            val step = done * 400 / total
                            if (step != lastPublished) {
                                lastPublished = step
                                publish(spec.id, DownloadState.Running(done, total))
                            }
                        },
                        onVerifying = { publish(spec.id, DownloadState.Verifying) }
                    )
                }
                onDownloaded(file)
                refreshInstalledNow()
                publish(spec.id, DownloadState.Completed(spec.title))
                log("Model download finished: ${spec.id} ${file.length()} bytes")
            } catch (cancelled: CancellationException) {
                publish(spec.id, DownloadState.Idle)
                throw cancelled
            } catch (error: Exception) {
                // Cancelling the HTTP call surfaces as an IOException in the blocked read.
                if (!coroutineContext.isActive) {
                    publish(spec.id, DownloadState.Idle)
                    throw CancellationException("Download cancelled.").apply { initCause(error) }
                }
                log("Model download failed: ${spec.id} ${error.message}")
                publish(spec.id, DownloadState.Failed(downloadErrorMessage(error)))
            }
        }
        return true
    }

    /** Cancels the running download; the partial file is kept so a retry resumes. */
    fun cancel() {
        val running = job?.takeIf { it.isActive } ?: return
        running.cancel()
        abortInFlight()
    }

    /** Clears a finished or failed state so the UI shows the plain download button again. */
    fun dismiss(id: String) {
        val state = state(id)
        if (state is DownloadState.Failed || state is DownloadState.Completed) publish(id, DownloadState.Idle)
    }

    /**
     * Deletes the files of [specs] after [beforeDelete] (e.g. releasing a loaded model or its
     * record), then runs [afterDelete]. Returns false while a download or removal runs.
     */
    fun remove(
        specs: List<DownloadSpec>,
        beforeDelete: suspend () -> Unit = {},
        afterDelete: suspend () -> Unit = {}
    ): Boolean {
        if (busy) return false
        removeJob = scope.launch {
            try {
                beforeDelete()
                withContext(io) { specs.forEach { downloads.getValue(it.id).delete() } }
                specs.forEach { publish(it.id, DownloadState.Idle) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                log("Removing ${specs.joinToString { it.id }} failed: ${error.message}")
            } finally {
                refreshInstalledNow()
                afterDelete()
            }
        }
        return true
    }

    private fun publish(id: String, state: DownloadState) {
        mutableStates.update { it + (id to state) }
        mutableCurrent.value = state
    }

    private fun refreshInstalled() {
        scope.launch { refreshInstalledNow() }
    }

    private suspend fun refreshInstalledNow() {
        mutableInstalled.value = withContext(io) {
            downloads.filterValues { it.isComplete() }.keys
        }
    }
}

/** User-facing message for a failed download. */
fun downloadErrorMessage(error: Exception): String = when (error) {
    is java.net.UnknownHostException, is java.net.ConnectException ->
        "No internet connection. Check your network and retry."
    is java.net.SocketTimeoutException -> "The connection timed out. Tap Retry to resume."
    else -> error.message ?: "Download failed."
}
