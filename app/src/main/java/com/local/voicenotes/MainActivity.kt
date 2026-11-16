package com.local.voicenotes

import android.Manifest
import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.local.voicenotes.history.HistoryEntry
import com.local.voicenotes.share.isShareableAudioType
import com.local.voicenotes.work.WorkNotifications
import java.text.DateFormat
import java.util.Date
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.local.voicenotes.domain.LanguageOption
import com.local.voicenotes.domain.TranscriptionProgress
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import java.util.Locale

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
        super.onCreate(savedInstanceState)
        // A recreated activity (rotation, process restore) gets the original intent again;
        // only a fresh launch may act on a share.
        if (savedInstanceState == null) handleShareIntent(intent)
        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            val showHistory by viewModel.showHistory.collectAsStateWithLifecycle()
            KeepScreenOn(state.busy)
            AppTheme {
                if (showHistory) {
                    val entries by viewModel.history.collectAsStateWithLifecycle()
                    HistoryScreen(entries, onClose = { viewModel.setShowHistory(false) },
                        onDelete = viewModel::deleteHistoryEntry)
                } else {
                    TranscriberScreen(state, viewModel, onWorkStarted = ::requestNotificationPermissionOnce)
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

/** Opens the picker in WhatsApp's voice-note folder; the system falls back to its default location if unavailable. */
private class WhatsAppVoiceNotePicker : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).putExtra(
            DocumentsContract.EXTRA_INITIAL_URI,
            DocumentsContract.buildDocumentUri(
                "com.android.externalstorage.documents",
                "primary:Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes"
            )
        )
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

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF49664B), onPrimary = Color.White,
            background = Color(0xFFF7F7F0), surface = Color(0xFFFFFFFF),
            onBackground = Color(0xFF20231F), onSurface = Color(0xFF20231F)
        ), content = content
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TranscriberScreen(state: AppUiState, viewModel: AppViewModel, onWorkStarted: () -> Unit) {
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::setAudio)
    }
    val whatsAppPicker = rememberLauncherForActivityResult(WhatsAppVoiceNotePicker()) { uri ->
        uri?.let(viewModel::setAudio)
    }
    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importModel)
    }
    var showApiKeyDialog by rememberSaveable { mutableStateOf(false) }
    var showCloudConsent by rememberSaveable { mutableStateOf(false) }
    val cloudConsent by viewModel.cloudUploadConsent.collectAsStateWithLifecycle()
    val cloudSelected = state.selectedModel?.backend == "mistral-api"
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val consentPrompt by viewModel.consentPrompt.collectAsStateWithLifecycle()
    LaunchedEffect(consentPrompt) {
        // Shared audio for a cloud model waits for the same consent dialog as a tap.
        if (consentPrompt) {
            showCloudConsent = true
            viewModel.consentPromptShown()
        }
    }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Voice notes", modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                IconButton(onClick = { viewModel.setShowHistory(true) }) {
                    Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Transcript history")
                }
                IconButton(onClick = { showSettings = true }) {
                    Icon(Icons.Default.Settings, contentDescription = "Transcription settings")
                }
            }

            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("AUDIO", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            Text(state.audioName.ifBlank { "Choose a voice note" },
                                style = MaterialTheme.typography.titleSmall, maxLines = 2,
                                overflow = TextOverflow.Ellipsis)
                        }
                        TextButton(
                            onClick = { audioPicker.launch(arrayOf("audio/*", "application/octet-stream")) },
                            enabled = !state.busy
                        ) { Text("Select audio") }
                    }
                    TextButton(
                        onClick = { whatsAppPicker.launch(arrayOf("audio/*", "application/octet-stream")) },
                        enabled = !state.busy
                    ) { Text("WhatsApp voice notes") }
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text("TRANSCRIPTION", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        val modelSummary = when (state.selectedModel?.backend) {
                            "mistral-api" -> "Cloud model: Mistral"
                            null -> "No model selected. Import a model or choose Mistral in settings."
                            else -> "On-device model: Parakeet"
                        }
                        Text(modelSummary, style = MaterialTheme.typography.labelMedium)
                        Text(if (cloudSelected) "Language: ${state.language.label}"
                            else "Language: detected automatically",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (state.busy || state.progress !is TranscriptionProgress.Idle) {
                        ProgressArea(state)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                                if (cloudSelected && !cloudConsent) showCloudConsent = true
                                else if (viewModel.transcribe()) onWorkStarted()
                            }, enabled = state.canTranscribe,
                            modifier = Modifier.weight(1f).height(48.dp)) {
                            Text(if (state.busy) "Working..." else if (state.transcript.isNotBlank()) "Transcribe again" else "Transcribe")
                        }
                        if (state.busy) OutlinedButton(onClick = viewModel::cancel,
                            modifier = Modifier.height(48.dp)) { Text("Cancel") }
                    }
                }
            }

            Surface(modifier = Modifier.fillMaxWidth().weight(1f),
                shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Transcript", modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        TextButton(onClick = { clipboard.setText(AnnotatedString(state.transcript)) },
                            enabled = state.transcript.isNotBlank()) { Text("Copy") }
                    }
                    BasicTextField(
                        value = state.transcript, onValueChange = viewModel::editTranscript,
                        modifier = Modifier.fillMaxWidth().weight(1f).padding(bottom = 16.dp)
                            .semantics { contentDescription = "Transcript" },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = Brush.verticalGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primary)),
                        decorationBox = { innerTextField ->
                            Box {
                                if (state.transcript.isBlank()) {
                                    Text(if (state.busy) "Your transcript is on its way..."
                                        else "Choose audio, then tap Transcribe.\nYour words will appear here.",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                innerTextField()
                            }
                        }
                    )
                }
            }
        }
    }

    if (showSettings) {
        ModalBottomSheet(onDismissRequest = { showSettings = false }) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Transcription settings", style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold)
                Text("Model", fontWeight = FontWeight.SemiBold)
                var modelExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(modelExpanded, { modelExpanded = !modelExpanded && !state.busy }) {
                    OutlinedTextField(
                        value = state.selectedModel?.displayName ?: "No model imported",
                        onValueChange = {}, readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(modelExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        label = { Text("Transcription model") }
                    )
                    DropdownMenu(expanded = modelExpanded, onDismissRequest = { modelExpanded = false }) {
                        state.models.forEach { model ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(model.displayName)
                                        Text(model.note, style = MaterialTheme.typography.bodySmall,
                                            color = if (model.enabled) Color(0xFF49664B) else Color(0xFF9A4545))
                                    }
                                },
                                enabled = model.enabled,
                                onClick = { viewModel.selectModel(model.id); modelExpanded = false }
                            )
                        }
                    }
                }
                TextButton(onClick = { modelPicker.launch(arrayOf("application/octet-stream", "*/*")) },
                    enabled = !state.busy) { Text("Import .tflite") }

                Text("Language", fontWeight = FontWeight.SemiBold)
                if (!cloudSelected) {
                    // Parakeet identifies the language acoustically and ignores this setting.
                    Text("The on-device model detects the language automatically. " +
                        "This setting only applies to the Mistral cloud model.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                var langExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(langExpanded, { langExpanded = !langExpanded && !state.busy && cloudSelected }) {
                    OutlinedTextField(
                        value = state.language.label, onValueChange = {}, readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(langExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    DropdownMenu(expanded = langExpanded, onDismissRequest = { langExpanded = false }) {
                        LanguageOption.entries.forEach { language ->
                            DropdownMenuItem(text = { Text(language.label) }, onClick = {
                                viewModel.setLanguage(language); langExpanded = false
                            })
                        }
                    }
                }
                OutlinedButton(onClick = { showSettings = false; showApiKeyDialog = true },
                    enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                    Text("Add or change Mistral API key")
                }
                Button(onClick = { showSettings = false }, modifier = Modifier.fillMaxWidth()) {
                    Text("Done")
                }
            }
        }
    }

    if (showCloudConsent) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showCloudConsent = false },
            title = { Text("Upload audio to Mistral?") },
            text = {
                Text("The selected model runs in the cloud. Your audio file will be uploaded to " +
                    "Mistral AI for transcription. On-device models never upload audio. " +
                    "You can switch models in settings at any time.")
            },
            confirmButton = {
                Button(onClick = {
                    showCloudConsent = false
                    viewModel.grantCloudUploadConsent()
                    if (viewModel.transcribe()) onWorkStarted()
                }) { Text("Upload and transcribe") }
            },
            dismissButton = {
                TextButton(onClick = { showCloudConsent = false }) { Text("Cancel") }
            }
        )
    }

    if (showApiKeyDialog) {
        val keyStatus = remember { viewModel.apiKeyStatus() }
        ApiKeyDialog(
            status = keyStatus,
            onSave = { apiKey -> viewModel.setApiKey(apiKey).also { if (it == null) showApiKeyDialog = false } },
            onDismiss = { showApiKeyDialog = false }
        )
    }
}

