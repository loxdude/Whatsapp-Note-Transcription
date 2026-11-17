package com.local.voicenotes.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.local.voicenotes.AppViewModel
import com.local.voicenotes.inference.MistralApiKeyStatus
import com.local.voicenotes.model.ParakeetUltraDownload
import com.local.voicenotes.model.formatBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun CloudConsentDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(AppIcons.Cloud, null) },
        title = { Text("Upload audio to Mistral?") },
        text = {
            Text(
                "Cloud transcription uploads your audio file to Mistral AI. On-device transcription " +
                    "never uploads audio. Your choice is remembered: later uploads start without asking. " +
                    "You can turn this off in Settings."
            )
        },
        confirmButton = { Button(onClick = onConfirm) { Text("Allow and transcribe") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun MeteredDownloadDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(AppIcons.Download, null) },
        title = { Text("Download over mobile data?") },
        text = {
            Text(
                "The on-device model is ${formatBytes(ParakeetUltraDownload.ENCODER_30S.sizeBytes)}. " +
                    "Your current connection may be metered."
            )
        },
        confirmButton = { Button(onClick = onConfirm) { Text("Download anyway") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } }
    )
}

@Composable
fun RemoveModelDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Delete, null) },
        title = { Text("Remove the on-device model?") },
        text = {
            Text(
                "This frees ${formatBytes(ParakeetUltraDownload.ENCODER_30S.sizeBytes)}. You can download it again " +
                    "at any time; your transcripts are kept."
            )
        },
        confirmButton = {
            Button(onClick = onConfirm, colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError
            )) { Text("Remove") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Loads the saved-key status asynchronously (it may wait on the key store lock), then edits it. */
@Composable
fun ApiKeyDialogHost(viewModel: AppViewModel, onDismiss: () -> Unit) {
    var keyStatus by remember { mutableStateOf<MistralApiKeyStatus?>(null) }
    var statusError by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) {
        statusError = null
        try { keyStatus = viewModel.apiKeyStatus() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { statusError = "Could not load saved-key status. Please try again." }
    }
    val loaded = keyStatus
    if (loaded == null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            icon = { Icon(AppIcons.Key, null) },
            title = { Text("Mistral API Key") },
            text = { Text(statusError ?: "Checking saved-key status…") },
            confirmButton = { if (statusError != null) TextButton(onClick = { reload++ }) { Text("Retry") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
        )
    } else {
        ApiKeyDialog(
            status = loaded,
            onSave = { key -> viewModel.setApiKey(key).also { if (it == null) onDismiss() } },
            onDismiss = onDismiss
        )
    }
}

@Composable
private fun ApiKeyDialog(
    status: MistralApiKeyStatus,
    /** Returns an error message, or null when saved. */
    onSave: suspend (String) -> String?,
    onDismiss: () -> Unit
) {
    // The stored key is never decrypted into the UI; the field starts empty.
    var apiKey by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    fun save(value: String) {
        if (saving) return
        saving = true
        scope.launch {
            try { error = onSave(value) } finally { saving = false }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        icon = { Icon(AppIcons.Key, null) },
        title = { Text("Mistral API Key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    when (status) {
                        MistralApiKeyStatus.CONFIGURED, MistralApiKeyStatus.CONFIGURED_UNENCRYPTED -> "A key is saved. Enter a new one to replace it."
                        MistralApiKeyStatus.UNREADABLE -> "The saved key could not be decrypted. Enter it again."
                        else -> "Paste a key from console.mistral.ai to use cloud transcription."
                    },
                    color = if (status == MistralApiKeyStatus.UNREADABLE) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://console.mistral.ai/api-keys")))
                    } catch (_: android.content.ActivityNotFoundException) {
                        android.widget.Toast.makeText(context, "No browser available.", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }) { Text("Open console.mistral.ai") }
                OutlinedTextField(
                    value = apiKey, onValueChange = { apiKey = it },
                    label = { Text("API Key") }, placeholder = { Text("Paste your key") },
                    visualTransformation = PasswordVisualTransformation(), singleLine = true,
                    enabled = !saving, modifier = Modifier.fillMaxWidth()
                )
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                if (status != MistralApiKeyStatus.ABSENT) {
                    TextButton(
                        onClick = { save("") }, enabled = !saving,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) { Text("Remove saved key") }
                }
            }
        },
        confirmButton = {
            Button(onClick = { save(apiKey) }, enabled = apiKey.isNotBlank() && !saving) {
                Text(if (saving) "Saving…" else "Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("Cancel") } }
    )
}
