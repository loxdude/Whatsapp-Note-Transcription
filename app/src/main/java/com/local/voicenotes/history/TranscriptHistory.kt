package com.local.voicenotes.history

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * App-scoped, thread-safe front end of [TranscriptHistoryStore]: every operation runs in
 * submission order on one background thread, so callers (coordinator callbacks, UI) never
 * block on disk I/O. Transcript edits are coalesced and written after [editDelayMillis].
 */
class TranscriptHistory(
    private val store: TranscriptHistoryStore,
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "transcript-history").apply { isDaemon = true }
    },
    private val editDelayMillis: Long = 600,
    private val log: (String) -> Unit = {}
) {
    private val mutableEntries = MutableStateFlow<List<HistoryEntry>>(emptyList())
    /** Newest first. Empty until the file has been loaded. */
    val entries: StateFlow<List<HistoryEntry>> = mutableEntries.asStateFlow()

    private val editLock = Any()
    private val pendingEdits = LinkedHashMap<String, String>() // guarded by editLock
    private var scheduledFlush: ScheduledFuture<*>? = null      // guarded by editLock

    init {
        submit { }
    }

    fun add(draft: NewHistoryEntry) = submit { store.add(draft) }

    /** Records an edit of entry [id]; persisted after a short delay or on [flush]. */
    fun updateText(id: String, text: String) {
        synchronized(editLock) {
            pendingEdits[id] = text
            if (scheduledFlush == null) {
                scheduledFlush = executor.schedule({ runLogged { flushEdits() } }, editDelayMillis, TimeUnit.MILLISECONDS)
            }
        }
    }

    /** Writes pending edits now (e.g. when the app goes to the background). */
    fun flush() = submit { flushEdits() }

    fun delete(id: String) = submit {
        synchronized(editLock) { pendingEdits.remove(id) }
        store.delete(id)
    }

    /** Blocks until all previously submitted operations are done (tests). */
    internal fun awaitIdle() {
        executor.submit { }.get(5, TimeUnit.SECONDS)
    }

    private fun flushEdits() {
        val edits = synchronized(editLock) {
            scheduledFlush?.cancel(false)
            scheduledFlush = null
            LinkedHashMap(pendingEdits).also { pendingEdits.clear() }
        }
        if (edits.isNotEmpty()) store.updateTexts(edits)
    }

    private fun submit(block: () -> Unit) {
        executor.execute { runLogged(block) }
    }

    private fun runLogged(block: () -> Unit) {
        try {
            block()
        } catch (error: Throwable) {
            log("Transcript history operation failed: ${error.message}")
        }
        mutableEntries.value = runCatching { store.entries() }.getOrElse { mutableEntries.value }
    }
}
