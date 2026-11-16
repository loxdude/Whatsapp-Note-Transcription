package com.local.voicenotes.share

import com.local.voicenotes.TranscriptionCoordinator
import com.local.voicenotes.isCloud
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

/**
 * Android-free app-scoped start path shared by the UI and notification adapter.
 * Entry points and continuations run on the app's main dispatcher, not a ViewModel scope.
 */
class SharedWorkController(
    private val scope: CoroutineScope,
    private val coordinator: TranscriptionCoordinator,
    private val loadAudioName: suspend (source: String, persist: Boolean) -> String,
    private val awaitInitialRefresh: suspend () -> Unit,
    private val cloudConsent: () -> Boolean,
    private val reserveService: () -> (() -> Unit),
    private val startService: () -> Unit,
    private val autoStart: () -> SharedAutoStart
) {
    private val mutableConsentPrompt = MutableStateFlow(false)
    val consentPrompt = mutableConsentPrompt.asStateFlow()
    private val mutableShareHint = MutableStateFlow<String?>(null)
    val shareHint = mutableShareHint.asStateFlow()
    private class PendingShare(val releaseService: () -> Unit) {
        var job: Job? = null
        private val released = AtomicBoolean()
        fun release() {
            if (released.compareAndSet(false, true)) releaseService()
        }
    }

    private var generation = 0L
    private var pendingShare: PendingShare? = null

    /** Invalidate synchronously; a blocked provider must not keep the service reserved. */
    private fun cancelPendingShare() {
        generation++
        val pending = pendingShare
        pendingShare = null
        pending?.job?.cancel()
        pending?.release()
        mutableConsentPrompt.value = false
    }

    fun setAudio(source: String, persist: Boolean): Job {
        cancelPendingShare()
        mutableShareHint.value = null
        val token = generation
        return scope.launch {
            val name = loadAudioName(source, persist)
            coroutineContext.ensureActive()
            if (token == generation) coordinator.setAudio(source, name)
        }
    }

    /**
     * Reserve while visible, then await metadata, catalog and any import/preparation in the
     * process scope. In the background only a running foreground service permits auto-start;
     * otherwise keep the audio and show a hint. Never select a cloud fallback or skip consent.
     */
    fun onSharedAudio(source: String): Job {
        cancelPendingShare()
        mutableShareHint.value = null
        val token = generation
        val pending = PendingShare(reserveService())
        pendingShare = pending
        // Register ownership before starting: Main.immediate may run the whole share inline.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val name = loadAudioName(source, false)
            coroutineContext.ensureActive()
            if (token != generation) return@launch
            coordinator.setAudio(source, name)
            awaitInitialRefresh()
            coroutineContext.ensureActive()
            if (token != generation) return@launch
            val decision = coordinator.state.map { decideSharedAudio(it, source, cloudConsent()) }
                .first { it != SharedAudioDecision.WAIT }
            coroutineContext.ensureActive()
            if (token != generation) return@launch
            when (decision) {
                SharedAudioDecision.TRANSCRIBE -> when (autoStart()) {
                    SharedAutoStart.START -> transcribe()
                    SharedAutoStart.START_IN_RUNNING_SERVICE -> transcribe(startService = false)
                    SharedAutoStart.HINT_ONLY ->
                        mutableShareHint.value = "Shared audio is loaded. Tap Transcribe to start."
                }
                SharedAudioDecision.NEEDS_CONSENT -> mutableConsentPrompt.value = true
                else -> Unit
            }
        }
        pending.job = job
        job.invokeOnCompletion {
            if (pendingShare === pending) pendingShare = null
            // A successful start has already published busy; the service carries over.
            pending.release()
        }
        job.start()
        return job
    }

    fun consentPromptShown() { mutableConsentPrompt.value = false }

    /** Enforce consent at the actual start, including background shares, not only in the UI. */
    fun transcribe(startService: Boolean = true): Boolean {
        if (coordinator.state.value.selectedModel?.isCloud() == true && !cloudConsent()) return false
        val started = coordinator.transcribe()
        if (started) mutableShareHint.value = null
        if (started && startService) this.startService()
        return started
    }

    /** UI and notifications use this same authority, including when the coordinator is Idle. */
    fun cancelWork() {
        cancelPendingShare()
        coordinator.cancel()
    }
}
