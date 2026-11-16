package com.local.voicenotes.model

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * Content-addressed local copy of a model whose source can only be streamed
 * (e.g. a Storage Access Framework URI), for runtimes that need a real path.
 *
 * Layout below [directory]:
 *  - `blobs/<sha256>.tflite`: complete copies. A blob only gets its final name by an
 *    atomic rename after every byte was written, fsynced, counted and hashed, so a
 *    partial/failed/interrupted copy can never appear under a blob name.
 *  - `refs/<sha256(key)>`: the digest last resolved for a source key (cleanup only).
 *  - `tmp/`: in-flight copies with unique names; deleted on failure and, as leftovers
 *    of killed processes, at the start of the next resolution.
 *
 * Every [resolve] streams and hashes the whole source and only reuses a blob whose
 * name equals that digest, so a source whose bytes changed while its URI and size
 * stayed the same is never served from stale bytes. A cache hit costs one sequential
 * read of the source and no writes. Resolutions of one directory are serialized
 * in-process (lock) and across processes ([FileChannel.lock]).
 */
class ModelFileCache(
    directory: File,
    private val bufferSize: Int = DEFAULT_BUFFER_SIZE
) {
    interface Source {
        /** Stable identity of the source (e.g. the imported model id). */
        val key: String
        /** Size recorded at import; a source of any other length is rejected. */
        val expectedSize: Long
        fun open(): InputStream
        /**
         * Optional cheap change marker (e.g. provider size + last-modified). When it
         * differs before and after a copy, the copy is discarded as possibly torn.
         */
        fun stamp(): String? = null
    }

    class SourceChangedException(message: String) : IOException(message)

    /**
     * A resolved blob. While any lease on it is open in this process, cleanup will not
     * delete it, so a path handed to a native loader stays valid for the session.
     */
    class Lease internal constructor(val file: File) : AutoCloseable {
        private var released = false

        override fun close() {
            synchronized(leases) {
                if (released) return
                released = true
                val remaining = (leases[file.path] ?: 1) - 1
                if (remaining <= 0) leases.remove(file.path) else leases[file.path] = remaining
            }
        }
    }

    // Canonical so aliased paths to the same directory share one in-process lock.
    private val root = runCatching { directory.canonicalFile }.getOrElse { directory.absoluteFile }
    private val blobs = File(root, "blobs")
    private val refs = File(root, "refs")
    private val tmp = File(root, "tmp")
    private val lockFile = File(root, ".lock")

    /**
     * Returns a lease on a complete local file whose bytes hash identically to the
     * source's current content. [checkCancelled] runs between buffers and may throw to
     * abort; aborted or failed copies are removed and never published. Close the lease
     * once the file is no longer used.
     */
    fun acquire(
        source: Source,
        checkCancelled: () -> Unit = {},
        /**
         * Path of a blob the caller already has open and verified (e.g. backing a live
         * session). A cache hit on any *other* blob is re-hashed before it is returned, so
         * same-length corruption of a private copy is replaced instead of loaded.
         */
        trustedBlobPath: String? = null
    ): Lease {
        require(source.expectedSize > 0) { "The model has no recorded size." }
        return withDirectoryLock(checkCancelled) {
            val blob = resolveLocked(source, checkCancelled, trustedBlobPath)
            synchronized(leases) { leases[blob.path] = (leases[blob.path] ?: 0) + 1 }
            val lease = Lease(blob)
            // Also on hits, so blobs whose last lease was released since get reclaimed.
            runCatching { collectUnreferencedBlobs() }
            lease
        }
    }

    private fun resolveLocked(source: Source, checkCancelled: () -> Unit, trustedBlobPath: String?): File {
        purgeIncompleteAndLegacy()
        val refFile = File(refs, sha256Hex(source.key.encodeToByteArray()))
        val previous = readRef(refFile)
        var sourceDigest: String? = null
        if (previous != null && isCompleteBlob(blobFor(previous), source.expectedSize)) {
            // Verify instead of trusting URI/size: hash only, no writes on a hit.
            val digest = source.open().use { hashStream(it, source.expectedSize, null, checkCancelled) }
            sourceDigest = digest
            val known = blobFor(digest)
            if (isCompleteBlob(known, source.expectedSize) &&
                blobIntact(known, digest, source.expectedSize, checkCancelled, trustedBlobPath)
            ) {
                if (digest != previous) writeRef(refFile, digest)
                return known
            }
        }
        // Changed or never cached: one pass that copies and hashes together.
        val blob = copyToBlob(source, sourceDigest, checkCancelled)
        writeRef(refFile, blob.name.removeSuffix(BLOB_SUFFIX))
        return blob
    }

    private fun copyToBlob(source: Source, expectedDigest: String?, checkCancelled: () -> Unit): File {
        tmp.mkdirs()
        val partial = File.createTempFile("copy-", ".partial", tmp)
        try {
            val stampBefore = source.stamp()
            val digest = source.open().use { input ->
                FileOutputStream(partial).use { output ->
                    hashStream(input, source.expectedSize, output, checkCancelled).also {
                        output.flush()
                        output.fd.sync()
                    }
                }
            }
            if (partial.length() != source.expectedSize) {
                throw IOException("The cached model copy is incomplete.")
            }
            val stampAfter = source.stamp()
            if ((stampBefore != null || stampAfter != null) && stampBefore != stampAfter) {
                throw SourceChangedException("The model file changed while it was being copied. Try again.")
            }
            if (expectedDigest != null && digest != expectedDigest) {
                throw SourceChangedException("The model file changed while it was being verified. Try again.")
            }
            // Always publish the freshly hashed bytes, replacing any blob of that name: an
            // existing one reached this point only because it was missing, short or failed
            // its integrity check. Open handles keep the replaced inode alive.
            val blob = blobFor(digest)
            blobs.mkdirs()
            moveAtomically(partial, blob)
            syncDirectory(blobs)
            return blob
        } catch (error: Throwable) {
            partial.delete()
            throw error
        }
    }

    /**
     * Streams exactly [expectedSize] bytes into the digest (and [sink]); fails if the
     * source ends early or has extra bytes.
     */
    private fun hashStream(
        input: InputStream,
        expectedSize: Long,
        sink: FileOutputStream?,
        checkCancelled: () -> Unit
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(bufferSize)
        var total = 0L
        while (true) {
            checkCancelled()
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            total += read
            if (total > expectedSize) {
                throw SourceChangedException("The model file is larger than when it was imported. Re-import it.")
            }
            digest.update(buffer, 0, read)
            sink?.write(buffer, 0, read)
        }
        if (total != expectedSize) {
            throw SourceChangedException(
                "The model file ended after $total of $expectedSize bytes. Re-import it if it changed."
            )
        }
        return digest.digest().toHex()
    }

    private fun isCompleteBlob(blob: File, size: Long) = blob.isFile && blob.length() == size

    /**
     * Re-hashes a cached blob unless it is the caller's [trustedBlobPath]. A blob whose bytes
     * no longer match its digest name is deleted (unless leased) so the caller re-copies it.
     */
    private fun blobIntact(
        blob: File,
        digest: String,
        size: Long,
        checkCancelled: () -> Unit,
        trustedBlobPath: String?
    ): Boolean {
        if (trustedBlobPath != null && File(trustedBlobPath).absoluteFile == blob.absoluteFile) return true
        val actual = try {
            blob.inputStream().use { hashStream(it, size, null, checkCancelled) }
        } catch (_: SourceChangedException) {
            null
        }
        if (actual == digest) return true
        val leased = synchronized(leases) { blob.path in leases }
        if (!leased) blob.delete()
        corruptBlobsDetected++
        return false
    }

    private fun blobFor(digest: String) = File(blobs, digest + BLOB_SUFFIX)

    private fun readRef(file: File): String? = runCatching {
        file.readText().trim().takeIf { DIGEST.matches(it) }
    }.getOrNull()

    private fun writeRef(file: File, digest: String) {
        refs.mkdirs()
        tmp.mkdirs()
        val staged = File.createTempFile("ref-", ".partial", tmp)
        try {
            FileOutputStream(staged).use { output ->
                output.write(digest.encodeToByteArray())
                output.fd.sync()
            }
            moveAtomically(staged, file)
            syncDirectory(refs)
        } catch (error: Throwable) {
            staged.delete()
            throw error
        }
    }

    /** Deletes blobs that no source points at and no open lease uses. */
    private fun collectUnreferencedBlobs() {
        val referenced = refs.listFiles().orEmpty().mapNotNullTo(HashSet()) { readRef(it) }
        val leased = synchronized(leases) { HashSet(leases.keys) }
        blobs.listFiles().orEmpty().forEach { blob ->
            if (blob.name.removeSuffix(BLOB_SUFFIX) !in referenced && blob.path !in leased) blob.delete()
        }
    }

    /**
     * Runs under the directory lock: no other resolver can be copying, so anything in
     * tmp/ is an abandoned copy. Files in the root (except the lock) are the legacy
     * `<id>.tflite`/`<id>.partial` layout, which was validated by size only.
     */
    private fun purgeIncompleteAndLegacy() {
        tmp.listFiles().orEmpty().forEach { it.deleteRecursively() }
        root.listFiles().orEmpty().forEach { file ->
            if (file.isFile && file != lockFile) file.delete()
        }
        blobs.listFiles().orEmpty().forEach { blob ->
            if (!blob.isFile || !BLOB_NAME.matches(blob.name)) blob.deleteRecursively()
        }
    }

    /**
     * Serializes resolutions in-process and across processes. Waiting for either lock
     * polls [checkCancelled], so a cancelled preparation does not block behind another
     * copy of a large model.
     */
    private fun <T> withDirectoryLock(checkCancelled: () -> Unit, block: () -> T): T {
        root.mkdirs()
        if (!root.isDirectory) throw IOException("Cannot create the model cache directory.")
        val lock = inProcessLocks.computeIfAbsent(root.path) { ReentrantLock() }
        while (!lock.tryLock(LOCK_POLL_MILLIS, TimeUnit.MILLISECONDS)) checkCancelled()
        try {
            return RandomAccessFile(lockFile, "rw").use { file ->
                var fileLock = file.channel.tryLock()
                while (fileLock == null) {
                    checkCancelled()
                    Thread.sleep(LOCK_POLL_MILLIS)
                    fileLock = file.channel.tryLock()
                }
                fileLock.use { block() }
            }
        } finally {
            lock.unlock()
        }
    }

    private fun moveAtomically(from: File, to: File) {
        try {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            if (!from.renameTo(to)) throw IOException("Could not finalize the local model cache.")
        }
    }

    private fun syncDirectory(directory: File) {
        // Makes the rename durable; not supported on every filesystem, which is harmless
        // because a lost rename only causes a re-copy, never a partial blob.
        runCatching { FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) } }
    }

    companion object {
        const val DEFAULT_BUFFER_SIZE = 8 * 1024 * 1024
        private const val LOCK_POLL_MILLIS = 50L
        /** Diagnostics/test counter: cached blobs whose bytes no longer matched their digest. */
        @Volatile var corruptBlobsDetected = 0
            internal set
        private const val BLOB_SUFFIX = ".tflite"
        private val DIGEST = Regex("[0-9a-f]{64}")
        private val BLOB_NAME = Regex("[0-9a-f]{64}\\.tflite")
        private val inProcessLocks = ConcurrentHashMap<String, ReentrantLock>()
        /** Open lease count per blob path, shared by all cache instances in the process. */
        private val leases = HashMap<String, Int>()

        private fun sha256Hex(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
        private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    }
}
