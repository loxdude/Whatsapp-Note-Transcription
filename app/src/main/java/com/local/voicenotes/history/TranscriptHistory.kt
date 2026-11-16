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

    private val mutablePersistenceError = MutableStateFlow<String?>(null)
    /** Human-readable save failure; null once the newest snapshot and cleanup are flushed. */
    val persistenceError: StateFlow<String?> = mutablePersistenceError.asStateFlow()

    private val editLock = Any()
    private val pendingEdits = LinkedHashMap<String, String>() // guarded by editLock
    private var scheduledFlush: ScheduledFuture<*>? = null      // guarded by editLock
    /**
     * Completed transcriptions whose text was blank (no speech recognized). They are not
     * listed, but kept so the entry is added once the user types a transcript for them.
     * Executor thread only.
     */
    private val blankDrafts = LinkedHashMap<String, NewHistoryEntry>()

    init {
        submit { }
    }

    /**
     * Adds a completed transcription. A blank one is not listed yet; it is added as soon as an
     * edit gives it text (see [updateText]).
     */
    fun add(draft: NewHistoryEntry) = submit {
        if (draft.text.isBlank()) {
            blankDrafts.remove(draft.id)
            blankDrafts[draft.id] = draft
            while (blankDrafts.size > MAX_BLANK_DRAFTS) blankDrafts.remove(blankDrafts.keys.first())
        } else {
            blankDrafts.remove(draft.id)
            store.add(draft)
        }
    }

    /** Records an edit of entry [id]; persisted after a short delay or on [flush]. */
    fun updateText(id: String, text: String) {
        synchronized(editLock) {
            pendingEdits[id] = text
            if (scheduledFlush == null) {
                scheduledFlush = executor.schedule({ runLogged { flushEdits() } }, editDelayMillis, TimeUnit.MILLISECONDS)
            }
        }
    }

    /** Writes pending edits and retries failed persistence, even without new edits. */
    fun flush() = submit { flushEdits() }

    fun delete(id: String) = submit {
        synchronized(editLock) { pendingEdits.remove(id) }
        blankDrafts.remove(id)
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
        var writeAttempted = false
        // Edits that give a blank transcription its first text create its entry.
        val remaining = LinkedHashMap<String, String>()
        edits.forEach { (id, text) ->
            val blank = blankDrafts[id]
            if (blank != null && text.isNotBlank()) {
                blankDrafts.remove(id)
                store.add(blank.copy(text = text))
                writeAttempted = true
            } else if (blank == null) {
                remaining[id] = text
            }
        }
        if (remaining.isNotEmpty()) writeAttempted = store.updateTexts(remaining) || writeAttempted
        // Edits have moved into the store's dirty snapshot, not been discarded on I/O failure.
        if (!writeAttempted) store.flush()
    }

    private companion object {
        const val MAX_BLANK_DRAFTS = 8
    }

    private fun submit(block: () -> Unit) {
        executor.execute { runLogged(block) }
    }

    private fun runLogged(block: () -> Unit) {
        var operationError: String? = null
        try {
            block()
        } catch (error: Exception) {
            operationError = "Verlauf konnte nicht aktualisiert werden. Bitte erneut versuchen."
            log("Transcript history operation failed: ${error.message}")
        }
        mutableEntries.value = runCatching { store.entries() }.getOrElse { mutableEntries.value }
        mutablePersistenceError.value = operationError ?: store.persistenceError
    }
}
