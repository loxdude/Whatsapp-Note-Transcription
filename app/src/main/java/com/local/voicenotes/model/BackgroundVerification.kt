package com.local.voicenotes.model

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * An integrity check running on [executor] (never on the caller's thread) while the caller
 * already uses the not-yet-verified bytes. It owns [lease] and releases it exactly once:
 * when the check ends, or immediately when it is cancelled before it started.
 *
 * Any exception thrown by the check that is not caused by [cancel] counts as [Outcome.FAILED],
 * so an unexpected error can never be mistaken for a successful verification.
 */
class BackgroundVerification(
    executor: Executor,
    private val lease: AutoCloseable?,
    private val onFinished: (BackgroundVerification, Outcome, Long) -> Unit = { _, _, _ -> },
    private val verifier: (checkCancelled: () -> Unit) -> Boolean
) {
    enum class Outcome { VERIFIED, FAILED, CANCELLED }

    private class Cancelled : RuntimeException("Verification cancelled.")

    private val state = AtomicInteger(PENDING)
    @Volatile private var cancelRequested = false
    @Volatile private var outcome: Outcome? = null
    private val done = CountDownLatch(1)
    private val leaseReleased = AtomicBoolean()

    init {
        try {
            executor.execute(::run)
        } catch (error: Throwable) {
            // Rejected (e.g. executor shut down): nothing ran, so nothing is verified.
            if (state.compareAndSet(PENDING, FINISHED)) finish(Outcome.FAILED, 0L)
            if (error is VirtualMachineError) throw error
        }
    }

    /** The final outcome, or null while the check is still pending/running. */
    val result: Outcome? get() = outcome

    val isCancelRequested: Boolean get() = cancelRequested

    /**
     * Requests cancellation. A check that did not start yet never runs and its lease is released
     * right away; a running check stops at its next [checkCancelled] poll and then releases it.
     */
    fun cancel() {
        cancelRequested = true
        if (state.compareAndSet(PENDING, FINISHED)) finish(Outcome.CANCELLED, 0L)
    }

    /**
     * Blocks until the check finished, polling [checkCancelled] every [pollMillis] so a cancelled
     * caller stops waiting promptly. Does not cancel the check itself.
     */
    fun await(pollMillis: Long = POLL_MILLIS, checkCancelled: () -> Unit = {}): Outcome {
        while (true) {
            checkCancelled()
            if (done.await(pollMillis, TimeUnit.MILLISECONDS)) return outcome!!
        }
    }

    private fun run() {
        if (!state.compareAndSet(PENDING, RUNNING)) return
        val started = System.nanoTime()
        var result = Outcome.FAILED
        try {
            val ok = verifier { if (cancelRequested) throw Cancelled() }
            result = when {
                cancelRequested -> Outcome.CANCELLED
                ok -> Outcome.VERIFIED
                else -> Outcome.FAILED
            }
        } catch (_: Cancelled) {
            result = Outcome.CANCELLED
        } catch (error: Throwable) {
            result = if (cancelRequested) Outcome.CANCELLED else Outcome.FAILED
            if (error is VirtualMachineError) {
                state.set(FINISHED)
                finish(result, System.nanoTime() - started)
                throw error
            }
        }
        state.set(FINISHED)
        finish(result, System.nanoTime() - started)
    }

    private fun finish(result: Outcome, nanos: Long) {
        try {
            if (leaseReleased.compareAndSet(false, true)) runCatching { lease?.close() }
        } finally {
            outcome = result
            done.countDown()
            runCatching { onFinished(this, result, nanos / 1_000_000) }
        }
    }

    private companion object {
        const val PENDING = 0
        const val RUNNING = 1
        const val FINISHED = 2
        const val POLL_MILLIS = 50L
    }
}

/**
 * Gate for results computed from bytes whose verification may still be pending: a result is
 * only returned once its verification succeeded. If it failed (or was cancelled by someone
 * else, e.g. backend close), [onUnverified] discards the unverified state and the work is
 * re-run exactly once via `attempt(true)`, which must use a fully verified path.
 */
object VerifiedResultGate {
    class Attempt<T>(val value: T, val verification: BackgroundVerification?)

    class UnverifiedResultException(message: String) : java.io.IOException(message)

    /** Inline so [attempt] may suspend (e.g. coroutine inference with cancellation checks). */
    inline fun <T> run(
        noinline checkCancelled: () -> Unit,
        onUnverified: (BackgroundVerification.Outcome) -> Unit,
        noinline onAwaited: (BackgroundVerification.Outcome, Long) -> Unit = { _, _ -> },
        attempt: (retry: Boolean) -> Attempt<T>
    ): T {
        val first = attempt(false)
        val firstOutcome = awaitVerified(first.verification, checkCancelled, onAwaited)
            ?: return first.value
        onUnverified(firstOutcome)
        val second = attempt(true)
        val secondOutcome = awaitVerified(second.verification, checkCancelled, onAwaited)
            ?: return second.value
        onUnverified(secondOutcome)
        throw UnverifiedResultException("The model file could not be verified. Re-import it.")
    }

    /** Null when the result may be returned, otherwise the outcome that prevents it. */
    @PublishedApi internal fun awaitVerified(
        verification: BackgroundVerification?,
        checkCancelled: () -> Unit,
        onAwaited: (BackgroundVerification.Outcome, Long) -> Unit
    ): BackgroundVerification.Outcome? {
        verification ?: return null
        val started = System.nanoTime()
        val outcome = verification.await(checkCancelled = checkCancelled)
        // A cancellation of the caller that raced with the outcome still wins.
        checkCancelled()
        onAwaited(outcome, (System.nanoTime() - started) / 1_000_000)
        return outcome.takeIf { it != BackgroundVerification.Outcome.VERIFIED }
    }
}
