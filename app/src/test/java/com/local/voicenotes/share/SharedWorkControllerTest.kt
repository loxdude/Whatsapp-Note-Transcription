package com.local.voicenotes.share

import com.local.voicenotes.*
import com.local.voicenotes.domain.ImportedModel
import com.local.voicenotes.domain.LanguageOption
import com.local.voicenotes.domain.TranscriptionProgress
import com.local.voicenotes.inference.UriTranscriptionBackend
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.ContinuationInterceptor

/** Exercises the production AppGraph start/cancel seam with the real coordinator. No Android. */
class SharedWorkControllerTest {
    @Test fun notificationCancelDuringMetadataAndCatalogWaitPreventsLocalAndCloudWork() = runBlocking {
        for (cloud in listOf(false, true)) {
            val f = Fixture(this, cloud)
            val metadata = Gate()
            val catalog = Gate()
            try {
                f.consent = true
                f.readName = { source, _ -> metadata.nonCancellable { source } }
                f.readModels = { catalog.nonCancellable { f.models } }
                f.refresh = f.coordinator.refreshModels()
                catalog.started.await()
                val share = f.work.onSharedAudio("content://shared")
                metadata.started.await()
                assertEquals(TranscriptionProgress.Idle, f.coordinator.state.value.progress)

                // CancelWorkReceiver and AppViewModel both delegate to this app-owned entry point.
                f.work.cancelWork()
                metadata.release.complete(Unit)
                catalog.release.complete(Unit)
                withTimeout(5_000) { share.join(); f.refresh.join() }
                settle()

                assertEquals("Cancel must prevent deferred transcription", 0, f.backend.transcribeCalls.get())
                assertEquals("Cancel must prevent deferred upload", 0, f.backend.uploads.get())
                assertEquals(1, f.releases.get())
                assertEquals(0, f.reservations)
            } finally { metadata.release.complete(Unit); catalog.release.complete(Unit); f.close() }
        }
    }

    @Test fun uiCancelReleasesReservationBeforeBlockedMetadataReturnsExactlyOnce() = runBlocking {
        val f = Fixture(this)
        val metadata = Gate()
        try {
            f.ready()
            f.readName = { source, _ -> metadata.nonCancellable { source } }
            val share = f.work.onSharedAudio("content://shared")
            metadata.started.await()
            f.work.cancelWork()
            assertEquals("release must not wait for a blocked content provider", 0, f.reservations)
            assertEquals(1, f.releases.get())
            f.work.cancelWork()
            metadata.release.complete(Unit)
            withTimeout(5_000) { share.join() }
            assertEquals(1, f.releases.get())
            assertEquals("content://picked", f.coordinator.state.value.audioUri)
            assertEquals(0, f.backend.transcribeCalls.get())
            assertEquals(0, f.backend.uploads.get())
        } finally { metadata.release.complete(Unit); f.close() }
    }

    @Test fun uiCancelWhileOnlyCatalogWaitsPreventsUpload() = runBlocking {
        val f = Fixture(this, cloud = true)
        val catalog = Gate()
        try {
            f.consent = true
            f.readModels = { catalog.nonCancellable { f.models } }
            f.refresh = f.coordinator.refreshModels()
            catalog.started.await()
            val share = f.work.onSharedAudio("content://shared")
            withTimeout(5_000) { f.coordinator.state.first { it.audioUri == "content://shared" } }
            assertEquals(TranscriptionProgress.Idle, f.coordinator.state.value.progress)
            f.work.cancelWork()
            assertEquals(0, f.reservations)
            catalog.release.complete(Unit)
            withTimeout(5_000) { share.join(); f.refresh.join() }
            settle()
            assertEquals(0, f.backend.uploads.get())
            assertEquals(0, f.backend.transcribeCalls.get())
            assertEquals(1, f.releases.get())
        } finally { catalog.release.complete(Unit); f.close() }
    }

