package com.local.voicenotes.ui

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.local.voicenotes.AppUiState
import com.local.voicenotes.AppViewModel
import com.local.voicenotes.domain.LanguageOption
import com.local.voicenotes.domain.TranscriptionProgress
import com.local.voicenotes.inference.MistralApiKeyStatus
import com.local.voicenotes.model.DownloadState
import com.local.voicenotes.model.ParakeetUltraDownload
import com.local.voicenotes.model.active
import com.local.voicenotes.model.formatBytes
import kotlinx.coroutines.launch
import com.local.voicenotes.share.VoiceNoteFolders
import java.util.Locale

/** System file picker opening at [initial]; the system falls back to its default location if unavailable. */
private class OpenDocumentAt(private val initial: () -> android.net.Uri) : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).putExtra(DocumentsContract.EXTRA_INITIAL_URI, initial())
}

/** One-time folder grant, opening at WhatsApp's voice-note folder. */
private class OpenVoiceNoteFolder : ActivityResultContracts.OpenDocumentTree() {
    override fun createIntent(context: Context, input: android.net.Uri?): Intent =
        super.createIntent(context, input ?: VoiceNoteFolders.whatsAppVoiceNotes)
}

private val AUDIO_TYPES = arrayOf("audio/*", "application/octet-stream")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    state: AppUiState,
    viewModel: AppViewModel,
    historyError: String?,
    onWorkStarted: () -> Unit
) {
    // "Files" starts at the top of the internal storage (/storage/emulated/0).
    val audioPicker = rememberLauncherForActivityResult(OpenDocumentAt { VoiceNoteFolders.primaryRoot }) { uri ->
        uri?.let(viewModel::setAudio)
    }
    val whatsAppSystemPicker = rememberLauncherForActivityResult(OpenDocumentAt { VoiceNoteFolders.whatsAppVoiceNotes }) { uri ->
        uri?.let(viewModel::setAudio)
    }
    var showVoiceNotes by rememberSaveable { mutableStateOf(false) }
    val voiceNoteTree by viewModel.voiceNoteTree.collectAsStateWithLifecycle()
    val folderPicker = rememberLauncherForActivityResult(OpenVoiceNoteFolder()) { tree ->
        if (tree != null) {
            viewModel.setVoiceNoteTree(tree)
            showVoiceNotes = true
        }
    }
    val download by viewModel.modelDownload.collectAsStateWithLifecycle()
    val keyStatus by viewModel.keyStatus.collectAsStateWithLifecycle()
    val cloudConsent by viewModel.cloudUploadConsent.collectAsStateWithLifecycle()
    val consentPrompt by viewModel.consentPrompt.collectAsStateWithLifecycle()
    val shareHint by viewModel.shareHint.collectAsStateWithLifecycle()

    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showApiKeyDialog by rememberSaveable { mutableStateOf(false) }
    var showCloudConsent by rememberSaveable { mutableStateOf(false) }
    var confirmMeteredDownload by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(consentPrompt) {
        // Shared audio for a cloud model waits for the same consent dialog as a tap.
        if (consentPrompt) {
            showCloudConsent = true
            viewModel.consentPromptShown()
        }
    }

    val engine = engineOf(state)
    fun startDownload() {
        val metered = context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered == true
        if (metered) confirmMeteredDownload = true else viewModel.downloadModel()
    }
    // A tap while the model is still being prepared starts the transcription right after.
    var pendingTranscribe by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(pendingTranscribe, state.busy) {
        if (pendingTranscribe && !state.busy) {
            pendingTranscribe = false
            if (state.canTranscribe && viewModel.transcribe()) onWorkStarted()
        }
    }
    fun startOrQueue() {
        if (state.progress is TranscriptionProgress.LoadingModel) pendingTranscribe = true
        else if (viewModel.transcribe()) onWorkStarted()
    }
    fun transcribe() {
        if (engine == Engine.CLOUD && !cloudConsent) showCloudConsent = true
        else startOrQueue()
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("Voice notes") },
                actions = {
                    IconButton(onClick = { viewModel.setShowHistory(true) }) {
                        Icon(AppIcons.History, contentDescription = "Transcript history")
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Transcription settings")
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        },
        bottomBar = {
            ActionBar(state, engine, onTranscribe = ::transcribe, onCancel = viewModel::cancel)
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.surface
    ) { padding ->
        val engineSection: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ConnectedToggleGroup(
                    options = Engine.entries,
                    selected = engine,
                    onSelect = viewModel::selectEngine,
                    enabled = !state.working,
                    modifier = Modifier.fillMaxWidth()
                ) { option, _ ->
                    Icon(
                        if (option == Engine.LOCAL) AppIcons.Smartphone else AppIcons.Cloud,
                        contentDescription = null, modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(option.label, style = MaterialTheme.typography.titleMedium)
                }
                AnimatedContent(
                    targetState = engine,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "engineStatus"
                ) { shown ->
                    when (shown) {
                        Engine.LOCAL -> LocalStatus(
                            state, download,
                            onDownload = ::startDownload,
                            onCancelDownload = viewModel::cancelModelDownload,
                            onOpenSettings = { showSettings = true }
                        )
                        Engine.CLOUD -> CloudStatus(
                            keyStatus,
                            onEditKey = { showApiKeyDialog = true }
                        )
                    }
                }
            }
        }
        val audioSection: @Composable () -> Unit = {
            AudioCard(
                state, shareHint,
                onWhatsApp = { if (voiceNoteTree == null) folderPicker.launch(null) else showVoiceNotes = true },
                onFiles = { audioPicker.launch(AUDIO_TYPES) }
            )
        }
        val transcriptSection: @Composable (Modifier, Boolean) -> Unit = { modifier, fill ->
            TranscriptCard(
                state,
                onEdit = viewModel::editTranscript,
                onCopied = { scope.launch { snackbar.showSnackbar("Transcript copied") } },
                modifier = modifier, fillHeight = fill
            )
        }
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            if (maxWidth >= 600.dp) {
                // Unfolded foldables / tablets: controls left, transcript right.
                Row(Modifier.fillMaxSize().padding(horizontal = 24.dp).padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Column(
                        Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        historyError?.let { HistoryErrorBanner(it, onRetry = viewModel::flushHistory) }
                        engineSection()
                        audioSection()
                    }
                    // The transcript fills the pane and scrolls inside its own text field.
                    transcriptSection(Modifier.weight(1f).fillMaxHeight(), true)
                }
            } else {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp).padding(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    historyError?.let { HistoryErrorBanner(it, onRetry = viewModel::flushHistory) }
                    engineSection()
                    audioSection()
                    transcriptSection(Modifier.fillMaxWidth(), false)
                }
            }
        }
    }

    if (showVoiceNotes) {
        VoiceNoteSheet(
            viewModel = viewModel,
            onPick = { uri -> showVoiceNotes = false; viewModel.setAudio(uri) },
            onChangeFolder = { showVoiceNotes = false; folderPicker.launch(null) },
            onSystemPicker = { showVoiceNotes = false; whatsAppSystemPicker.launch(AUDIO_TYPES) },
            onDismiss = { showVoiceNotes = false }
        )
    }
    if (showSettings) {
        SettingsSheet(
            state = state, viewModel = viewModel, download = download, keyStatus = keyStatus,
            onDismiss = { showSettings = false },
            onDownload = ::startDownload,
            onEditKey = { showSettings = false; showApiKeyDialog = true }
        )
    }
    if (showCloudConsent) {
        CloudConsentDialog(
            onConfirm = {
                showCloudConsent = false
                viewModel.grantCloudUploadConsent()
                startOrQueue()
            },
            onDismiss = { showCloudConsent = false }
        )
    }
    if (showApiKeyDialog) ApiKeyDialogHost(viewModel, onDismiss = { showApiKeyDialog = false })
    if (confirmMeteredDownload) {
        MeteredDownloadDialog(
            onConfirm = { confirmMeteredDownload = false; viewModel.downloadModel() },
            onDismiss = { confirmMeteredDownload = false }
        )
    }
}

