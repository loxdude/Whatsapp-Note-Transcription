package com.local.voicenotes.model

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

class ModelFileCacheTest {
    @get:Rule val temporary = TemporaryFolder()

    private lateinit var root: File
    private lateinit var cache: ModelFileCache
    private val leases = mutableListOf<ModelFileCache.Lease>()

    @Before fun setUp() {
        root = File(temporary.root, "litert-models")
        cache = ModelFileCache(root, bufferSize = BUFFER)
    }

    @After fun tearDown() {
        leases.forEach { it.close() }
    }

    private fun acquire(
        source: ModelFileCache.Source,
        target: ModelFileCache = cache,
        checkCancelled: () -> Unit = {}
    ): File = target.acquire(source, checkCancelled).also { synchronized(leases) { leases += it } }.file

    private fun blobs(): List<File> = File(root, "blobs").listFiles().orEmpty().toList()
    private fun tmpFiles(): List<File> = File(root, "tmp").listFiles().orEmpty().toList()

    private fun assertNothingPublished() {
        assertTrue("no blob may be published: " + blobs(), blobs().isEmpty())
        assertTrue("partial copies must be removed: " + tmpFiles(), tmpFiles().isEmpty())
        assertTrue(File(root, "refs").listFiles().orEmpty().isEmpty())
    }

    @Test fun firstAcquireCopiesExactBytesAndHitReusesBlobWithoutRewriting() {
        val source = FakeSource("content://models/a", modelBytes(1))
        val first = acquire(source)
        assertArrayEquals(source.content, first.readBytes())
        first.setLastModified(1_000_000L)

        val second = acquire(source)

        assertEquals(first, second)
        assertEquals("hit must not rewrite the blob", 1_000_000L, second.lastModified())
        assertEquals("one copy pass + one verification pass", 2, source.opens.get())
        assertEquals(1, blobs().size)
        assertTrue(tmpFiles().isEmpty())
    }

    @Test fun equalSizeDifferentContentAtSameUriIsNeverServedFromStaleBytes() {
        val original = modelBytes(1)
        val source = FakeSource("content://models/a", original)
        val stale = acquire(source)

        val edited = original.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        source.content = edited
        val fresh = acquire(source)

        assertEquals(original.size, edited.size)
        assertNotEquals(stale, fresh)
        assertArrayEquals(edited, fresh.readBytes())
        // The old blob is still leased (as by a live session), so it stays intact...
        assertArrayEquals(original, stale.readBytes())
        // ...and is removed once released and the cache is next used.
        leases.first { it.file == stale }.close()
        acquire(source)
        assertFalse(stale.exists())
        assertEquals(listOf(fresh), blobs())
    }

    @Test fun revertedContentResolvesToMatchingBytes() {
        val a = modelBytes(1)
        val b = modelBytes(2)
        val source = FakeSource("content://models/a", a)
        acquire(source)
        source.content = b
        assertArrayEquals(b, acquire(source).readBytes())
        source.content = a
        assertArrayEquals(a, acquire(source).readBytes())
    }

    @Test fun failingCopyPublishesNothingAndALaterCopySucceeds() {
        val source = FakeSource("content://models/a", modelBytes(1)).apply { failAfterBytes = SIZE / 2L }
        expect<IOException> { acquire(source) }
        assertNothingPublished()

        source.failAfterBytes = -1
        assertArrayEquals(source.content, acquire(source).readBytes())
    }

    @Test fun failedRecopyOfChangedSourceDoesNotFallBackToStaleBlob() {
        val source = FakeSource("content://models/a", modelBytes(1))
        acquire(source)
        val edited = modelBytes(2)
        source.content = edited
        source.failAfterBytes = SIZE - 1L
        expect<IOException> { acquire(source) }
        assertTrue(tmpFiles().isEmpty())

        source.failAfterBytes = -1
        assertArrayEquals(edited, acquire(source).readBytes())
    }