    @Test fun newestShareOwnsCancellationEvenAfterOldBlockedShareFinallyReturns() = runBlocking {
        val f = Fixture(this)
        val oldMetadata = Gate()
        val newMetadata = Gate()
        try {
            f.ready()
            f.readName = { source, _ ->
                (if (source.endsWith("old")) oldMetadata else newMetadata).nonCancellable { source }
            }
            val old = f.work.onSharedAudio("content://old")
            oldMetadata.started.await()
            val newest = f.work.onSharedAudio("content://new")
            newMetadata.started.await()
            assertEquals(1, f.releases.get())
            assertEquals(1, f.reservations)
            oldMetadata.release.complete(Unit)
            withTimeout(5_000) { old.join() }
            // Old completion must neither clear the new pending owner nor release its lease.
            assertEquals(1, f.reservations)
            f.work.cancelWork()
            assertEquals(0, f.reservations)
            newMetadata.release.complete(Unit)
            withTimeout(5_000) { newest.join() }
            assertEquals(2, f.releases.get())
            assertEquals("content://picked", f.coordinator.state.value.audioUri)
            assertEquals(0, f.backend.transcribeCalls.get())
        } finally { oldMetadata.release.complete(Unit); newMetadata.release.complete(Unit); f.close() }
    }

    @Test fun newestShareAloneIsTranscribedWhenOldMetadataReturnsLate() = runBlocking {
        val f = Fixture(this)
        val oldMetadata = Gate()
        try {
            f.ready()
            f.readName = { source, _ ->
                if (source.endsWith("old")) oldMetadata.nonCancellable { source } else source
            }
            val old = f.work.onSharedAudio("content://old")
            oldMetadata.started.await()
            val newest = f.work.onSharedAudio("content://new")
            withTimeout(5_000) {
                newest.join()
                f.coordinator.state.first { it.progress is TranscriptionProgress.Completed }
            }
            oldMetadata.release.complete(Unit)
            withTimeout(5_000) { old.join() }
            assertEquals(listOf("content://new"), f.decodedSources)
            assertEquals("content://new", f.coordinator.state.value.audioUri)
            assertEquals("local text", f.coordinator.state.value.transcript)
            assertEquals(1, f.backend.transcribeCalls.get())
            assertEquals(2, f.releases.get())
            assertEquals(0, f.reservations)
        } finally { oldMetadata.release.complete(Unit); f.close() }
    }

    @Test fun pickedAudioSupersedesBlockedShareWithoutAutomaticTranscription() = runBlocking {
        val f = Fixture(this)
        val metadata = Gate()
        try {
            f.ready()
            f.readName = { source, _ ->
                if (source.endsWith("shared")) metadata.nonCancellable { source } else source
            }
            val share = f.work.onSharedAudio("content://shared")
            metadata.started.await()
            val pick = f.work.setAudio("content://new-pick", persist = true)
            assertEquals(0, f.reservations)
            pick.join()
            metadata.release.complete(Unit)
            withTimeout(5_000) { share.join() }
            assertEquals("content://new-pick", f.coordinator.state.value.audioUri)
            assertEquals(0, f.backend.transcribeCalls.get())
            assertEquals(0, f.backend.uploads.get())
            assertEquals(1, f.releases.get())
        } finally { metadata.release.complete(Unit); f.close() }
    }

    @Test fun cloudWithoutConsentInvokesNoBackendFromDirectOrShareStart() = runBlocking {
        val f = Fixture(this, cloud = true)
        try {
            f.ready()
            val prepared = f.backend.prepared.toList()
            assertFalse(f.work.transcribe())
            f.work.onSharedAudio("content://shared").join()
            settle()
            assertNotNull(f.work.shareHint.value)
            assertEquals(0, f.backend.validations.get())
            assertEquals(0, f.backend.uploads.get())
            assertEquals(0, f.backend.transcribeCalls.get())
            assertEquals(prepared, f.backend.prepared)
            assertEquals(0, f.serviceStarts)
            assertEquals(1, f.releases.get())
        } finally { f.close() }
    }


    @Test fun explicitConsentAllowsOneUploadFromDirectStartAndRejectsConcurrentTap() = runBlocking {
        val f = Fixture(this, cloud = true)
        try {
            f.ready()
            f.consent = true
            assertTrue(f.work.transcribe())
            assertFalse(f.work.transcribe())
            withTimeout(5_000) { f.coordinator.state.first { it.progress is TranscriptionProgress.Completed } }
            assertEquals(1, f.backend.validations.get())
            assertEquals(1, f.backend.uploads.get())
            assertEquals(1, f.serviceStarts)
            assertEquals(0, f.backend.transcribeCalls.get())
        } finally { f.close() }
    }

