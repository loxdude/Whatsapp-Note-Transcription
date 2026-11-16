package com.local.voicenotes.model

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/** Cold-start fast path: verified records, deferred verification and its lease handling. */
class ModelFileCacheFastPathTest {
    @get:Rule val temporary = TemporaryFolder()

    private lateinit var root: File
    private lateinit var pool: ExecutorService
    private val held = mutableListOf<ModelFileCache.Lease>()

    @Before fun setUp() {
        root = File(temporary.root, "litert-models")
        pool = Executors.newCachedThreadPool()
    }

    @After fun tearDown() {
        held.forEach { it.close() }
        pool.shutdownNow()
    }

    /** A new instance per call models a fresh process (nothing trusted in memory). */
    private fun cache() = ModelFileCache(root, bufferSize = BUFFER)

    private fun fullAcquire(source: Source): File = cache().acquire(source).also { held += it }.file

    private fun recorded(source: Source): ModelFileCache.RecordedBlob? =
        cache().acquireRecorded(source)?.also { held += it.lease }

    private fun recordFiles() = File(root, "records").listFiles().orEmpty().toList()

    @Test fun fastPathIsTakenWithoutOpeningTheSourceWhenRecordStampAndBlobMatch() {
        val source = Source(modelBytes(1), stamp = "100:1700")
        val blob = fullAcquire(source)
        assertEquals(1, recordFiles().size)
        val opensBefore = source.opens.get()

        val fast = recorded(source)

        assertNotNull(fast)
        assertEquals(blob, fast!!.file)
        assertEquals("no source open on the critical path", opensBefore, source.opens.get())
        assertArrayEquals(source.content, fast.file.readBytes())
        assertTrue(fast.verify(pool))
        assertEquals("verification hashes the source once", opensBefore + 1, source.opens.get())
        assertNotNull("a passed verification keeps the record", recorded(source))
    }

    @Test fun fastPathIsNotTakenWithoutAStamp() {
        val source = Source(modelBytes(1), stamp = null)
        fullAcquire(source)
        assertTrue("no record without a stamp", recordFiles().isEmpty())
        assertNull(recorded(source))

        source.stamp = "s"
        fullAcquire(source)
        source.stamp = null
        assertNull("a recorded source that lost its stamp", recorded(source))
    }

    @Test fun fastPathIsNotTakenWhenTheStampChanged() {
        val source = Source(modelBytes(1), stamp = "100:1700")
        fullAcquire(source)
        source.stamp = "100:1800"
        assertNull(recorded(source))
        val opens = source.opens.get()
        // The full path keeps working and re-records the new stamp.
        fullAcquire(source)
        assertTrue(source.opens.get() > opens)
        assertNotNull(recorded(source))
    }

    @Test fun fastPathIsNotTakenWhenBlobLengthOrMtimeChanged() {
        val source = Source(modelBytes(1), stamp = "s")
        val blob = fullAcquire(source)
        val mtime = blob.lastModified()

        assertTrue(blob.setLastModified(mtime - 60_000))
        assertNull("mtime changed", recorded(source))

        blob.writeBytes(source.content + byteArrayOf(1))
        blob.setLastModified(mtime)
        assertNull("length changed", recorded(source))

        blob.delete()
        assertNull("blob gone", recorded(source))
        assertArrayEquals(source.content, fullAcquire(source).readBytes())
    }

    @Test fun deferredVerificationDetectsSameLengthBlobCorruptionWithPreservedMtime() {
        val source = Source(modelBytes(1), stamp = "s")
        val blob = fullAcquire(source)
        val mtime = blob.lastModified()
        blob.writeBytes(blob.readBytes().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() })
        blob.setLastModified(mtime)
        val corruptBefore = ModelFileCache.corruptBlobsDetected

        val fast = recorded(source)
        assertNotNull("metadata alone cannot see this; only the verification can", fast)
        assertFalse(fast!!.verify(pool))