    @Test fun truncatedOrGrownSourceIsRejected() {
        val shorter = FakeSource("content://models/a", modelBytes(1).copyOf(SIZE - 1), expectedSize = SIZE.toLong())
        expect<ModelFileCache.SourceChangedException> { acquire(shorter) }
        assertNothingPublished()

        val longer = FakeSource("content://models/a", modelBytes(1) + byteArrayOf(7), expectedSize = SIZE.toLong())
        expect<ModelFileCache.SourceChangedException> { acquire(longer) }
        assertNothingPublished()
    }

    @Test fun truncatedSourceAfterCachingIsRejectedInsteadOfReusingCache() {
        val source = FakeSource("content://models/a", modelBytes(1))
        acquire(source)
        source.content = source.content.copyOf(SIZE - BUFFER)
        expect<ModelFileCache.SourceChangedException> { acquire(source) }
    }

    @Test fun cancelledCopyRemovesPartialAndCancelledVerificationKeepsValidBlob() {
        val source = FakeSource("content://models/a", modelBytes(1))
        var checks = 0
        expect<Cancelled> { acquire(source) { if (++checks > 3) throw Cancelled() } }
        assertNothingPublished()

        val cached = acquire(source)
        checks = 0
        expect<Cancelled> { acquire(source) { if (++checks > 3) throw Cancelled() } }
        assertArrayEquals("verification abort must not damage the blob", source.content, cached.readBytes())
        assertEquals(cached, acquire(source))
    }

    @Test fun leftoversOfKilledProcessesAndLegacySizeOnlyCacheAreNeverUsed() {
        val source = FakeSource("content://models/a", modelBytes(1))
        root.mkdirs()
        // Old layout: <id>.tflite validated only by length, same size, stale bytes.
        val legacy = File(root, "abc.tflite").apply { writeBytes(modelBytes(9)) }
        val legacyPartial = File(root, "abc.partial").apply { writeBytes(ByteArray(10)) }
        val orphan = File(root, "tmp").let { it.mkdirs(); File(it, "copy-1.partial") }.apply { writeBytes(modelBytes(1).copyOf(SIZE / 2)) }
        val bogusBlob = File(root, "blobs").let { it.mkdirs(); File(it, "not-a-digest.tflite") }.apply { writeBytes(ByteArray(3)) }

        val resolved = acquire(source)

        assertArrayEquals(source.content, resolved.readBytes())
        listOf(legacy, legacyPartial, orphan, bogusBlob).forEach { assertFalse(it.path, it.exists()) }
        assertTrue(File(root, "blobs").listFiles()!!.all { it.name.matches(Regex("[0-9a-f]{64}\\.tflite")) })
    }

    @Test fun damagedBlobIsReplacedByAFreshCopy() {
        val source = FakeSource("content://models/a", modelBytes(1))
        val blob = acquire(source)
        blob.writeBytes(source.content.copyOf(SIZE / 3))

        val repaired = acquire(source)

        assertArrayEquals(source.content, repaired.readBytes())
    }

    @Test fun sourceModifiedDuringCopyIsRejectedByStamp() {
        val stamps = AtomicInteger()
        val source = FakeSource("content://models/a", modelBytes(1)).apply {
            stamp = { "mtime-" + stamps.incrementAndGet() }
        }
        expect<ModelFileCache.SourceChangedException> { acquire(source) }
        assertNothingPublished()

        source.stamp = { "stable" }
        assertArrayEquals(source.content, acquire(source).readBytes())
    }

    @Test fun sourceModifiedBetweenVerificationAndCopyIsRejected() {
        val source = FakeSource("content://models/a", modelBytes(1))
        acquire(source)
        val b = modelBytes(2)
        val c = modelBytes(3)
        source.content = b
        // Opens: 1 = first copy, 2 = verification (sees b), 3 = re-copy (sees c).
        source.onOpen = { count -> if (count == 3) source.content = c }

        expect<ModelFileCache.SourceChangedException> { acquire(source) }
        assertTrue(tmpFiles().isEmpty())

        source.onOpen = {}
        assertArrayEquals(c, acquire(source).readBytes())
    }

