package com.local.voicenotes.history

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class TranscriptHistoryTest {
    @get:Rule val folder = TemporaryFolder()

    private val executors = mutableListOf<ScheduledExecutorService>()

    @After fun stopExecutors() {
        executors.forEach { it.shutdownNow() }
        executors.forEach { assertTrue(it.awaitTermination(5, TimeUnit.SECONDS)) }
    }

    private fun history(
        store: TranscriptHistoryStore = newStore(),
        editDelayMillis: Long = 60_000
    ): TranscriptHistory {
        val executor = Executors.newSingleThreadScheduledExecutor()
        executors += executor
        return TranscriptHistory(store, executor = executor, editDelayMillis = editDelayMillis)
    }

    private var now = 1_000L
    private fun newStore() = TranscriptHistoryStore(File(folder.root, "h.json"), clock = { now++ })

    private fun draft(id: String) = NewHistoryEntry(id, "$id.ogg", "Parakeet", "text $id")

    @Test fun publishesEntriesNewestFirst() {
        val history = history()
        history.add(draft("a"))
        history.add(draft("b"))
        history.awaitIdle()
        assertEquals(listOf("b", "a"), history.entries.value.map { it.id })
    }

    @Test fun editsAreCoalescedAndWrittenOnFlush() {
        val history = history()
        history.add(draft("a"))
        history.updateText("a", "e")
        history.updateText("a", "ed")
        history.updateText("a", "edited")
        history.awaitIdle()
        assertEquals("text a", history.entries.value.single().text)
        history.flush()
        history.awaitIdle()
        assertEquals("edited", history.entries.value.single().text)
        assertEquals("edited", newStore().entries().single().text)
    }

    @Test fun editsAreWrittenAfterTheDelay() {
        val history = history(editDelayMillis = 20)
        history.add(draft("a"))
        history.updateText("a", "edited")
        val deadline = System.currentTimeMillis() + 5_000
        while (history.entries.value.firstOrNull()?.text != "edited" && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertEquals("edited", history.entries.value.single().text)
    }

    @Test fun deleteDropsPendingEdits() {
        val history = history()
        history.add(draft("a"))
        history.updateText("a", "edited")
        history.delete("a")
        history.flush()
        history.awaitIdle()
        assertTrue(history.entries.value.isEmpty())
        assertTrue(newStore().entries().isEmpty())
    }

    @Test fun blankTranscriptIsListedOnceTheUserEditsTextIntoIt() {
        val history = history()
        history.add(NewHistoryEntry("blank", "b.ogg", "Parakeet", "", audioDurationMillis = 900))
        history.awaitIdle()
        assertTrue("no speech recognized: not listed", history.entries.value.isEmpty())

        history.updateText("blank", "   ")
        history.flush()
        history.awaitIdle()
        assertTrue("whitespace still counts as blank", history.entries.value.isEmpty())

        history.updateText("blank", "typed by the user")
        history.flush()
        history.awaitIdle()
        val entry = history.entries.value.single()
        assertEquals("blank", entry.id)
        assertEquals("typed by the user", entry.text)
        assertEquals(900L, entry.audioDurationMillis)
        assertEquals("typed by the user", newStore().entries().single().text)

        // Later edits update the now-listed entry as usual.
        history.updateText("blank", "edited again")
        history.flush()
        history.awaitIdle()
        assertEquals("edited again", newStore().entries().single().text)
    }

    @Test fun deletedBlankTranscriptIsNotResurrectedByALateEdit() {
        val history = history()
        history.add(NewHistoryEntry("blank", "b.ogg", "Parakeet", ""))
        history.delete("blank")
        history.updateText("blank", "text")
        history.flush()
        history.awaitIdle()
        assertTrue(history.entries.value.isEmpty())
    }

    @Test fun flushRetriesFailedEditsWithoutAnotherEdit() {
        val history = history()
        history.add(draft("a"))
        history.awaitIdle()
        val blockedTemp = File(folder.root, "h.json.tmp").apply { mkdir() }
        File(blockedTemp, "blocker").writeText("occupied")

        history.updateText("a", "edited")
        history.flush()
        history.awaitIdle()
        assertEquals("edited", history.entries.value.single().text)
        assertEquals("text a", newStore().entries().single().text)
        assertNotNull(history.persistenceError.value)
        assertTrue(history.persistenceError.value!!.contains("Speicher"))

        assertTrue(blockedTemp.deleteRecursively())
        history.flush()
        history.awaitIdle()
        assertEquals("edited", newStore().entries().single().text)
        assertNull(history.persistenceError.value)
    }

    @Test fun flushRetriesAFailedAddAfterStorageBecomesAvailable() {
        val blocked = folder.newFile("blocked")
        val file = File(blocked, "history.json")
        val history = history(TranscriptHistoryStore(file, clock = { now++ }))
        history.add(draft("a"))
        history.awaitIdle()
        assertEquals(listOf("a"), history.entries.value.map { it.id })
        assertNotNull(history.persistenceError.value)

        assertTrue(blocked.delete())
        assertTrue(blocked.mkdir())
        history.flush()
        history.awaitIdle()
        assertEquals(listOf("a"), TranscriptHistoryStore(file, clock = { 0 }).entries().map { it.id })
        assertNull(history.persistenceError.value)
    }

    @Test fun deleteAfterFailedSaveRetriesOnlyTheNewestSurvivingState() {
        val history = history()
        history.add(draft("keep"))
        history.add(draft("delete"))
        history.awaitIdle()
        val blockedTemp = File(folder.root, "h.json.tmp").apply { mkdir() }
        File(blockedTemp, "blocker").writeText("occupied")

        history.updateText("delete", "superseded secret")
        history.updateText("keep", "superseded edit")
        history.flush()
        history.awaitIdle()
        assertNotNull(history.persistenceError.value)
        history.delete("delete")
        history.updateText("keep", "newest edit")
        history.flush()
        history.awaitIdle()
        assertNotNull(history.persistenceError.value)

        assertTrue(blockedTemp.deleteRecursively())
        history.flush()
        history.awaitIdle()
        val surviving = newStore().entries().single()
        assertEquals("keep", surviving.id)
        assertEquals("newest edit", surviving.text)
        assertNull(history.persistenceError.value)
        File(folder.root, "h.json").writeText("damaged main")
        assertEquals(listOf(surviving), newStore().entries())
    }

    @Test fun loadsExistingFileOnStart() {
        newStore().add(draft("old"))
        val history = history()
        history.awaitIdle()
        assertEquals(listOf("old"), history.entries.value.map { it.id })
    }
}
