package com.local.voicenotes

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.local.voicenotes.domain.LanguageOption
import com.local.voicenotes.domain.TranscriptionProgress
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
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Voice notes, readable.", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Everything stays on this phone.", color = Color(0xFF60665E))

            SectionCard("Audio") {
                OutlinedButton(
                    onClick = { audioPicker.launch(arrayOf("audio/*", "application/ogg")) },
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (state.audioName.isBlank()) "Choose a voice note" else state.audioName,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text("WhatsApp OGG/Opus, MP3, M4A, AAC or WAV · up to 15 minutes",
                    style = MaterialTheme.typography.bodySmall, color = Color(0xFF6B7168))
            }

            SectionCard("Model") {
                var expanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded, { expanded = !expanded && !state.busy }) {
                    OutlinedTextField(
                        value = state.selectedModel?.displayName ?: "No compatible model imported",
                        onValueChange = {}, readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        label = { Text("Qwen model") }
                    )
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
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
                                onClick = { viewModel.selectModel(model.id); expanded = false }
                            )
                        }
                    }
                }
                TextButton(onClick = { modelPicker.launch(arrayOf("application/octet-stream", "*/*")) },
                    enabled = !state.busy) { Text("Import GGUF model") }
            }

            SectionCard("Language") {
                var expanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded, { expanded = !expanded && !state.busy }) {
                    OutlinedTextField(
                        value = state.language.label, onValueChange = {}, readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        LanguageOption.entries.forEach { language ->
                            DropdownMenuItem(text = { Text(language.label) }, onClick = {
                                viewModel.setLanguage(language); expanded = false
                            })
                        }
                    }
                }
            }

            ProgressArea(state)

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = viewModel::transcribe, enabled = state.canTranscribe,
                    modifier = Modifier.weight(1f).height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) { Text("Transcribe") }
                if (state.busy) OutlinedButton(onClick = viewModel::cancel, modifier = Modifier.height(52.dp)) {
                    Text("Cancel")
                }
            }

            OutlinedTextField(
                value = state.transcript, onValueChange = viewModel::editTranscript,
                modifier = Modifier.fillMaxWidth().height(240.dp),
                label = { Text("Transcript") }, placeholder = { Text("The transcript will appear here.") }
            )
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(16.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
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
            if (fraction == null || fraction <= 0f) LinearProgressIndicator(Modifier.fillMaxWidth())
            else LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        }
    }
}

private fun formatElapsed(millis: Long): String {
    val seconds = millis / 1000
    return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
}
