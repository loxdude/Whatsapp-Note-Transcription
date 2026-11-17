package com.local.voicenotes.share

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceNoteFoldersTest {
    private fun dir(name: String) = FolderEntry(name, name, true, 0, 0)
    private fun file(name: String, modified: Long) = FolderEntry(name, name, false, modified, 10)

    @Test fun newestNotesComeFirstAndOnlyRecentWeekFoldersAreListed() {
        val tree = mapOf(
            "root" to listOf(dir("202301"), dir("202546"), dir("202545"), dir(".Statuses"), file(".nomedia", 9)),
            "202546" to listOf(file("PTT-a.opus", 300), file("PTT-b.opus", 500)),
            "202545" to listOf(file("PTT-c.opus", 200), file("notes.txt", 999)),
            "202301" to listOf(file("PTT-old.opus", 1))
        )
        val listed = mutableListOf<String>()
        val result = VoiceNoteFolders.newest("root", limit = 3, maxFolders = 2) { id -> listed += id; tree.getValue(id) }
        assertEquals(listOf("PTT-b.opus", "PTT-a.opus", "PTT-c.opus"), result.map { it.name })
        // Only the newest week folders within the cap are opened; hidden folders never.
        assertEquals(listOf("root", "202546", "202545"), listed)
    }

    @Test fun newerNotesInOlderNamedFoldersOrTheRootAreNotMissed() {
        // After a restore, an older week folder can hold the newest file, and the root can
        // already hold at least `limit` files.
        val tree = mapOf(
            "root" to listOf(file("PTT-root1.opus", 10), file("PTT-root2.opus", 20), dir("202546"), dir("202540")),
            "202546" to listOf(file("PTT-a.opus", 100)),
            "202540" to listOf(file("PTT-restored.opus", 900))
        )
        val result = VoiceNoteFolders.newest("root", limit = 2) { tree.getValue(it) }
        assertEquals(listOf("PTT-restored.opus", "PTT-a.opus"), result.map { it.name })
    }
}
