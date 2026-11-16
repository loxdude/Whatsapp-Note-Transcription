package com.local.voicenotes.work

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.local.voicenotes.MainActivity
import com.local.voicenotes.R
import com.local.voicenotes.appGraph

/** Notification channels and builders for background work. */
object WorkNotifications {
    const val CHANNEL_PROGRESS = "transcription_progress"
    const val CHANNEL_RESULTS = "transcription_results"
    const val ONGOING_ID = 1
    const val FINISHED_ID = 2

    fun ensureChannels(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        manager.createNotificationChannelsCompat(
            listOf(
                NotificationChannelCompat.Builder(CHANNEL_PROGRESS, NotificationManagerCompat.IMPORTANCE_LOW)
                    .setName("Transcription progress")
                    .setDescription("Shown while a voice note is transcribed or a model is imported.")
                    .setShowBadge(false)
                    .build(),
                NotificationChannelCompat.Builder(CHANNEL_RESULTS, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                    .setName("Finished transcriptions")
                    .setDescription("Tells you when a transcription finished while the app was in the background.")
                    .build()
            )
        )
    }

    /** Opens the app like the launcher does: brings the existing task to the front. */
    private fun openAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return PendingIntent.getActivity(
            context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    fun ongoing(context: Context, notice: OngoingNotice): Notification {
        val cancel = PendingIntent.getBroadcast(
            context, 0, Intent(context, CancelWorkReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val text = notice.percent?.let { "${notice.text} · $it%" } ?: notice.text
        return NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_voice_note)
            .setContentTitle(notice.title)
            .setContentText(text)
            .setProgress(100, notice.percent ?: 0, notice.percent == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openAppIntent(context))
            .addAction(0, "Cancel", cancel)
            .build()
    }

    /** Posts [notice] if the user allows notifications; silently does nothing otherwise. */
    fun postFinished(context: Context, notice: FinishedNotice) {
        if (!canPost(context)) return
        val notification = NotificationCompat.Builder(context, CHANNEL_RESULTS)
            .setSmallIcon(R.drawable.ic_stat_voice_note)
            .setContentTitle(notice.title)
            .setContentText(notice.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notice.text))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context))
            .build()
        try {
            NotificationManagerCompat.from(context).notify(FINISHED_ID, notification)
        } catch (denied: SecurityException) {
            // Permission revoked between the check and the call; nothing to show.
        }
    }

    fun cancelFinished(context: Context) = NotificationManagerCompat.from(context).cancel(FINISHED_ID)

    fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return false
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
}

/** Handles the notification's Cancel action; not exported, only our PendingIntent reaches it. */
class CancelWorkReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // The service observes the resulting Cancelled state and stops itself.
        context.appGraph.coordinator.cancel()
    }
}