        assertEquals(corruptBefore + 1, ModelFileCache.corruptBlobsDetected)
        assertTrue("failed verification removes the record", recordFiles().isEmpty())
        assertNull(recorded(source))
        // The full path repairs the copy (even while the bad blob is still leased).
        assertArrayEquals(source.content, fullAcquire(source).readBytes())
        assertNotNull(recorded(source))
    }

    @Test fun deferredVerificationDetectsChangedSourceWithIdenticalStamp() {
        val original = modelBytes(1)
        val source = Source(original, stamp = "same-size-same-mtime")
        fullAcquire(source)
        val edited = modelBytes(2)
        source.content = edited

        val fast = recorded(source)
        assertNotNull("residual risk: identical stamp passes the cheap check", fast)
        assertFalse(fast!!.verify(pool))
        assertNull(recorded(source))
        assertArrayEquals(edited, fullAcquire(source).readBytes())
    }

    @Test fun stampChangedDuringVerificationFailsIt() {
        val source = Source(modelBytes(1), stamp = "a")
        fullAcquire(source)
        val fast = recorded(source)!!
        source.onOpen = { source.stamp = "b" }
        assertFalse(fast.verify(pool))
    }

    @Test fun backgroundVerificationHoldsItsOwnLeaseAndReportsOutcome() {
        val source = Source(modelBytes(1), stamp = "s")
        fullAcquire(source).also { held.forEach { lease -> lease.close() }; held.clear() }
        val fast = recorded(source)!!
        val finished = CountDownLatch(1)
        val background = fast.startVerification(pool) { _, _, _ -> finished.countDown() }

        assertEquals(BackgroundVerification.Outcome.VERIFIED, background.await())
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        assertEquals("only the caller's lease is left", 1, ModelFileCache.openLeases(fast.file))
        fast.lease.close()
        assertEquals(0, ModelFileCache.openLeases(fast.file))
    }

    @Test fun cancellingARunningVerificationReleasesItsLeaseAndKeepsTheRecord() {
        val source = Source(modelBytes(1), stamp = "s")
        fullAcquire(source).also { held.forEach { lease -> lease.close() }; held.clear() }
        val fast = recorded(source)!!
        val reading = CountDownLatch(1)
        val release = CountDownLatch(1)
        source.onRead = { reading.countDown(); release.await(5, TimeUnit.SECONDS) }

        val background = fast.startVerification(pool)
        assertTrue(reading.await(5, TimeUnit.SECONDS))
        assertEquals(2, ModelFileCache.openLeases(fast.file))
        background.cancel()
        release.countDown()

        assertEquals(BackgroundVerification.Outcome.CANCELLED, background.await())
        assertEquals(1, ModelFileCache.openLeases(fast.file))
        assertEquals("cancellation is not a failed verification", 1, recordFiles().size)
        fast.lease.close()
        assertEquals(0, ModelFileCache.openLeases(fast.file))
    }

    @Test fun cancellingAVerificationThatNeverStartedReleasesItsLeaseImmediately() {
        val source = Source(modelBytes(1), stamp = "s")
        fullAcquire(source).also { held.forEach { lease -> lease.close() }; held.clear() }
        val fast = recorded(source)!!
        val opens = source.opens.get()
        val queued = mutableListOf<Runnable>()
        val background = fast.startVerification(Executor { queued += it })
        assertEquals(2, ModelFileCache.openLeases(fast.file))

        background.cancel()

        assertEquals(BackgroundVerification.Outcome.CANCELLED, background.result)
        assertEquals(1, ModelFileCache.openLeases(fast.file))
        queued.forEach { it.run() } // a late start is a no-op
        assertEquals(1, ModelFileCache.openLeases(fast.file))
        assertEquals(opens, source.opens.get())
        fast.lease.close()
    }

    @Test fun cancellationThroughCheckCancelledPropagatesFromVerify() {
        val source = Source(modelBytes(1), stamp = "s")
        fullAcquire(source)
        val fast = recorded(source)!!
        var checks = 0
        try {
            fast.verify(pool) { if (++checks > 3) throw Stop() }
            fail("expected cancellation")
        } catch (_: Stop) {
        }
        assertEquals("cancellation keeps the record", 1, recordFiles().size)
        // ...but the fast path stays marked as unverified, so it is not taken again.
        assertNull(recorded(source))
    }

    @Test fun fastPathThatNeverFinishedVerificationIsNotTakenOnTheNextColdStart() {
        val source = Source(modelBytes(1), stamp = "s")
        fullAcquire(source)
        val fast = recorded(source)
        assertNotNull(fast)
        assertTrue("marker is persisted before the caller builds a session", cache().fastPathMarked(source))
        // The process dies here (e.g. native code crashed on the unverified bytes): no verify().

        val opens = source.opens.get()
        assertNull("next cold start must take the full path", recorded(source))
        assertEquals("declining the fast path reads nothing", opens, source.opens.get())
        assertEquals("the record itself is kept", 1, recordFiles().size)

        // The full path re-verifies and clears the marker, so later starts are fast again.
        fullAcquire(source)
        assertFalse(cache().fastPathMarked(source))
        val again = recorded(source)
        assertNotNull(again)
        assertTrue(again!!.verify(pool))
        assertFalse("a passed verification clears the marker", cache().fastPathMarked(source))
        assertNotNull(recorded(source))
    }

    @Test fun failedVerificationKeepsTheMarkerUntilTheFullPathRepairsTheCopy() {
        val source = Source(modelBytes(1), stamp = "s")
        fullAcquire(source)
        val fast = recorded(source)!!
        source.onOpen = { throw IllegalStateException("provider crashed") }
        assertFalse(fast.verify(pool))
        assertTrue(cache().fastPathMarked(source))
        assertNull("failed source cannot re-enter the recorded fast path", recorded(source))
        source.onOpen = {}
        fullAcquire(source)
        assertFalse(cache().fastPathMarked(source))
    }

    @Test fun backgroundVerificationThatPassesClearsTheMarker() {
        val source = Source(modelBytes(1), stamp = "s")
        fullAcquire(source)
        val fast = recorded(source)!!
        assertEquals(BackgroundVerification.Outcome.VERIFIED, fast.startVerification(pool).await())
        assertFalse(cache().fastPathMarked(source))
        assertNotNull(recorded(source))
    }

    @Test fun legacyCacheWithoutRecordsStillWorksAndGainsARecord() {
        val source = Source(modelBytes(1), stamp = "s")
        val blob = fullAcquire(source)
        File(root, "records").deleteRecursively()

        assertNull(recorded(source))
        assertEquals(blob, fullAcquire(source))
        assertNotNull(recorded(source))
    }

    @Test fun damagedOrForeignRecordIsIgnored() {
        val source = Source(modelBytes(1), stamp = "s")
        fullAcquire(source)
        val record = recordFiles().single()
        record.writeText("garbage")
        assertNull(recorded(source))
        fullAcquire(source)
        assertNotNull(recorded(source))
        // A record whose digest no longer matches the ref (another model was resolved).
        File(root, "refs").listFiles()!!.single().writeText("0".repeat(64))
        assertNull(recorded(source))
    }

    @Test fun recordIsNotWrittenWhenTheStampChangedDuringTheCopy() {
        val stamps = AtomicInteger()
        val source = Source(modelBytes(1), stamp = "x")
        source.stampProvider = { "t" + stamps.incrementAndGet() }
        try { cache().acquire(source).close(); fail() } catch (_: ModelFileCache.SourceChangedException) {}
        assertTrue(recordFiles().isEmpty())
        assertTrue("records are staged in tmp and renamed", File(root, "tmp").listFiles().orEmpty().isEmpty())
    }

    private class Stop : RuntimeException()

    private class Source(
        @Volatile var content: ByteArray,
        @Volatile var stamp: String?
    ) : ModelFileCache.Source {
        override val key = "content://downloads/model"
        override val expectedSize = content.size.toLong()
        val opens = AtomicInteger()
        @Volatile var onOpen: () -> Unit = {}
        @Volatile var onRead: () -> Unit = {}
        @Volatile var stampProvider: (() -> String?)? = null

        override fun stamp(): String? = stampProvider?.invoke() ?: stamp

        override fun open(): InputStream {
            opens.incrementAndGet()
            onOpen()
            val data = content
            return object : InputStream() {
                private var position = 0
                override fun read(): Int = throw UnsupportedOperationException()
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    onRead()
                    if (position >= data.size) return -1
                    val count = minOf(length, data.size - position, 1000)
                    System.arraycopy(data, position, buffer, offset, count)
                    position += count
                    return count
                }
            }
        }
    }

    private companion object {
        const val BUFFER = 4096
        const val SIZE = 48 * 1024 + 77
        fun modelBytes(seed: Int): ByteArray = Random(seed).nextBytes(SIZE)
    }
}
