package com.local.voicenotes.model

import com.local.voicenotes.model.BackgroundVerification.Outcome
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class VerifiedResultGateTest {
    private val pool = Executors.newCachedThreadPool()

    @After fun tearDown() {
        pool.shutdownNow()
    }

    private class CountingLease : AutoCloseable {
        val closes = AtomicInteger()
        override fun close() { closes.incrementAndGet() }
    }

    private fun verification(lease: AutoCloseable? = null, check: (() -> Unit) -> Boolean) =
        BackgroundVerification(pool, lease, verifier = check)

    @Test fun resultWithoutPendingVerificationIsReturnedDirectly() {
        val attempts = mutableListOf<Boolean>()
        val text = VerifiedResultGate.run({}, { fail("not unverified") }) { retry ->
            attempts += retry
            VerifiedResultGate.Attempt("text", null)
        }
        assertEquals("text", text)
        assertEquals(listOf(false), attempts)
    }

    @Test fun resultIsHeldBackUntilVerificationPasses() {
        val release = CountDownLatch(1)
        val pending = verification { release.await(5, TimeUnit.SECONDS); true }
        val returned = CountDownLatch(1)
        val worker = Thread {
            VerifiedResultGate.run({}, { fail("not unverified") }) { VerifiedResultGate.Attempt("text", pending) }
            returned.countDown()
        }.apply { start() }

        assertTrue("result must wait for the verification", !returned.await(200, TimeUnit.MILLISECONDS))
        release.countDown()
        assertTrue(returned.await(5, TimeUnit.SECONDS))
        worker.join()
    }

    @Test fun failedVerificationDiscardsTheResultAndRerunsOnceOnTheVerifiedPath() {
        val attempts = mutableListOf<Boolean>()
        val unverified = mutableListOf<Outcome>()
        val text = VerifiedResultGate.run({}, { unverified += it }) { retry ->
            attempts += retry
            if (!retry) VerifiedResultGate.Attempt("from unverified bytes", verification { false })
            else VerifiedResultGate.Attempt("from verified bytes", null)
        }
        assertEquals("from verified bytes", text)
        assertEquals(listOf(false, true), attempts)
        assertEquals(listOf(Outcome.FAILED), unverified)
    }

    @Test fun throwingVerificationCountsAsFailed() {
        val text = VerifiedResultGate.run({}, {}) { retry ->
            if (!retry) VerifiedResultGate.Attempt("bad", verification { throw IllegalStateException("io") })
            else VerifiedResultGate.Attempt("good", null)
        }
        assertEquals("good", text)
    }

    @Test fun verificationCancelledByOthersIsNotTreatedAsVerified() {
        val started = CountDownLatch(1)
        val pending = verification { check -> started.countDown(); spin(check) }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        pending.cancel() // e.g. backend close()
        val unverified = mutableListOf<Outcome>()
        val text = VerifiedResultGate.run({}, { unverified += it }) { retry ->
            VerifiedResultGate.Attempt(if (retry) "verified" else "unverified", if (retry) null else pending)
        }
        assertEquals("verified", text)
        assertEquals(listOf(Outcome.CANCELLED), unverified)
    }

    @Test fun secondUnverifiedResultIsNeverReturned() {
        try {
            VerifiedResultGate.run({}, {}) { VerifiedResultGate.Attempt("bad", verification { false }) }
            fail("expected an error")
        } catch (_: VerifiedResultGate.UnverifiedResultException) {
        }
    }

    @Test fun cancelledCallerStopsWaitingWithoutReturningAResult() {
        val cancelled = java.util.concurrent.atomic.AtomicBoolean()
        val lease = CountingLease()
        val pending = verification(lease) { check -> spin(check) }
        val failure = arrayOfNulls<Throwable>(1)
        val worker = Thread {
            try {
                VerifiedResultGate.run({ if (cancelled.get()) throw Stop() }, { fail("not unverified") }) {
                    VerifiedResultGate.Attempt("text", pending)
                }
                fail("must not return")
            } catch (error: Throwable) {
                failure[0] = error
                pending.cancel() // what the backend does on cancellation
            }
        }.apply { start() }
        Thread.sleep(100)
        cancelled.set(true)
        worker.join(5_000)
        assertTrue(failure[0] is Stop)
        assertEquals(Outcome.CANCELLED, pending.await())
        assertEquals("lease released exactly once", 1, lease.closes.get())
    }

    @Test fun leaseIsReleasedExactlyOnceInEveryOutcome() {
        listOf<(() -> Unit) -> Boolean>({ true }, { false }, { throw RuntimeException() }).forEach { check ->
            val lease = CountingLease()
            val v = verification(lease, check)
            v.await()
            v.cancel()
            assertEquals(1, lease.closes.get())
        }
        val rejected = CountingLease()
        val executor = Executors.newSingleThreadExecutor().also { it.shutdown() }
        val v = BackgroundVerification(executor, rejected) { true }
        assertEquals("a check that cannot run is a failed check", Outcome.FAILED, v.result)
        assertEquals(1, rejected.closes.get())
    }

    @Test fun onFinishedReportsOutcomeAndDuration() {
        val reported = CountDownLatch(1)
        var outcome: Outcome? = null
        BackgroundVerification(pool, null, { _, result, millis ->
            outcome = result
            assertTrue(millis >= 0)
            reported.countDown()
        }) { true }
        assertTrue(reported.await(5, TimeUnit.SECONDS))
        assertEquals(Outcome.VERIFIED, outcome)
    }

    @Test fun errorOfFirstAttemptWhileVerificationPendingIsRetriedWhenTheBytesFailVerification() {
        val release = CountDownLatch(1)
        val pending = verification { release.await(5, TimeUnit.SECONDS); false }
        val attempts = mutableListOf<Boolean>()
        val unverified = mutableListOf<Outcome>()
        var used: BackgroundVerification? = null
        Thread { Thread.sleep(100); release.countDown() }.start()
        val text = VerifiedResultGate.run({}, { unverified += it }, pendingVerification = { used }) { retry ->
            attempts += retry
            if (!retry) {
                used = pending
                throw IllegalStateException("native inference failed on corrupt bytes")
            }
            VerifiedResultGate.Attempt("from verified bytes", null)
        }
        assertEquals("from verified bytes", text)
        assertEquals(listOf(false, true), attempts)
        assertEquals(listOf(Outcome.FAILED), unverified)
    }

    @Test fun errorOfFirstAttemptOnVerifiedBytesIsRethrownUnchanged() {
        val original = IllegalStateException("genuine failure")
        val attempts = mutableListOf<Boolean>()
        try {
            VerifiedResultGate.run<String>({}, { fail("bytes were verified") }, pendingVerification = { verification { true } }) { retry ->
                attempts += retry
                throw original
            }
            fail("expected the original error")
        } catch (error: IllegalStateException) {
            assertTrue(error === original)
        }
        assertEquals(listOf(false), attempts)
    }

    @Test fun errorWithoutPendingVerificationOrOnCancellationIsNotRetried() {
        val attempts = AtomicInteger()
        try {
            VerifiedResultGate.run<String>({}, { fail("no verification") }) { attempts.incrementAndGet(); throw IllegalStateException("x") }
            fail("expected an error")
        } catch (_: IllegalStateException) {
        }
        try {
            VerifiedResultGate.run<String>({}, { fail("cancellation is not retried") },
                pendingVerification = { verification { false } }) {
                attempts.incrementAndGet(); throw java.util.concurrent.CancellationException("cancelled")
            }
            fail("expected cancellation")
        } catch (_: java.util.concurrent.CancellationException) {
        }
        assertEquals(2, attempts.get())
    }

    private class Stop : RuntimeException()

    /** Runs until cancelled (bounded to keep a broken test from hanging forever). */
    private fun spin(check: () -> Unit): Boolean {
        repeat(2_000) { check(); Thread.sleep(5) }
        return true
    }
}
