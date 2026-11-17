package com.local.voicenotes.work

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.local.voicenotes.AppUiState
import com.local.voicenotes.appGraph
import com.local.voicenotes.model.DownloadState
import com.local.voicenotes.voiceNotesApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps the process in the foreground while an import or transcription runs, so the work in
 * the app-scoped coordinator survives the activity being backgrounded or swiped away.
 *
 * The service holds no work itself: it mirrors the coordinator state (and pending share
 * reservations, see [com.local.voicenotes.AppGraph.reserveWorkServiceForShare]) into an
 * ongoing notification ([WorkNotificationPolicy]) and stops itself as soon as no work is
 * running or reserved.
 * Type: mediaProcessing on API 35+, dataSync on API 31-34 (mediaProcessing does not exist).
 */
class TranscriptionService : Service() {
    companion object {
        private const val TAG = "VoiceNotesBenchmark"

        /**
         * Starts the service. Only allowed while the app is visible; returns false if the
         * system refused (e.g. ForegroundServiceStartNotAllowedException), in which case the
         * work still runs, just without background protection.
         */
        fun start(context: Context): Boolean = try {
            ContextCompat.startForegroundService(context, Intent(context, TranscriptionService::class.java))
            true
        } catch (refused: IllegalStateException) {
            Log.w(TAG, "Could not start the transcription service", refused)
            false
        } catch (refused: SecurityException) {
            Log.w(TAG, "Could not start the transcription service", refused)
            false
        }

        private val foregroundType: Int
            get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null
    private var latestStartId = 0
    private var inForeground = false
    private var shownNotice: OngoingNotice? = null
    private var lastKind: WorkKind? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        // startForeground must follow every startForegroundService(), even if the work has
        // already finished; onState() below stops the service again right away then.
        val notice = currentNotice()
            ?: OngoingNotice(WorkKind.TRANSCRIPTION, "Voice notes", "Working", null)
        val notification = WorkNotifications.ongoing(this, notice)
        // Android 16 devices (OxygenOS 16 and One UI 8 both tested) reject mediaProcessing as "type none" even though
        // it is declared and its permission is granted; dataSync is the declared fallback.
        var lastRefusal: RuntimeException? = null
        for (type in listOf(foregroundType, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC).distinct()) {
            try {
                ServiceCompat.startForeground(this, WorkNotifications.ONGOING_ID, notification, type)
                inForeground = true
                shownNotice = notice
                lastRefusal = null
                Log.i(TAG, "foreground service started type=$type")
                break
            } catch (refused: RuntimeException) {
                Log.w(TAG, "startForeground refused for type=$type", refused)
                lastRefusal = refused
            }
        }
        if (lastRefusal != null) {
            // Only stops if no newer start is pending (that one gets its own startForeground).
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        appGraph.workServiceInForeground = true
        if (observer == null) {
            observer = scope.launch {
                combine(appGraph.coordinator.state, appGraph.shareReservations, appGraph.modelDownload) { state, reserved, download ->
                    Triple(state, reserved > 0, download)
                }.collect { (state, reserved, download) -> onState(state, reserved, download) }
            }
        }
        // Render the current state now: if work started and ended since the last start, the
        // observer sees no new value and the placeholder above would otherwise stay.
        onState(appGraph.coordinator.state.value, appGraph.shareReservations.value > 0, appGraph.modelDownload.value)
        return START_NOT_STICKY
    }

    private fun currentNotice(): OngoingNotice? =
        WorkNotificationPolicy.ongoing(appGraph.coordinator.state.value, appGraph.shareReservations.value > 0, appGraph.modelDownload.value)

    private fun onState(state: AppUiState, shareReserved: Boolean, download: DownloadState) {
        val notice = WorkNotificationPolicy.ongoing(state, shareReserved, download)
        if (notice != null) {
            lastKind = notice.kind
            if (inForeground && notice != shownNotice) {
                shownNotice = notice
                runCatching {
                    NotificationManagerCompat.from(this)
                        .notify(WorkNotifications.ONGOING_ID, WorkNotifications.ongoing(this, notice))
                }
            }
            return
        }
        val finished = WorkNotificationPolicy.finished(lastKind, state, download)
        lastKind = null
        if (finished != null && !voiceNotesApp.isInForeground) {
            WorkNotifications.postFinished(this, finished)
        }
        leaveForeground()
        // Keeps running if a newer start (new work) is already queued; its onStartCommand
        // re-enters the foreground.
        stopSelfResult(latestStartId)
    }

    private fun leaveForeground() {
        if (!inForeground) return
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        inForeground = false
        shownNotice = null
        appGraph.workServiceInForeground = false
    }

    /** API 35 time limit for dataSync/mediaProcessing (6 h): cancel and stop promptly. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "Foreground service time limit reached; cancelling work")
        appGraph.cancelAll()
        leaveForeground()
        stopSelfResult(latestStartId)
    }

    override fun onDestroy() {
        scope.cancel()
        appGraph.workServiceInForeground = false
        super.onDestroy()
    }
}
