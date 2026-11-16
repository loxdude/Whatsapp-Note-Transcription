package com.local.voicenotes.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicLong

class TranscriptHistoryStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private val now = AtomicLong(1_000)
    private val logs = mutableListOf<String>()
    private val file: File get() = File(folder.root, "history.json")
    private val backup: File get() = File(folder.root, "history.json.bak")

    private fun store(max: Int = 100) =
        TranscriptHistoryStore(file, clock = { now.get() }, maxEntries = max, log = { logs += it })

    private fun draft(id: String, text: String = "text $id") =
        NewHistoryEntry(id = id, audioName = "$id.ogg", modelName = "Parakeet", text = text,
            audioDurationMillis = 2_000, processingMillis = 300)

    private fun add(store: TranscriptHistoryStore, id: String, at: Long, text: String = "text $id") {
        now.set(at)
        store.add(draft(id, text))
    }

    @Test fun entriesAreNewestFirstAndSurviveReload() {
        val store = store()
        add(store, "a", 1_000)
        add(store, "b", 2_000)
        add(store, "c", 3_000)
        assertEquals(listOf("c", "b", "a"), store.entries().map { it.id })

        val reloaded = store().entries()
        assertEquals(listOf("c", "b", "a"), reloaded.map { it.id })
        val c = reloaded.first()
        assertEquals(3_000L, c.createdAtMillis)
        assertEquals("c.ogg", c.audioName)
        assertEquals("Parakeet", c.modelName)
        assertEquals(2_000L, c.audioDurationMillis)
        assertEquals(300L, c.processingMillis)
    }

    @Test fun keepsOnlyTheNewestHundred() {
        val store = store()
        for (i in 1..105) add(store, "e$i", i * 10L)
        val ids = store.entries().map { it.id }
        assertEquals(100, ids.size)
        assertEquals("e105", ids.first())
        assertEquals("e6", ids.last())
        assertEquals(100, store().entries().size)
    }

    @Test fun addingAnExistingIdReplacesIt() {
        val store = store()
        add(store, "a", 1_000, "first")
        add(store, "b", 2_000)
        add(store, "a", 3_000, "second")
        assertEquals(listOf("a", "b"), store.entries().map { it.id })
        assertEquals("second", store.entries().first().text)
    }

    @Test fun deleteRemovesTheEntryPersistently() {
        val store = store()
        add(store, "a", 1_000)
        add(store, "b", 2_000)
        assertTrue(store.delete("a"))
        assertFalse(store.delete("a"))
        assertEquals(listOf("b"), store.entries().map { it.id })
        assertEquals(listOf("b"), store().entries().map { it.id })
    }

    @Test fun deletionCannotBeUndoneByBackupRecoveryAndRemovesSensitiveArtifacts() {
        val store = store()
        add(store, "keep", 1_000)
        add(store, "secret", 2_000, "sensitive transcript")
        File(folder.root, "history.json.corrupt").writeText("broken sensitive transcript")
        File(folder.root, "history.json.tmp").writeText("sensitive transcript")

        store.delete("secret")

        assertFalse(File(folder.root, "history.json.corrupt").exists())
        assertFalse(File(folder.root, "history.json.tmp").exists())
        assertFalse(backup.readText().contains("sensitive transcript"))
        file.writeText("damaged main")
        assertEquals(listOf("keep"), store().entries().map { it.id })
    }

    @Test fun deletingAnIdOnlyInBackupPreservesOtherRecoverableEntries() {
        val seed = store()
        add(seed, "backup-only", 1_000)
        add(seed, "secret", 2_000, "sensitive transcript")
        backup.writeText(file.readText())
        // Valid main with a different, independently recoverable older backup.
        file.writeText("""{"version":1,"entries":[{"id":"current","createdAt":3000,"text":"keep"}]}""")
        val store = store()
        File(folder.root, "history.json.corrupt").writeText("broken sensitive transcript")
        File(folder.root, "history.json.tmp").writeText("sensitive transcript")

        assertFalse(store.delete("secret"))

        assertEquals(listOf("current"), store().entries().map { it.id })
        assertFalse(backup.readText().contains("sensitive transcript"))
        assertFalse(File(folder.root, "history.json.corrupt").exists())
        assertFalse(File(folder.root, "history.json.tmp").exists())
        file.writeText("damaged main")
        assertEquals(listOf("backup-only"), store().entries().map { it.id })
    }

    @Test fun failedBackupSanitizationRemainsAnErrorUntilFlushRetriesCleanup() {
        val store = store()
        add(store, "keep", 1_000)
        add(store, "secret", 2_000)
        assertTrue(backup.delete())
        assertTrue(backup.mkdir())
        File(backup, "blocker").writeText("occupied")

        store.delete("secret")
        assertEquals(listOf("keep"), store.entries().map { it.id })
        assertNotNull(store.persistenceError)
        // Reads must not replace the dirty cache with the stale on-disk version.
        assertEquals(listOf("keep"), store.entries().map { it.id })

        assertTrue(backup.deleteRecursively())
        store.flush()
        assertNull(store.persistenceError)
        assertEquals(listOf("keep"), store().entries().map { it.id })
        file.writeText("damaged main")
        assertEquals(listOf("keep"), store().entries().map { it.id })
    }

    @Test fun failedCorruptArtifactCleanupIsNotReportedAsSaved() {
        val store = store()
        add(store, "keep", 1_000)
        add(store, "secret", 2_000)
        val corrupt = File(folder.root, "history.json.corrupt").apply { mkdir() }
        File(corrupt, "blocker").writeText("sensitive transcript")

        store.delete("secret")
        assertNotNull(store.persistenceError)
        assertEquals(listOf("keep"), store.entries().map { it.id })

        assertTrue(corrupt.deleteRecursively())
        store.flush()
        assertNull(store.persistenceError)
        assertFalse(corrupt.exists())
        assertEquals(listOf("keep"), store().entries().map { it.id })
    }

    @Test fun editsUpdateOnlyKnownEntries() {
        val store = store()
        add(store, "a", 1_000)
        assertTrue(store.updateTexts(mapOf("a" to "edited", "gone" to "ignored")))
        assertFalse(store.updateTexts(mapOf("a" to "edited")))
        assertEquals("edited", store().entries().single().text)
    }

    @Test fun textWithQuotesNewlinesAndUnicodeRoundTrips() {
        val text = "Line 1\n\"quoted\" \\ back\tslash — Grüße 👋 \u0001 end"
        val store = store()
        add(store, "a", 1_000, text)
        assertEquals(text, store().entries().single().text)
    }

    @Test fun writesAtomicallyAndKeepsTheLastVersionAsBackup() {
        val store = store()
        add(store, "a", 1_000)
        add(store, "b", 2_000)
        assertFalse(File(folder.root, "history.json.tmp").exists())
        assertTrue(backup.exists())
        // The backup is the previous commit.
        file.delete()
        assertEquals(listOf("a"), store().entries().map { it.id })
    }

    @Test fun leftoverTempFileFromAnInterruptedWriteIsIgnored() {
        add(store(), "a", 1_000)
        File(folder.root, "history.json.tmp").writeText("{\"entries\": [")
        val store = store()
        assertEquals(listOf("a"), store.entries().map { it.id })
        assertFalse(File(folder.root, "history.json.tmp").exists())
    }

    @Test fun corruptFileWithoutBackupStartsEmptyAndRecovers() {
        file.writeText("{ this is not json")
        val store = store()
        assertTrue(store.entries().isEmpty())
        assertTrue(logs.isNotEmpty())
        assertTrue("corrupt file is kept for inspection", File(folder.root, "history.json.corrupt").exists())
        add(store, "a", 1_000)
        assertEquals(listOf("a"), store().entries().map { it.id })
    }

    @Test fun corruptFileFallsBackToBackupWithoutOverwritingIt() {
        val first = store()
        add(first, "a", 1_000)
        add(first, "b", 2_000) // backup now holds [a]
        file.writeBytes(byteArrayOf(0, 1, 2, 3))

        val recovered = store()
        assertEquals(listOf("a"), recovered.entries().map { it.id })
        add(recovered, "c", 3_000)
        assertEquals(listOf("c", "a"), store().entries().map { it.id })
    }

    @Test fun wrongShapeAndBadEntriesAreTolerated() {
        file.writeText("[1, 2, 3]")
        assertTrue(store().entries().isEmpty())

        file.writeText(
            """{"version":1,"entries":[{"id":"ok","createdAt":5,"text":"fine"},{"text":"no id"},42,""" +
                """{"id":"x","createdAt":"bad","text":"still fine","audioDurationMillis":null}]}"""
        )
        val entries = store().entries()
        assertEquals(listOf("ok", "x"), entries.map { it.id })
        assertNull(entries.last().audioDurationMillis)
        assertEquals("", entries.first().audioName)
    }

    @Test fun unwritableDirectoryKeepsEntriesInMemory() {
        val blocked = folder.newFile("not-a-directory")
        val store = TranscriptHistoryStore(File(blocked, "history.json"), clock = { now.get() }, log = { logs += it })
        store.add(draft("a"))
        assertEquals(listOf("a"), store.entries().map { it.id })
        assertTrue(logs.any { it.contains("failed") })
    }
}
