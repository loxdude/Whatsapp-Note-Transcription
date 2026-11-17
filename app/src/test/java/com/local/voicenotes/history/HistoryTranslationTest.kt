package com.local.voicenotes.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HistoryTranslationTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun translationIsStoredAndOlderFilesStillLoad() {
        val file = File(tmp.root, "history.json")
        file.writeText("""{"version":1,"entries":[{"id":"old","createdAt":1,"audioName":"a","modelName":"m","text":"t"}]}""")
        val store = TranscriptHistoryStore(file, clock = { 2L })
        assertNull(store.entries().single().translation)
        assertTrue(store.updateTranslation("old", "Hallo"))
        assertFalse(store.updateTranslation("missing", "x"))
        val reloaded = TranscriptHistoryStore(file, clock = { 3L }).entries().single()
        assertEquals("Hallo", reloaded.translation)
        assertEquals("t", reloaded.text)
    }
}
