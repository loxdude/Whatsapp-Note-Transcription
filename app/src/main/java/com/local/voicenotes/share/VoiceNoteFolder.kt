package com.local.voicenotes.share

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document

/** One audio file in the granted WhatsApp voice-note folder. */
data class VoiceNoteFile(val uri: Uri, val name: String, val lastModifiedMillis: Long, val sizeBytes: Long)

/** A child of a folder listing, independent of Android for unit tests. */
data class FolderEntry(val id: String, val name: String, val isDirectory: Boolean, val lastModifiedMillis: Long, val sizeBytes: Long)

object VoiceNoteFolders {
    const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    /** WhatsApp keeps voice notes in weekly subfolders (yyyyww) below this folder. */
    const val WHATSAPP_VOICE_NOTES_ID = "primary:Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes"
    const val PRIMARY_ROOT_ID = "primary:"

    val whatsAppVoiceNotes: Uri get() = DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, WHATSAPP_VOICE_NOTES_ID)
    /** Top of the internal shared storage (/storage/emulated/0). */
    val primaryRoot: Uri get() = DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, PRIMARY_ROOT_ID)

    private val audioExtensions = setOf("opus", "ogg", "oga", "m4a", "aac", "mp3", "wav", "amr")

    fun isAudio(entry: FolderEntry): Boolean =
        !entry.isDirectory && !entry.name.startsWith(".") && entry.name.substringAfterLast('.', "").lowercase() in audioExtensions

    /** Week folders listed per search: about five months of history at one folder per week. */
    const val MAX_FOLDERS = 20

    /**
     * Newest [limit] audio files by modification time. The root and its newest [maxFolders]
     * week folders (by name, yyyyww, then modification time) are all listed before sorting:
     * after a restore or copy, file times need not follow folder names, so stopping at the
     * first [limit] hits could miss newer notes. The folder cap keeps years of history cheap.
     * [list] returns the children of a folder id.
     */
    fun newest(rootId: String, limit: Int, maxFolders: Int = MAX_FOLDERS, list: (String) -> List<FolderEntry>): List<FolderEntry> {
        val top = list(rootId)
        val found = top.filter(::isAudio).toMutableList()
        top.filter { it.isDirectory && !it.name.startsWith(".") }
            .sortedWith(compareByDescending<FolderEntry> { it.name }.thenByDescending { it.lastModifiedMillis })
            .take(maxFolders)
            .forEach { found += list(it.id).filter(::isAudio) }
        return found.sortedByDescending { it.lastModifiedMillis }.take(limit)
    }
}

/** Lists a persisted folder grant through the Storage Access Framework. Blocking; call on IO. */
class VoiceNoteFolderLister(private val resolver: ContentResolver) {
    fun newest(tree: Uri, limit: Int = 60): List<VoiceNoteFile> {
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        return VoiceNoteFolders.newest(rootId, limit) { id -> children(tree, id) }
            .map { VoiceNoteFile(DocumentsContract.buildDocumentUriUsingTree(tree, it.id), it.name, it.lastModifiedMillis, it.sizeBytes) }
    }

    private fun children(tree: Uri, parentId: String): List<FolderEntry> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val projection = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_LAST_MODIFIED, Document.COLUMN_SIZE
        )
        return resolver.query(uri, projection, null, null, null)?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        FolderEntry(
                            id = c.getString(0) ?: continue,
                            name = c.getString(1).orEmpty(),
                            isDirectory = c.getString(2) == Document.MIME_TYPE_DIR,
                            lastModifiedMillis = if (c.isNull(3)) 0L else c.getLong(3),
                            sizeBytes = if (c.isNull(4)) 0L else c.getLong(4)
                        )
                    )
                }
            }
        }.orEmpty()
    }
}
