package com.local.voicenotes.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TranscriptHistoryTest {
    @get:Rule val folder = TemporaryFolder()

    private var now = 1_000L
    private fun newStore() = TranscriptHistoryStore(File(folder.root, "h.json"), clock = { now++ })

    private fun draft(id: String) = NewHistoryEntry(id, "$id.ogg", "Parakeet", "text $id")

    @Test fun publishesEntriesNewestFirst() {
        val history = TranscriptHistory(newStore(), editDelayMillis = 60_000)
        history.add(draft("a"))
        history.add(draft("b"))
        history.awaitIdle()
        assertEquals(listOf("b", "a"), history.entries.value.map { it.id })
    }

    @Test fun editsAreCoalescedAndWrittenOnFlush() {
        val history = TranscriptHistory(newStore(), editDelayMillis = 60_000)
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
        val history = TranscriptHistory(newStore(), editDelayMillis = 20)
        history.add(draft("a"))
        history.updateText("a", "edited")
        val deadline = System.currentTimeMillis() + 5_000
        while (history.entries.value.firstOrNull()?.text != "edited" && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertEquals("edited", history.entries.value.single().text)
    }

    @Test fun deleteDropsPendingEdits() {
        val history = TranscriptHistory(newStore(), editDelayMillis = 60_000)
        history.add(draft("a"))
        history.updateText("a", "edited")
        history.delete("a")
        history.flush()
        history.awaitIdle()
        assertTrue(history.entries.value.isEmpty())
        assertTrue(newStore().entries().isEmpty())
    }

    @Test fun loadsExistingFileOnStart() {
        newStore().add(draft("old"))
        val history = TranscriptHistory(newStore())
        history.awaitIdle()
        assertEquals(listOf("old"), history.entries.value.map { it.id })
    }
}
