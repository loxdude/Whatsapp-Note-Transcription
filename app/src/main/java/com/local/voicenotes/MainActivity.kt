package com.local.voicenotes

import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ViewRootForInspector
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            KeepScreenOn(state.busy)
            AppTheme { TranscriberScreen(state, viewModel) }
        }
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
private fun TranscriberScreen(state: AppUiState, viewModel: AppViewModel) {
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::setAudio)
    }
    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importModel)
    }
    var showApiKeyDialog by remember { mutableStateOf(false) }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = 50.dp, start = 20.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Voice notes", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                IconButton(onClick = { showApiKeyDialog = true }) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings")
                }
            }

            SectionCard("Settings") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Audio (MP3, Opus, M4A, AAC, WAV)", fontWeight = FontWeight.SemiBold)
                    OutlinedButton(
                        onClick = { audioPicker.launch(arrayOf("audio/*", "application/octet-stream")) },
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (state.audioName.isBlank()) "Choose a voice note" else state.audioName,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }

                    Text("Model", fontWeight = FontWeight.SemiBold)
                    var modelExpanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(modelExpanded, { modelExpanded = !modelExpanded && !state.busy }) {
                        OutlinedTextField(
                            value = state.selectedModel?.displayName ?: "No model imported",
                            onValueChange = {}, readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(modelExpanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth(),
                            label = { Text("LiteRT model") }
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
                    var langExpanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(langExpanded, { langExpanded = !langExpanded && !state.busy }) {
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
                }
            }

            ProgressArea(state)

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = viewModel::transcribe, enabled = state.canTranscribe,
                    modifier = Modifier.weight(1f).height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) { Text("Transcribe") }
                if (state.busy) OutlinedButton(onClick = viewModel::cancel, modifier = Modifier.height(48.dp)) {
                    Text("Cancel")
                }
            }

            OutlinedTextField(
                value = state.transcript, onValueChange = viewModel::editTranscript,
                modifier = Modifier.fillMaxWidth().weight(1f),
                label = { Text("Transcript") }, placeholder = { Text("The transcript will appear here.") }
            )
        }
    }

    if (showApiKeyDialog) {
        ApiKeyDialog(
            currentApiKey = viewModel.getApiKey(),
            onSave = { apiKey ->
                viewModel.setApiKey(apiKey)
                showApiKeyDialog = false
            },
            onDismiss = { showApiKeyDialog = false }
        )
    }
}

@Composable
private fun ApiKeyDialog(
    currentApiKey: String?,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var apiKey by remember { mutableStateOf(currentApiKey ?: "") }
    val context = LocalContext.current

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mistral API Key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Enter your Mistral API key to use the Mistral Transcription API.")
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
            }
        },
        confirmButton = {
            Button(onClick = { onSave(apiKey) }) {
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
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(12.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, fontWeight = FontWeight.SemiBold)
        content()
    }
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
            Text(label, color = if (state.progress is TranscriptionProgress.Failed) Color(0xFF9A4545) else Color(0xFF535950))
            if (state.busy || state.elapsedMillis > 0) Text(formatElapsed(state.elapsedMillis), color = Color(0xFF535950))
        }
        if (state.busy || fraction != null) {
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
