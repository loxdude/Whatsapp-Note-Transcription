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
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
 *  - `records/<sha256(key)>`: "verified record" written after a full verification: digest,
 *    source stamp and blob length/mtime. Only a hint for [acquireRecorded]; never proof.
 *  - `fastpath/<sha256(key)>`: "fast path in flight" marker, written durably by [acquireRecorded]
 *    before the caller builds anything from the unverified blob and removed once that blob
 *    was verified (or the key was fully re-resolved). A marker left behind means the process
 *    died with unverified bytes in use (possibly crashing on them), so the next
 *    [acquireRecorded] for the key declines and the caller takes the full path.
 *  - `tmp/`: in-flight copies with unique names; deleted on failure and, as leftovers
 *    of killed processes, at the start of the next resolution.
 *
 * Every [resolve] streams and hashes the whole source and only reuses a blob whose
 * name equals that digest, so a source whose bytes changed while its URI and size
 * stayed the same is never served from stale bytes. A cache hit costs one sequential
 * read of the source and no writes. Resolutions of one directory are serialized
 * in-process (lock) and across processes ([FileChannel.lock]).
 *
 * [acquireRecorded] is the cold-start fast path: when the persisted record still matches the
 * source stamp and the blob's length/mtime, it returns the blob without hashing anything and
 * the caller must run [RecordedBlob.verify] (typically via [RecordedBlob.startVerification])
 * and must not publish any result derived from the blob before that check succeeded.
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
    private val records = File(root, "records")
    private val fastPathMarkers = File(root, "fastpath")
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
        /**
         * Path of a blob the caller already has open and verified (e.g. backing a live
         * session). A cache hit on any *other* blob is re-hashed before it is returned, so
         * same-length corruption of a private copy is replaced instead of loaded.
         */
        trustedBlobPath: String? = null,
        checkCancelled: () -> Unit = {}
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
        val keyName = keyName(source)
        val refFile = File(refs, keyName)
        val recordFile = File(records, keyName)
        val previous = readRef(refFile)
        var sourceDigest: String? = null
        if (previous != null && isCompleteBlob(blobFor(previous), source.expectedSize)) {
            // Verify instead of trusting URI/size: hash only, no writes on a hit.
            val stampBefore = source.stamp()
            val digest = source.open().use { hashStream(it, source.expectedSize, null, checkCancelled) }
            sourceDigest = digest
            val known = blobFor(digest)
            val trusted = isTrusted(known, trustedBlobPath)
            if (isCompleteBlob(known, source.expectedSize) &&
                blobIntact(known, digest, source.expectedSize, checkCancelled, trustedBlobPath)
            ) {
                if (digest != previous) writeRef(refFile, digest)
                val stamp = stampBefore?.takeIf { it == source.stamp() }
                updateRecord(recordFile, digest, known, source.expectedSize, stamp, blobHashed = !trusted)
                // Verified (or the caller's verified live blob): the fast path is safe again.
                clearFastPathMarker(keyName)
                return known
            }
        }
        // Changed or never cached: one pass that copies and hashes together.
        val (blob, stamp) = copyToBlob(source, sourceDigest, checkCancelled)
        val digest = blob.name.removeSuffix(BLOB_SUFFIX)
        writeRef(refFile, digest)
        updateRecord(recordFile, digest, blob, source.expectedSize, stamp, blobHashed = true)
        clearFastPathMarker(keyName)
        return blob
    }

    /**
     * Fast path for cold starts. Returns a lease on the blob recorded for [source] *without
     * hashing anything* when the verified record exists, [Source.stamp] is non-null and equal to
     * the recorded stamp, the ref still points at the recorded digest and the blob's length and
     * mtime are unchanged. Returns null otherwise; the caller then uses [acquire].
     *
     * The returned bytes are NOT verified. Callers must run [RecordedBlob.verify] and must not
     * publish anything derived from the blob unless it returned true. Residual risk: a source
     * rewritten with identical size AND identical provider lastModified (or a blob modified
     * with its mtime restored) passes this check and is only caught by that verification,
     * which therefore has to gate every result.
     *
     * Before returning, a "fast path in flight" marker is persisted for the key; only a passed
     * [RecordedBlob.verify] (or a full resolution) removes it. While it exists this returns
     * null, so bytes that crash the process before their verification finished cannot be
     * loaded by the fast path again on the next cold start.
     */
    fun acquireRecorded(source: Source, checkCancelled: () -> Unit = {}): RecordedBlob? {
        require(source.expectedSize > 0) { "The model has no recorded size." }
        val stamp = source.stamp() ?: return null
        val keyName = keyName(source)
        return withDirectoryLock(checkCancelled) {
            purgeIncompleteAndLegacy()
            val recordFile = File(records, keyName)
            val recordText = readText(recordFile) ?: return@withDirectoryLock null
            val record = Record.decode(recordText) ?: return@withDirectoryLock null
            val blob = blobFor(record.digest)
            val usable = record.stamp == stamp &&
                record.expectedSize == source.expectedSize &&
                record.blobLength == source.expectedSize &&
                readRef(File(refs, keyName)) == record.digest &&
                blob.isFile &&
                blob.length() == record.blobLength &&
                blob.lastModified() == record.blobModified
            if (!usable) return@withDirectoryLock null
            val marker = File(fastPathMarkers, keyName)
            // An earlier fast path never got verified (e.g. the process died): full path.
            if (marker.exists()) return@withDirectoryLock null
            val token = UUID.randomUUID().toString()
            // Must be durable before anything is built from the unverified bytes.
            try {
                writeAtomically(fastPathMarkers, marker, token)
            } catch (error: IOException) {
                marker.delete()
                return@withDirectoryLock null
            }
            synchronized(leases) { leases[blob.path] = (leases[blob.path] ?: 0) + 1 }
            RecordedBlob(Lease(blob), record, recordText, recordFile, source, marker, token)
        }
    }

    /**
     * Blob returned by [acquireRecorded] whose content has not been verified in this process.
     * [lease] belongs to the caller (e.g. the session built on the file).
     */
    inner class RecordedBlob internal constructor(
        val lease: Lease,
        private val record: Record,
        private val recordText: String,
        private val recordFile: File,
        private val source: Source,
        private val marker: File,
        private val markerToken: String
    ) {
        val file: File get() = lease.file
        val digest: String get() = record.digest
        /** The source stamp that matched the record. */
        val stamp: String get() = record.stamp

        /**
         * Full check: the source currently hashes to [digest] (and its stamp did not change
         * meanwhile) AND the blob hashes to [digest]. The blob is hashed on [executor] while
         * the source is hashed on the calling thread. On success the fast-path marker written
         * by [acquireRecorded] is removed. On a mismatch or any error other than
         * cancellation the record is deleted (so the next cold start takes the full path) and
         * false is returned. Exceptions thrown by [checkCancelled] propagate unchanged and keep
         * the record. The caller must hold a lease on [file] for the duration.
         */
        fun verify(executor: Executor, checkCancelled: () -> Unit = {}): Boolean {
            val cancelled = AtomicBoolean()
            val abort = AtomicBoolean()
            val guard: () -> Unit = {
                try {
                    checkCancelled()
                } catch (error: Throwable) {
                    cancelled.set(true)
                    throw error
                }
                if (abort.get()) throw IOException("Verification aborted.")
            }
            val blobTask = FutureTask<String?> {
                try {
                    file.inputStream().use { hashStream(it, record.blobLength, null, guard) }
                } catch (error: Exception) {
                    if (cancelled.get()) throw error
                    null
                }
            }
            var submitted = false
            try {
                var sourceOk = false
                var blobOk = false
                try {
                    executor.execute(blobTask)
                    submitted = true
                    val sourceDigest = try {
                        source.open().use { hashStream(it, source.expectedSize, null, guard) }
                    } catch (error: Exception) {
                        if (cancelled.get()) throw error
                        null
                    }
                    sourceOk = sourceDigest == record.digest && source.stamp() == record.stamp
                    if (!sourceOk) abort.set(true) // the outcome is already known
                    val blobDigest = try {
                        blobTask.get()
                    } catch (error: ExecutionException) {
                        throw error.cause ?: error
                    }
                    blobOk = blobDigest == record.digest
                } catch (error: Throwable) {
                    if (cancelled.get() || error is VirtualMachineError) throw error
                    // Any other failure counts as failed verification.
                }
                if (sourceOk && blobOk) {
                    clearMarker()
                    return true
                }
                if (sourceOk) corruptBlobsDetected++
                invalidateRecord()
                return false
            } finally {
                // Never return while the blob reader still runs: the caller's lease must
                // cover every read. It stops at the next buffer once aborted.
                abort.set(true)
                if (submitted) runCatching { blobTask.get() }
            }
        }

        /**
         * Starts [verify] on [executor], holding its own lease on [file] until the check has
         * ended or was cancelled before it started. [executor] must be able to run two tasks
         * at once (source and blob are hashed concurrently), e.g. a cached thread pool.
         */
        fun startVerification(
            executor: Executor,
            onFinished: (BackgroundVerification, BackgroundVerification.Outcome, Long) -> Unit = { _, _, _ -> }
        ): BackgroundVerification = BackgroundVerification(executor, retain(lease), onFinished) { checkCancelled ->
            verify(executor, checkCancelled)
        }

        /** Deletes the record unless it was rewritten meanwhile (e.g. by a full resolution). */
        fun invalidateRecord() {
            if (readText(recordFile) == recordText) recordFile.delete()
        }

        /** Removes this fast path's marker, unless a newer fast path of the key owns it now. */
        private fun clearMarker() {
            if (readText(marker) == markerToken && marker.delete()) syncDirectory(fastPathMarkers)
        }
    }

    /** Must hold the directory lock. */
    private fun clearFastPathMarker(keyName: String) {
        if (File(fastPathMarkers, keyName).delete()) syncDirectory(fastPathMarkers)
    }

    /** Test/diagnostics hook: whether a fast path of [source] is marked as in flight. */
    internal fun fastPathMarked(source: Source): Boolean = File(fastPathMarkers, keyName(source)).exists()

    private fun retain(lease: Lease): Lease {
        synchronized(leases) {
            val held = leases[lease.file.path] ?: 0
            check(held > 0) { "The blob lease was already released." }
            leases[lease.file.path] = held + 1
        }
        return Lease(lease.file)
    }

    /**
     * Writes a fresh record when [blob] was hashed in this resolution and the source stamp is
     * known and was stable; otherwise keeps an existing record only if it still describes
     * exactly this state, and deletes it if not.
     */
    private fun updateRecord(file: File, digest: String, blob: File, size: Long, stamp: String?, blobHashed: Boolean) {
        val current = Record(digest, size, blob.length(), blob.lastModified(), stamp ?: "")
        if (stamp == null || stamp.contains('\n')) {
            file.delete()
            return
        }
        if (!blobHashed) {
            if (readText(file)?.let(Record::decode) != current) file.delete()
            return
        }
        val text = current.encode()
        if (readText(file) == text) return
        runCatching { writeAtomically(records, file, text) }.onFailure { file.delete() }
    }

    internal data class Record(
        val digest: String,
        val expectedSize: Long,
        val blobLength: Long,
        val blobModified: Long,
        val stamp: String
    ) {
        fun encode() = listOf(VERSION, digest, expectedSize, blobLength, blobModified, stamp).joinToString("\n")

        companion object {
            private const val VERSION = "v1"

            fun decode(text: String): Record? {
                val lines = text.split('\n')
                if (lines.size != 6 || lines[0] != VERSION || !DIGEST.matches(lines[1])) return null
                val stamp = lines[5].takeIf { it.isNotEmpty() } ?: return null
                return Record(
                    lines[1],
                    lines[2].toLongOrNull() ?: return null,
                    lines[3].toLongOrNull() ?: return null,
                    lines[4].toLongOrNull() ?: return null,
                    stamp
                )
            }
        }
    }

    private fun copyToBlob(source: Source, expectedDigest: String?, checkCancelled: () -> Unit): Pair<File, String?> {
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
            return blob to stampBefore
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

    private fun isTrusted(blob: File, trustedBlobPath: String?) =
        trustedBlobPath != null && File(trustedBlobPath).absoluteFile == blob.absoluteFile

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
        if (isTrusted(blob, trustedBlobPath)) return true
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

    private fun keyName(source: Source) = sha256Hex(source.key.encodeToByteArray())

    private fun readText(file: File): String? = runCatching { file.readText() }.getOrNull()

    private fun readRef(file: File): String? = readText(file)?.trim()?.takeIf { DIGEST.matches(it) }

    private fun writeRef(file: File, digest: String) = writeAtomically(refs, file, digest)

    /** Staged in tmp/, fsynced and renamed, so readers see the old or the new text only. */
    private fun writeAtomically(directory: File, file: File, text: String) {
        directory.mkdirs()
        tmp.mkdirs()
        val staged = File.createTempFile("meta-", ".partial", tmp)
        try {
            FileOutputStream(staged).use { output ->
                output.write(text.encodeToByteArray())
                output.fd.sync()
            }
            moveAtomically(staged, file)
            syncDirectory(directory)
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

        /** Test hook: open leases on [file] in this process. */
        internal fun openLeases(file: File): Int = synchronized(leases) { leases[file.path] ?: 0 }

        private fun sha256Hex(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
        private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    }
}