// ---- Engine status ---------------------------------------------------------------------------

@Composable
private fun SetupCard(content: @Composable () -> Unit) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        ),
        modifier = Modifier.fillMaxWidth().animateContentSize()
    ) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() } }
}

@Composable
private fun LocalStatus(
    state: AppUiState,
    download: DownloadState,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val local = bestLocalModel(state)
    when {
        download.active -> SetupCard {
            Text("Setting up on-device transcription", style = MaterialTheme.typography.titleMedium)
            DownloadProgress(download, onCancelDownload)
        }
        // Ready: nothing to say; loading is shown in the bottom bar.
        local != null -> Unit
        else -> SetupCard {
            Text("Set up on-device transcription", style = MaterialTheme.typography.titleMedium)
            Text(
                "Works offline and keeps your voice notes on this phone. " +
                    "One-time download of ${formatBytes(ParakeetUltraDownload.ENCODER_30S.sizeBytes)}; Wi-Fi recommended.",
                style = MaterialTheme.typography.bodyMedium
            )
            (download as? DownloadState.Failed)?.let { failure ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                    Text(failure.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onDownload, modifier = Modifier.height(48.dp)) {
                    Icon(
                        if (download is DownloadState.Failed) Icons.Default.Refresh else AppIcons.Download,
                        null, Modifier.size(ButtonDefaults.IconSize)
                    )
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text(if (download is DownloadState.Failed) "Retry download" else "Download")
                }
                TextButton(onClick = onOpenSettings) { Text("Import file") }
            }
        }
    }
}