    /** A share (exported activity, any sender) must never upload by itself, even with consent. */
    @Test fun sharedAudioNeverStartsCloudUploadEvenWithConsent() = runBlocking {
        for (mode in SharedAutoStart.entries) {
            val f = Fixture(this, cloud = true)
            try {
                f.ready()
                f.consent = true
                f.mode = mode
                f.work.onSharedAudio("content://shared").join()
                settle()
                assertEquals("content://shared", f.coordinator.state.value.audioUri)
                assertEquals(TranscriptionProgress.Idle, f.coordinator.state.value.progress)
                assertTrue(f.work.shareHint.value!!.contains("Mistral"))
                assertEquals(0, f.backend.validations.get())
                assertEquals(0, f.backend.uploads.get())
                assertEquals(0, f.serviceStarts)
                assertEquals(0, f.reservations)
                // The user's tap uploads exactly this audio.
                assertTrue(f.work.transcribe(audioUri = "content://shared"))
                withTimeout(5_000) { f.coordinator.state.first { it.progress is TranscriptionProgress.Completed } }
                assertEquals(1, f.backend.uploads.get())
                assertNull(f.work.shareHint.value)
            } finally { f.close() }
        }
    }

    @Test fun cloudIsNeverAnAutomaticFallbackEvenWithConsent() = runBlocking {
        val f = Fixture(this, cloud = true)
        try {
            f.savedSelection = null
            f.consent = true
            f.ready()
            f.work.onSharedAudio("content://shared").join()
            settle()
            assertNull(f.coordinator.state.value.selectedModel)
            assertEquals(0, f.backend.validations.get())
            assertEquals(0, f.backend.uploads.get())
            assertEquals(0, f.reservations)
        } finally { f.close() }
    }

    @Test fun backgroundShareWithoutServiceOnlyShowsHint() = runBlocking {
        val f = Fixture(this)
        try {
            f.ready()
            f.mode = SharedAutoStart.HINT_ONLY
            f.work.onSharedAudio("content://shared").join()
            assertEquals("content://shared", f.coordinator.state.value.audioUri)
            assertNotNull(f.work.shareHint.value)
            assertEquals(0, f.backend.transcribeCalls.get())
            assertEquals(0, f.serviceStarts)
            assertEquals(1, f.releases.get())
        } finally { f.close() }
    }

    @Test fun appOwnedPendingShareRemainsCancellableAfterUiScopeIsDestroyed() = runBlocking {
        val f = Fixture(this)
        val metadata = Gate()
        val uiScope = CoroutineScope(SupervisorJob() + coroutineContext[ContinuationInterceptor]!!)
        try {
            f.ready()
            f.readName = { source, _ -> metadata.nonCancellable { source } }
            val submitted = CompletableDeferred<Job>()
            uiScope.launch { submitted.complete(f.work.onSharedAudio("content://shared")) }
            val share = submitted.await()
            metadata.started.await()
            uiScope.cancel()
            assertTrue("UI destruction must not own/cancel app work", share.isActive)
            f.work.cancelWork()
            assertEquals(0, f.reservations)
            metadata.release.complete(Unit)
            withTimeout(5_000) { share.join() }
            assertEquals(0, f.backend.transcribeCalls.get())
            assertEquals(1, f.releases.get())
        } finally { metadata.release.complete(Unit); uiScope.cancel(); f.close() }
    }

    @Test fun runningSharedTranscriptionSurvivesUiScopeDestruction() = runBlocking {
        val f = Fixture(this)
        val transcribe = Gate()
        val uiScope = CoroutineScope(SupervisorJob() + coroutineContext[ContinuationInterceptor]!!)
        try {
            f.ready()
            f.backend.onTranscribe = { _, _ -> transcribe.nonCancellable { "survived UI" } }
            uiScope.launch { f.work.onSharedAudio("content://shared") }
            transcribe.started.await()
            uiScope.cancel()
            assertTrue(f.coordinator.state.value.busy)
            transcribe.release.complete(Unit)
            val done = withTimeout(5_000) {
                f.coordinator.state.first { it.progress is TranscriptionProgress.Completed }
            }
            assertEquals("survived UI", done.transcript)
            assertEquals(1, f.backend.transcribeCalls.get())
            assertEquals(1, f.releases.get())
        } finally { transcribe.release.complete(Unit); uiScope.cancel(); f.close() }
    }