@Composable
private fun ApiKeyDialog(
    status: com.local.voicenotes.inference.MistralApiKeyStatus,
    /** Returns an error message, or null when saved. */
    onSave: (String) -> String?,
    onDismiss: () -> Unit
) {
    // The stored key is never decrypted into the UI; the field starts empty.
    var apiKey by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mistral API Key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Enter your Mistral API key to use the Mistral Transcription API.")
                when (status) {
                    com.local.voicenotes.inference.MistralApiKeyStatus.CONFIGURED ->
                        Text("A key is saved. Enter a new one to replace it.", style = MaterialTheme.typography.bodySmall)
                    com.local.voicenotes.inference.MistralApiKeyStatus.UNREADABLE ->
                        Text("The saved key could not be decrypted. Enter it again.",
                            style = MaterialTheme.typography.bodySmall, color = Color(0xFF9A4545))
                    else -> Unit
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Need a key?", fontWeight = FontWeight.SemiBold)
                    Text(
                        "1. Open console.mistral.ai",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.clickable {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://console.mistral.ai"))
                            context.startActivity(intent)
                        }
                    )
                    Text("2. Create or copy an API key")
                    Text("3. Paste it here and tap Save")
                }
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key") },
                    placeholder = { Text("sk-...") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Color(0xFF9A4545)) }
            }
        },
        confirmButton = {
            Button(onClick = { error = onSave(apiKey) }, enabled = apiKey.isNotBlank()) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun ProgressArea(state: AppUiState) {
    val (label, fraction) = when (val progress = state.progress) {
        TranscriptionProgress.Idle -> "Ready" to null
        is TranscriptionProgress.Importing -> "Importing model" to progress.fraction
        TranscriptionProgress.Preparing -> "Preparing" to null
        is TranscriptionProgress.Decoding -> "Decoding audio" to progress.fraction
        TranscriptionProgress.LoadingModel -> "Loading model" to null
        is TranscriptionProgress.Transcribing -> "Transcribing" to progress.fraction
        is TranscriptionProgress.Completed -> "Finished" to 1f
        TranscriptionProgress.Cancelled -> "Cancelled" to null
        is TranscriptionProgress.Failed -> progress.message to null
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = if (state.progress is TranscriptionProgress.Failed) Color(0xFF9A4545) else Color(0xFF535950))
            if (state.busy || state.elapsedMillis > 0) Text(formatElapsed(state.elapsedMillis), style = MaterialTheme.typography.bodySmall, color = Color(0xFF535950))
        }
        if (state.busy) {
            if (state.progress is TranscriptionProgress.Transcribing &&
                state.selectedModel?.backend == "mistral-api") {
                MistralProgressIndicator(Modifier.fillMaxWidth())
            } else if (fraction == null || fraction <= 0f) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun MistralProgressIndicator(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "mistralProgress")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
        label = "stripePhase"
    )
    Box(
        modifier = modifier
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(Color(0xFFB8BBB6))
            .padding(0.5.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(2.dp))) {
            val stripeWidth = size.height * 2.4f
            val offset = phase * stripeWidth * 2f
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF49664B),
                        Color(0xFF49664B),
                        Color.White.copy(alpha = 0.18f),
                        Color.White.copy(alpha = 0.18f),
                        Color(0xFF49664B)
                    ),
                    start = Offset(-stripeWidth + offset, 0f),
                    end = Offset(stripeWidth + offset, size.height),
                    tileMode = TileMode.Repeated
                )
            )
        }
    }
}