@Composable
fun DownloadProgress(download: DownloadState, onCancel: () -> Unit) {
    val running = download as? DownloadState.Running
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (running != null && running.downloadedBytes > 0) {
            LinearProgressIndicator(
                progress = { running.fraction },
                modifier = Modifier.fillMaxWidth().height(8.dp),
                strokeCap = StrokeCap.Round, gapSize = 4.dp, drawStopIndicator = {}
            )
        } else {
            LinearProgressIndicator(Modifier.fillMaxWidth().height(8.dp), strokeCap = StrokeCap.Round, gapSize = 4.dp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    running != null -> "${formatBytes(running.downloadedBytes)} of ${formatBytes(running.totalBytes)} · " +
                        String.format(Locale.ROOT, "%.0f%%", running.fraction * 100)
                    else -> "Checking integrity…"
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            if (running != null) TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

/** Cloud needs nothing on screen once a key is saved; only a missing/broken key gets a button. */
@Composable
private fun CloudStatus(keyStatus: MistralApiKeyStatus?, onEditKey: () -> Unit) {
    val unreadable = keyStatus == MistralApiKeyStatus.UNREADABLE
    if (keyStatus == null || keyStatus == MistralApiKeyStatus.CONFIGURED) return
    FilledTonalButton(onClick = onEditKey, modifier = Modifier.padding(horizontal = 4.dp)) {
        Icon(if (unreadable) Icons.Default.Warning else AppIcons.Key, null, Modifier.size(ButtonDefaults.IconSize))
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        Text(if (unreadable) "Re-enter API key" else "Add API key")
    }
}

// ---- Audio -------------------------------------------------------------------------------

@Composable
private fun AudioCard(state: AppUiState, shareHint: String?, onWhatsApp: () -> Unit, onFiles: () -> Unit) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth().animateContentSize()
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val hasAudio = state.audioName.isNotBlank()
            if (hasAudio) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    IconBadge(AppIcons.AudioFile)
                    Column(Modifier.weight(1f)) {
                        Text(state.audioName, style = MaterialTheme.typography.titleMedium, maxLines = 2,
                            overflow = TextOverflow.Ellipsis)
                        Text("Voice note",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                Text("Pick a voice note", style = MaterialTheme.typography.titleLarge)
                Text("Or share one from WhatsApp and choose Transcribe.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Waveform(animated = rememberSettledWork(state), modifier = Modifier.fillMaxWidth().height(if (hasAudio) 36.dp else 48.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onWhatsApp, enabled = !state.working, modifier = Modifier.weight(1f).height(48.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp)) {
                    Icon(AppIcons.Chat, null, Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text("WhatsApp", maxLines = 1, softWrap = false)
                }
                OutlinedButton(onClick = onFiles, enabled = !state.working, modifier = Modifier.weight(1f).height(48.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp)) {
                    Icon(AppIcons.Folder, null, Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text("Files", maxLines = 1, softWrap = false)
                }
            }
            shareHint?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
        }
    }
}

// ---- Transcript ------------------------------------------------------------------------

@Composable
private fun TranscriptCard(
    state: AppUiState, onEdit: (String) -> Unit, onCopied: () -> Unit,
    modifier: Modifier = Modifier, fillHeight: Boolean = false
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = modifier
    ) {
        Column(Modifier.then(if (fillHeight) Modifier.fillMaxSize() else Modifier)
            .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Transcript", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                val hasText = state.transcript.isNotBlank()
                IconButton(onClick = { clipboard.setText(AnnotatedString(state.transcript)); onCopied() }, enabled = hasText) {
                    Icon(AppIcons.ContentCopy, contentDescription = "Copy transcript")
                }
                IconButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, state.transcript)
                    context.startActivity(Intent.createChooser(send, "Share transcript"))
                }, enabled = hasText) {
                    Icon(Icons.Default.Share, contentDescription = "Share transcript")
                }
            }
            BasicTextField(
                value = state.transcript, onValueChange = onEdit,
                modifier = Modifier.fillMaxWidth().padding(end = 12.dp).heightIn(min = 140.dp)
                    .then(if (fillHeight) Modifier.weight(1f) else Modifier)
                    .semantics { contentDescription = "Transcript" },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { inner ->
                    Box {
                        if (state.transcript.isBlank()) {
                            Text(
                                "Your words will appear here. You can edit them afterwards.",
                                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        inner()
                    }
                }
            )
        }
    }
}