    @Test fun concurrentAcquiresFromSeveralInstancesAreSerializedAndShareOneCompleteCopy() {
        val source = FakeSource("content://models/a", modelBytes(1))
        val other = ModelFileCache(root, bufferSize = BUFFER)
        val results = runConcurrently(8) { index -> acquire(source, if (index % 2 == 0) cache else other) }

        assertEquals(1, results.toSet().size)
        assertArrayEquals(source.content, results.first().readBytes())
        assertEquals("source reads must never overlap", 1, source.maxConcurrentReads.get())
        assertEquals(1, blobs().size)
        assertTrue(tmpFiles().isEmpty())
    }

    @Test fun concurrentAcquiresOfEqualSizeDifferentModelsKeepBothCorrect() {
        val a = FakeSource("content://models/a", modelBytes(1))
        val b = FakeSource("content://models/b", modelBytes(2))
        val results = runConcurrently(6) { index -> (if (index % 2 == 0) a else b).let { it to acquire(it) } }

        results.forEach { (source, file) -> assertArrayEquals(source.content, file.readBytes()) }
        assertEquals(2, blobs().size)
    }

    @Test fun failingConcurrentCopyDoesNotAffectHealthyOne() {
        val broken = FakeSource("content://models/a", modelBytes(1)).apply { failAfterBytes = SIZE / 2L }
        val healthy = FakeSource("content://models/b", modelBytes(2))
        val outcomes = runConcurrently(2) { index ->
            runCatching { if (index == 0) acquire(broken) else acquire(healthy) }
        }

        assertTrue(outcomes[0].isFailure)
        assertArrayEquals(healthy.content, outcomes[1].getOrThrow().readBytes())
        assertEquals(1, blobs().size)
        assertTrue(tmpFiles().isEmpty())
    }

    private fun <T> runConcurrently(count: Int, block: (Int) -> T): List<T> {
        val pool = Executors.newFixedThreadPool(count)
        try {
            val start = CountDownLatch(1)
            val futures = (0 until count).map { index -> pool.submit<T> { start.await(); block(index) } }
            start.countDown()
            return futures.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }

    private class Cancelled : RuntimeException()

    private inline fun <reified T : Throwable> expect(block: () -> Unit) {
        try {
            block()
        } catch (error: Throwable) {
            if (error is T) return
            throw AssertionError("expected " + T::class.java.simpleName + " but got " + error, error)
        }
        fail("expected " + T::class.java.simpleName)
    }

    private class FakeSource(
        override val key: String,
        @Volatile var content: ByteArray,
        override val expectedSize: Long = content.size.toLong()
    ) : ModelFileCache.Source {
        val opens = AtomicInteger()
        val maxConcurrentReads = AtomicInteger()
        private val reading = AtomicInteger()
        @Volatile var failAfterBytes = -1L
        @Volatile var onOpen: (Int) -> Unit = {}
        @Volatile var stamp: () -> String? = { null }

        override fun stamp(): String? = stamp.invoke()

        override fun open(): InputStream {
            onOpen(opens.incrementAndGet())
            val data = content
            val failAt = failAfterBytes
            maxConcurrentReads.accumulateAndGet(reading.incrementAndGet(), ::maxOf)
            return object : InputStream() {
                private var position = 0
                private var closed = false

                override fun read(): Int = throw UnsupportedOperationException()

                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (failAt >= 0 && position >= failAt) throw IOException("provider went away")
                    if (position >= data.size) return -1
                    // Short reads, like real providers, to exercise partial buffers.
                    var count = minOf(length, data.size - position, 1000)
                    if (failAt >= 0) count = minOf(count, (failAt - position).toInt().coerceAtLeast(1))
                    System.arraycopy(data, position, buffer, offset, count)
                    position += count
                    Thread.yield()
                    return count
                }

                override fun close() {
                    if (!closed) {
                        closed = true
                        reading.decrementAndGet()
                    }
                }
            }
        }
    }

    private companion object {
        const val BUFFER = 4096
        const val SIZE = 64 * 1024 + 123
        fun modelBytes(seed: Int): ByteArray = Random(seed).nextBytes(SIZE).also {
            "TFL3".encodeToByteArray().copyInto(it, 4)
        }
    }
}
