package com.local.voicenotes.concurrent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicLong

class CancellationTest {
    @Test fun runCatchingCancellableRethrowsCancellation() {
        val cancellation = CancellationException("stop")
        try {
            runCatchingCancellable<Unit> { throw cancellation }
            fail("cancellation was swallowed")
        } catch (e: CancellationException) {
            assertSame(cancellation, e)
        }
    }

    @Test fun runCatchingCancellableWrapsOrdinaryFailures() {
        val result = runCatchingCancellable { error("boom") }
        assertEquals("boom", result.exceptionOrNull()?.message)
        assertEquals(42, runCatchingCancellable { 42 }.getOrThrow())
    }

    @Test fun copyCancellableCopiesEverything() {
        val data = ByteArray(10_000) { it.toByte() }
        val output = ByteArrayOutputStream()
        val copied = copyCancellable(ByteArrayInputStream(data), output, bufferSize = 333) {}
        assertEquals(data.size.toLong(), copied)
        assertArrayEquals(data, output.toByteArray())
    }

    @Test fun copyCancellableStopsAtTheNextBufferOnceCancelled() {
        val reads = AtomicLong()
        val input = object : InputStream() {
            override fun read(): Int = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int { reads.incrementAndGet(); return len }
        }
        var checks = 0
        try {
            copyCancellable(input, ByteArrayOutputStream(), bufferSize = 16) {
                if (++checks > 3) throw CancellationException("cancelled")
            }
            fail("copy did not stop")
        } catch (_: CancellationException) {
        }
        assertEquals(3, reads.get())
    }

    @Test fun cancellingTheJobStopsABlockingCopyPromptly() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val endless = object : InputStream() {
            override fun read(): Int = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                started.complete(Unit)
                return len
            }
        }
        val sink = object : OutputStream() { override fun write(b: Int) {} ; override fun write(b: ByteArray, off: Int, len: Int) {} }
        var failure: Throwable? = null
        val job = launch(Dispatchers.IO) {
            try {
                copyCancellable(endless, sink, 1024, coroutineContext.job::ensureActive)
            } catch (e: Throwable) {
                failure = e
                throw e
            }
        }
        withTimeout(2_000) { started.await(); job.cancelAndJoin() }
        assertTrue(job.isCancelled)
        assertTrue(failure is CancellationException)
    }
}
