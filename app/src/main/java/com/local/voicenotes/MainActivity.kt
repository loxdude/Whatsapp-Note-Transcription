package com.local.voicenotes

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.local.voicenotes.share.isAcceptedShareScheme
import com.local.voicenotes.share.isShareableAudioType
import com.local.voicenotes.ui.HistoryScreen
import com.local.voicenotes.ui.MainScreen
import com.local.voicenotes.ui.VoiceNotesTheme
import com.local.voicenotes.work.WorkNotifications

class MainActivity : ComponentActivity() {
    companion object {
        /** Marks a share intent as handled so it is not processed twice by this instance. */
        private const val EXTRA_SHARE_CONSUMED = "com.local.voicenotes.SHARE_CONSUMED"
        private const val NOTIFICATION_PERMISSION_ASKED = "notification_permission_asked"
    }

    private val viewModel: AppViewModel by viewModels()

    /** The result does not matter: work runs either way, only notifications are hidden. */
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // A recreated activity (rotation, process restore) gets the original intent again;
        // only a fresh launch may act on a share.
        if (savedInstanceState == null) handleShareIntent(intent)
        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            val showHistory by viewModel.showHistory.collectAsStateWithLifecycle()
            val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
            val dynamicColor by viewModel.dynamicColor.collectAsStateWithLifecycle()
            val historyError by viewModel.historyPersistenceError.collectAsStateWithLifecycle()
            KeepScreenOn(state.busy)
            VoiceNotesTheme(themeMode, dynamicColor) {
                AnimatedContent(
                    targetState = showHistory,
                    transitionSpec = {
                        if (targetState) (slideInHorizontally { it / 3 } + fadeIn()) togetherWith fadeOut()
                        else fadeIn() togetherWith (slideOutHorizontally { it / 3 } + fadeOut())
                    },
                    label = "screen"
                ) { history ->
                    if (history) {
                        val entries by viewModel.history.collectAsStateWithLifecycle()
                        HistoryScreen(
                            entries, historyError, onRetrySave = viewModel::flushHistory,
                            onClose = { viewModel.setShowHistory(false) }, onDelete = viewModel::deleteHistoryEntry
                        )
                    } else {
                        MainScreen(state, viewModel, historyError, onWorkStarted = ::requestNotificationPermissionOnce)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        // The user is looking at the result now.
        WorkNotifications.cancelFinished(this)
    }

    override fun onStop() {
        viewModel.flushHistory()
        super.onStop()
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent == null || intent.action != Intent.ACTION_SEND) return
        if (intent.getBooleanExtra(EXTRA_SHARE_CONSUMED, false)) return
        // Relaunch from recents replays the original share; its temporary grant is gone.
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        intent.putExtra(EXTRA_SHARE_CONSUMED, true)
        if (!isShareableAudioType(intent.type)) return
        val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
            ?: return
        if (!isAcceptedShareScheme(uri.scheme)) {
            // file:// and other schemes would be read with this app's own permissions.
            Toast.makeText(this, "This share is not supported. Share the file from an app that provides it as content.", Toast.LENGTH_LONG).show()
            return
        }
        viewModel.onSharedAudio(uri)
        requestNotificationPermissionOnce()
    }

    /** Asks for POST_NOTIFICATIONS once (API 33+), when the first transcription starts. */
    private fun requestNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) return
        val preferences = getSharedPreferences("privacy", 0)
        if (preferences.getBoolean(NOTIFICATION_PERMISSION_ASKED, false)) return
        preferences.edit().putBoolean(NOTIFICATION_PERMISSION_ASKED, true).apply()
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

@Composable
private fun KeepScreenOn(enabled: Boolean) {
    val activity = LocalActivity.current
    DisposableEffect(enabled) {
        if (enabled) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { if (enabled) activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}
