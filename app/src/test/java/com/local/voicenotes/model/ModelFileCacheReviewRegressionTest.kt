package com.local.voicenotes.model

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Regressions from the independent review of the cache candidate. */
class ModelFileCacheReviewRegressionTest {
    @get:Rule val tmp = TemporaryFolder()

    private class Source(var bytes: ByteArray) : ModelFileCache.Source {
        override val key = "content://same/uri"
        override val expectedSize = bytes.size.toLong()
        override fun stamp() = "constant-size-and-time"
        override fun open(): InputStream = ByteArrayInputStream(bytes)
    }

    @Test fun equalSizeAndTimestampChangeServesFreshBytes() {
        val s = Source("AAAAAAAA".toByteArray()); val c = ModelFileCache(tmp.root, 4)
        c.acquire(s).use { assertArrayEquals(s.bytes, it.file.readBytes()) }
        s.bytes = "BBBBBBBB".toByteArray()
        c.acquire(s).use { assertArrayEquals(s.bytes, it.file.readBytes()) }
    }

    @Test fun sameLengthCorruptionOfCachedBlobIsDetectedAndRepaired() {
        val s = Source("AAAAAAAA".toByteArray()); val c = ModelFileCache(tmp.root, 4)
        c.acquire(s).use { it.file.writeBytes("XXXXXXXX".toByteArray()) }
        val before = ModelFileCache.corruptBlobsDetected
        c.acquire(s).use { assertArrayEquals(s.bytes, it.file.readBytes()) }
        assertEquals(before + 1, ModelFileCache.corruptBlobsDetected)
    }

    @Test fun corruptionWhileLeasedIsRepairedWithoutBreakingTheLease() {
        val s = Source("AAAAAAAA".toByteArray()); val c = ModelFileCache(tmp.root, 4)
        val held = c.acquire(s)
        held.file.writeBytes("XXXXXXXX".toByteArray())
        c.acquire(s).use { assertArrayEquals(s.bytes, it.file.readBytes()) }
        held.close()
    }

    @Test fun trustedBlobPathSkipsTheBlobRehash() {
        val s = Source("AAAAAAAA".toByteArray()); val c = ModelFileCache(tmp.root, 4)
        val path = c.acquire(s).use { it.file.path }
        val before = ModelFileCache.corruptBlobsDetected
        c.acquire(s, trustedBlobPath = path).use { assertEquals(path, it.file.path) }
        assertEquals(before, ModelFileCache.corruptBlobsDetected)
    }

    @Test fun waitingForDirectoryLockPollsCancellation() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val checked = CountDownLatch(1)
        val c = ModelFileCache(tmp.root, 4); val s = Source("AAAAAAAA".toByteArray())
        val pool = Executors.newFixedThreadPool(2)
        try {
            val first = pool.submit { c.acquire(s) { entered.countDown(); release.await() }.close() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val second = pool.submit {
                try {
                    c.acquire(s) { checked.countDown(); throw InterruptedException("cancelled") }.close()
                    fail("expected cancellation")
                } catch (_: InterruptedException) {
                }
            }
            assertTrue("cancellation must be polled while the lock is held", checked.await(2, TimeUnit.SECONDS))
            second.get(5, TimeUnit.SECONDS)
            release.countDown(); first.get(5, TimeUnit.SECONDS)
        } finally {
            release.countDown(); pool.shutdownNow()
        }
    }

    @Test fun interruptedCopyLeavesNoPublishedBlobAndCanRetry() {
        val c = ModelFileCache(tmp.root, 4); val s = Source("AAAAAAAA".toByteArray()); var n = 0
        try { c.acquire(s) { if (++n == 2) throw InterruptedException("probe") }; fail() } catch (_: InterruptedException) {}
        assertTrue(java.io.File(tmp.root, "blobs").listFiles().orEmpty().isEmpty())
        assertTrue(java.io.File(tmp.root, "tmp").listFiles().orEmpty().isEmpty())
        c.acquire(s).use { assertArrayEquals(s.bytes, it.file.readBytes()) }
    }
}