// ---- Bottom action bar -------------------------------------------------------------------

@Composable
private fun ActionBar(state: AppUiState, engine: Engine, onTranscribe: () -> Unit, onCancel: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 0.dp) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Short runs (most notes take well under a second to start) show no progress UI.
            val settled = rememberSettledWork(state)
            StatusLine(state, engine, settled)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = onTranscribe, enabled = state.canRequestTranscription,
                    shape = CircleShape, modifier = Modifier.weight(1f).height(60.dp),
                    contentPadding = ButtonDefaults.ButtonWithIconContentPadding
                ) {
                    Icon(AppIcons.Mic, null, Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        when {
                            settled -> "Working…"
                            state.transcript.isNotBlank() -> "Transcribe again"
                            else -> "Transcribe"
                        },
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                AnimatedVisibility(visible = settled) {
                    OutlinedButton(onClick = onCancel, shape = CircleShape, modifier = Modifier.height(60.dp)) {
                        Icon(Icons.Default.Close, null, Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text("Cancel")
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusLine(state: AppUiState, engine: Engine, settled: Boolean) {
    val progress = state.progress
    if (state.busy && !settled) return
    val (label, fraction) = when (progress) {
        is TranscriptionProgress.Importing -> "Importing model" to progress.fraction
        TranscriptionProgress.Preparing -> "Preparing" to null
        is TranscriptionProgress.Decoding -> "Decoding audio" to progress.fraction
        TranscriptionProgress.LoadingModel -> "Loading model" to null
        is TranscriptionProgress.Transcribing ->
            (if (engine == Engine.CLOUD) "Transcribing with Mistral" else "Transcribing on device") to progress.fraction
        else -> null to null
    }
    when {
        label != null -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row {
                Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                val percent = fraction?.takeIf { it > 0f }?.let { String.format(Locale.ROOT, "%.0f%% · ", it * 100) }.orEmpty()
                if (state.busy) Text(percent + formatElapsed(state.elapsedMillis), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // Mistral reports no intermediate progress: keep its bar indeterminate.
            if (fraction == null || fraction <= 0f || (engine == Engine.CLOUD && progress is TranscriptionProgress.Transcribing)) {
                LinearProgressIndicator(Modifier.fillMaxWidth().height(6.dp), strokeCap = StrokeCap.Round, gapSize = 4.dp)
            } else {
                LinearProgressIndicator(
                    progress = { fraction }, modifier = Modifier.fillMaxWidth().height(6.dp),
                    strokeCap = StrokeCap.Round, gapSize = 4.dp, drawStopIndicator = {}
                )
            }
        }
        progress is TranscriptionProgress.Failed -> Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
            Text(progress.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        progress is TranscriptionProgress.Completed -> Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Text("Finished in ${formatElapsed(state.elapsedMillis)} · saved to history",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        progress is TranscriptionProgress.Cancelled -> Text("Cancelled", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        !state.canTranscribe -> {
            val hint = when {
                state.selectedModel == null && engine == Engine.LOCAL -> "Download the on-device model to start"
                state.audioUri == null -> "Pick a voice note to start"
                else -> null
            }
            hint?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
fun HistoryErrorBanner(message: String, onRetry: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer
        ),
        shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Warning, null, Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onRetry) { Text("Retry saving") }
        }
    }
}

fun formatElapsed(millis: Long): String {
    val seconds = millis / 1000
    return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
}