    @Test fun centralCancelAlsoCancelsRunningCoordinatorAndDiscardsLateBackendResult() = runBlocking {
        val f = Fixture(this)
        val transcribe = Gate()
        try {
            f.ready()
            f.backend.onTranscribe = { _, _ -> transcribe.nonCancellable { "must be discarded" } }
            val share = f.work.onSharedAudio("content://shared")
            transcribe.started.await()
            share.join()
            f.work.cancelWork()
            assertEquals(TranscriptionProgress.Cancelled, f.coordinator.state.value.progress)
            transcribe.release.complete(Unit)
            transcribe.finished.await()
            settle()
            assertEquals(TranscriptionProgress.Cancelled, f.coordinator.state.value.progress)
            assertEquals("", f.coordinator.state.value.transcript)
            assertEquals(1, f.backend.transcribeCalls.get())
            assertEquals(1, f.releases.get())
        } finally { transcribe.release.complete(Unit); f.close() }
    }

    /** A consent confirmation is bound to the audio it was shown for. */
    @Test fun boundStartRefusesWhenAudioChangedSinceConfirmation() = runBlocking {
        val f = Fixture(this, cloud = true)
        try {
            f.ready()
            f.consent = true
            f.work.setAudio("content://newer", persist = true).join()
            assertFalse(f.work.transcribe(audioUri = "content://picked"))
            assertFalse(f.work.transcribe(audioUri = "content://newer", modelId = "other"))
            settle()
            assertEquals(0, f.backend.uploads.get())
            assertEquals(0, f.serviceStarts)
        } finally { f.close() }
    }

    private open class CountingBackend : FakeBackend() {
        val validations = AtomicInteger()
        override suspend fun validate(model: ImportedModel): Result<Unit> {
            validations.incrementAndGet()
            return Result.success(Unit)
        }
        val uploads = AtomicInteger()
    }

    private class CountingCloudBackend : CountingBackend(), UriTranscriptionBackend {
        var upload: suspend (String) -> String = { "uploaded $it" }
        override suspend fun transcribeUri(
            model: ImportedModel, uri: String, language: LanguageOption, onProgress: (Float) -> Unit
        ): String {
            uploads.incrementAndGet()
            return upload(uri)
        }
    }

    private class Fixture(parent: CoroutineScope, cloud: Boolean = false) {
        val scope = CoroutineScope(SupervisorJob() + parent.coroutineContext[ContinuationInterceptor]!!)
        val backend = if (cloud) CountingCloudBackend() else CountingBackend()
        val models = listOf(if (cloud) MODEL_CLOUD else MODEL_A)
        var savedSelection: String? = models.single().id
        val decodedSources = mutableListOf<String>()
        var cloudUpload: suspend (String) -> String
            get() = (backend as CountingCloudBackend).upload
            set(value) { (backend as CountingCloudBackend).upload = value }
        var consent = false
        var reservations = 0
        val releases = AtomicInteger()
        var serviceStarts = 0
        var mode = SharedAutoStart.START
        var readName: suspend (String, Boolean) -> String = { source, _ -> source }
        var readModels: suspend () -> List<ImportedModel> = { models }
        lateinit var refresh: Job
        val coordinator = TranscriptionCoordinator(
            scope,
            object : ModelCatalog {
                override suspend fun models() = readModels()
                override suspend fun selectedModelId() = savedSelection
                override suspend fun select(id: String) = Unit
                override suspend fun import(source: String, onProgress: (Float) -> Unit): ImportedModel = error("unused")
            },
            PcmDecoder { source, _ -> decodedSources += source; floatArrayOf(1f) },
            mapOf("local" to backend, "cloud" to backend)
        )
        val work = SharedWorkController(
            scope, coordinator,
            loadAudioName = { source, persist -> readName(source, persist) },
            awaitInitialRefresh = { refresh.join() },
            cloudConsent = { consent },
            reserveService = {
                reservations++
                // Deliberately not idempotent: the controller must release each lease exactly once.
                val release: () -> Unit = { releases.incrementAndGet(); reservations-- }
                release
            },
            startService = { serviceStarts++ },
            autoStart = { mode }
        )
        suspend fun ready() {
            refresh = coordinator.refreshModels()
            refresh.join()
            withTimeout(5_000) { coordinator.state.first { !it.busy } }
            coordinator.setAudio("content://picked", "picked.ogg")
        }
        fun close() { scope.cancel() }
    }
}