private fun formatElapsed(millis: Long): String {
    val seconds = millis / 1000
    return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
}

@Composable
private fun HistoryScreen(entries: List<HistoryEntry>, onClose: () -> Unit, onDelete: (String) -> Unit) {
    BackHandler(onBack = onClose)
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var pendingDelete by rememberSaveable { mutableStateOf<String?>(null) }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Text("History", modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            if (entries.isEmpty()) {
                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth()) {
                    Text("Finished transcripts appear here. The newest 100 are kept on this device.",
                        modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 8.dp)
                ) {
                    items(entries, key = { it.id }) { entry ->
                        HistoryCard(
                            entry,
                            onCopy = { clipboard.setText(AnnotatedString(entry.text)) },
                            onShare = {
                                val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                                    .putExtra(Intent.EXTRA_TEXT, entry.text)
                                    .putExtra(Intent.EXTRA_SUBJECT, entry.audioName)
                                context.startActivity(Intent.createChooser(send, "Share transcript"))
                            },
                            onDelete = { pendingDelete = entry.id }
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { id ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete transcript?") },
            text = { Text("This removes the transcript from the history on this device.") },
            confirmButton = {
                Button(onClick = { onDelete(id); pendingDelete = null }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun HistoryCard(entry: HistoryEntry, onCopy: () -> Unit, onShare: () -> Unit, onDelete: () -> Unit) {
    var expanded by rememberSaveable(entry.id) { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(entry.audioName.ifBlank { "Voice note" }, style = MaterialTheme.typography.titleSmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = 8.dp))
            val details = listOfNotNull(
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(entry.createdAtMillis)),
                entry.audioDurationMillis?.let(::formatElapsed),
                entry.modelName.takeIf { it.isNotBlank() }
            ).joinToString(" · ")
            Text(details, style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = 8.dp))
            Text(entry.text, style = MaterialTheme.typography.bodyMedium,
                maxLines = if (expanded) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(end = 8.dp, top = 4.dp)
                    .clickable { expanded = !expanded })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onCopy) { Text("Copy") }
                TextButton(onClick = onShare) { Text("Share") }
                TextButton(onClick = onDelete) { Text("Delete", color = Color(0xFF9A4545)) }
            }
        }
    }
}
