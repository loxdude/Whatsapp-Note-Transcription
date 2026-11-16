package com.local.voicenotes.history

import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.file.StandardCopyOption

/** One completed transcription as shown in the history list. */
data class HistoryEntry(
    val id: String,
    val createdAtMillis: Long,
    val audioName: String,
    val modelName: String,
    val text: String,
    /** Length of the decoded audio; null when the backend did not decode it (cloud upload). */
    val audioDurationMillis: Long? = null,
    val processingMillis: Long? = null
)

/** Data for a new entry; the store assigns the timestamp from its clock. */
data class NewHistoryEntry(
    val id: String,
    val audioName: String,
    val modelName: String,
    val text: String,
    val audioDurationMillis: Long? = null,
    val processingMillis: Long? = null
)

/**
 * Persistent transcript history in a single JSON file (Android-free, so it is unit-testable).
 *
 * - Newest entries first; at most [maxEntries] are kept, older ones are dropped on add.
 * - Every change rewrites the file atomically: the JSON goes to a temp file which is synced
 *   and then renamed over the main file. The previous main file is kept as `.bak` first.
 * - A missing or unreadable main file never throws: the store falls back to the backup, else
 *   starts empty. A corrupt main file is moved aside to `.corrupt` so the next write cannot
 *   overwrite the good backup with it.
 * - I/O failures while writing are logged; the in-memory list stays authoritative.
 *
 * All methods are synchronized; callers should still run them off the main thread.
 */
class TranscriptHistoryStore(
    private val file: File,
    private val clock: () -> Long,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val log: (String) -> Unit = {}
) {
    companion object {
        const val DEFAULT_MAX_ENTRIES = 100
        private const val FORMAT_VERSION = 1L
    }

    private val backupFile = File(file.path + ".bak")
    private val tempFile = File(file.path + ".tmp")
    private val corruptFile = File(file.path + ".corrupt")
    private var cache: List<HistoryEntry>? = null

    @Synchronized
    fun entries(): List<HistoryEntry> = cache ?: load().also { cache = it }

    /** Adds [draft] as the newest entry (replacing an entry with the same id) and persists. */
    @Synchronized
    fun add(draft: NewHistoryEntry): HistoryEntry {
        val entry = HistoryEntry(
            id = draft.id, createdAtMillis = clock(), audioName = draft.audioName,
            modelName = draft.modelName, text = draft.text,
            audioDurationMillis = draft.audioDurationMillis, processingMillis = draft.processingMillis
        )
        val updated = (listOf(entry) + entries().filter { it.id != entry.id }).take(maxEntries)
        commit(updated)
        return entry
    }

    /** Replaces the text of existing entries; unknown ids (deleted or evicted) are ignored. */
    @Synchronized
    fun updateTexts(edits: Map<String, String>): Boolean {
        val current = entries()
        var changed = false
        val updated = current.map { entry ->
            val text = edits[entry.id]
            if (text != null && text != entry.text) { changed = true; entry.copy(text = text) } else entry
        }
        if (changed) commit(updated)
        return changed
    }

    @Synchronized
    fun delete(id: String): Boolean {
        val current = entries()
        val updated = current.filter { it.id != id }
        if (updated.size == current.size) return false
        commit(updated)
        return true
    }

    private fun commit(updated: List<HistoryEntry>) {
        cache = updated
        try {
            write(updated)
        } catch (error: Exception) {
            log("Writing transcript history failed: ${error.message}")
            tempFile.delete()
        }
    }

    // ---- File format ------------------------------------------------------------------

    private fun write(entries: List<HistoryEntry>) {
        file.parentFile?.mkdirs()
        val json = MiniJson.write(
            mapOf("version" to FORMAT_VERSION, "entries" to entries.map(::toJson))
        )
        FileOutputStream(tempFile).use { output ->
            output.write(json.toByteArray(Charsets.UTF_8))
            output.flush()
            output.fd.sync()
        }
        if (file.exists()) {
            runCatching { backUp() }.onFailure { log("Backing up transcript history failed: ${it.message}") }
        }
        try {
            Files.move(
                tempFile.toPath(), file.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
            )
        } catch (atomicUnsupported: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(tempFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        // Makes the rename (and the backup) durable before the commit counts as done.
        file.parentFile?.let(::syncDirectory)
    }

    /** Copies the current main file to `.bak` and syncs it, so the backup survives a crash. */
    private fun backUp() {
        file.inputStream().use { input ->
            FileOutputStream(backupFile).use { output ->
                input.copyTo(output)
                output.flush()
                output.fd.sync()
            }
        }
    }

    /** Not supported on every filesystem; harmless then (the previous commit stays readable). */
    private fun syncDirectory(directory: File) {
        runCatching { FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) } }
    }

    private fun load(): List<HistoryEntry> {
        // A leftover temp file is an interrupted write; the main file is still the last commit.
        tempFile.delete()
        if (file.exists()) {
            try {
                return parse(file.readText(Charsets.UTF_8))
            } catch (error: Exception) {
                log("Transcript history is unreadable, trying backup: ${error.message}")
                if (!file.renameTo(corruptFile)) file.delete()
            }
        }
        if (backupFile.exists()) {
            try {
                return parse(backupFile.readText(Charsets.UTF_8))
            } catch (error: Exception) {
                log("Transcript history backup is unreadable, starting empty: ${error.message}")
            }
        }
        return emptyList()
    }

    private fun parse(text: String): List<HistoryEntry> {
        val root = MiniJson.parse(text) as? Map<*, *> ?: throw IllegalArgumentException("Not a JSON object")
        val items = root["entries"] as? List<*> ?: throw IllegalArgumentException("Missing entries")
        return items.mapNotNull { item -> (item as? Map<*, *>)?.let(::fromJson) }
            .distinctBy { it.id }
            .sortedByDescending { it.createdAtMillis }
            .take(maxEntries)
    }

    private fun toJson(entry: HistoryEntry): Map<String, Any?> = linkedMapOf(
        "id" to entry.id,
        "createdAt" to entry.createdAtMillis,
        "audioName" to entry.audioName,
        "modelName" to entry.modelName,
        "text" to entry.text,
        "audioDurationMillis" to entry.audioDurationMillis,
        "processingMillis" to entry.processingMillis
    )

    /** Skips individual malformed entries instead of discarding the whole history. */
    private fun fromJson(map: Map<*, *>): HistoryEntry? {
        val id = map["id"] as? String ?: return null
        val text = map["text"] as? String ?: return null
        return HistoryEntry(
            id = id,
            createdAtMillis = (map["createdAt"] as? Number)?.toLong() ?: 0L,
            audioName = map["audioName"] as? String ?: "",
            modelName = map["modelName"] as? String ?: "",
            text = text,
            audioDurationMillis = (map["audioDurationMillis"] as? Number)?.toLong(),
            processingMillis = (map["processingMillis"] as? Number)?.toLong()
        )
    }
}
