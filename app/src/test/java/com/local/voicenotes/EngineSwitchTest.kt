package com.local.voicenotes

import com.local.voicenotes.model.DownloadState
import com.local.voicenotes.model.ParakeetUltraDownload
import com.local.voicenotes.ui.Engine
import com.local.voicenotes.ui.bestLocalModel
import com.local.voicenotes.ui.engineOf
import com.local.voicenotes.work.WorkKind
import com.local.voicenotes.work.WorkNotificationPolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineSwitchTest {
    @Test fun switchReadsCloudOnlyForAnActualCloudSelection() {
        assertEquals(Engine.LOCAL, engineOf(AppUiState(models = listOf(MODEL_CLOUD), selectedModelId = null)))
        assertEquals(Engine.CLOUD, engineOf(AppUiState(models = listOf(MODEL_CLOUD), selectedModelId = MODEL_CLOUD.id)))
    }

    @Test fun downloadedEncoderIsPreferredOverOtherImports() {
        val downloaded = model(ParakeetUltraDownload.MODEL_ID)
        val state = AppUiState(models = listOf(MODEL_CLOUD, MODEL_A, downloaded), selectedModelId = MODEL_CLOUD.id)
        assertEquals(downloaded.id, bestLocalModel(state)?.id)
        assertNull(bestLocalModel(AppUiState(models = listOf(MODEL_CLOUD, model("v3", enabled = false)))))
    }

    /** "Local" without a local model must not leave the cloud selected (no silent upload). */
    @Test fun clearingTheSelectionBlocksTranscriptionAndPersistsNoChoice() = runBlocking {
        val h = Harness(this)
        h.ready(selected = MODEL_CLOUD.id)
        assertTrue(h.state.canTranscribe)
        assertTrue(h.coordinator.clearSelection())
        assertNull(h.state.selectedModelId)
        assertFalse(h.state.canTranscribe)
        assertFalse(h.coordinator.transcribe())
        h.await { !it.busy }
        settle()
        assertEquals(NO_SELECTION, h.catalog.saved)
        // A later refresh may only pick a local model, never the cloud one.
        h.coordinator.refreshModels().join()
        assertEquals(MODEL_A.id, h.state.selectedModelId)
        h.coordinator.dispose()
    }

    @Test fun downloadKeepsTheServiceAliveAndReportsTheOutcome() {
        val idle = AppUiState()
        val running = WorkNotificationPolicy.ongoing(idle, download = DownloadState.Running(332_729_264, 665_458_528))
        assertEquals(WorkKind.DOWNLOAD, running?.kind)
        assertEquals(50, running?.percent)
        assertTrue(WorkNotificationPolicy.shouldRun(idle, download = DownloadState.Verifying))
        assertFalse(WorkNotificationPolicy.shouldRun(idle, download = DownloadState.Failed("x")))
        assertEquals("Model download failed",
            WorkNotificationPolicy.finished(WorkKind.DOWNLOAD, idle, DownloadState.Failed("offline"))?.title)
        // Cancelled: nothing installed, nothing to announce.
        assertNull(WorkNotificationPolicy.finished(WorkKind.DOWNLOAD, idle, DownloadState.Idle))
    }
}
