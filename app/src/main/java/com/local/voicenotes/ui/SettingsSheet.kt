package com.local.voicenotes.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.local.voicenotes.AppUiState
import com.local.voicenotes.AppViewModel
import com.local.voicenotes.inference.MistralApiKeyStatus
import com.local.voicenotes.isCloud
import com.local.voicenotes.model.DownloadState
import com.local.voicenotes.model.ParakeetUltraDownload
import com.local.voicenotes.model.active
import com.local.voicenotes.model.formatBytes

/** CC-BY-4.0 attribution for the bundled/downloaded Parakeet Ultra graphs. */
private const val ULTRA_ATTRIBUTION =
    "Parakeet Ultra graphs from litert-community/parakeet-ultra (CC-BY-4.0), converted from " +
        "NVIDIA parakeet-tdt-0.6b-v3 via moondream/parakeet-ultra. Opus decoding uses libopus, " +
        "opusfile and libogg (BSD licences)."

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    state: AppUiState,
    viewModel: AppViewModel,
    download: DownloadState,
    keyStatus: MistralApiKeyStatus?,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onEditKey: () -> Unit
) {
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val dynamicColor by viewModel.dynamicColor.collectAsStateWithLifecycle()
    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importModel)
    }
    var confirmRemove by rememberSaveable { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Settings", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 4.dp))

            // ---- Appearance
            SectionLabel("Appearance")
            SettingsCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Theme", style = MaterialTheme.typography.titleMedium)
                    ConnectedToggleGroup(
                        options = ThemeMode.entries, selected = themeMode, onSelect = viewModel::setThemeMode,
                        modifier = Modifier.fillMaxWidth(), height = 48.dp
                    ) { mode, _ -> Text(mode.label, style = MaterialTheme.typography.labelLarge) }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerLow)
                ListItem(
                    headlineContent = { Text("Wallpaper colours") },
                    supportingContent = { Text("Material You: match the colours of your wallpaper") },
                    leadingContent = { Icon(AppIcons.Palette, null) },
                    trailingContent = { Switch(checked = dynamicColor, onCheckedChange = viewModel::setDynamicColor) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
            }

            // ---- On-device model
            SectionLabel("On-device model")
            SettingsCard {
                val downloaded = state.models.firstOrNull { it.id == ParakeetUltraDownload.MODEL_ID }
                val imported = state.models.filter { it.enabled && !it.isCloud() && it.id != ParakeetUltraDownload.MODEL_ID }
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        IconBadge(AppIcons.Smartphone, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                        Column(Modifier.weight(1f)) {
                            Text("Speech model", style = MaterialTheme.typography.titleMedium)
                            Text(
                                when {
                                    download.active -> "Downloading…"
                                    downloaded != null -> "Installed · ${formatBytes(downloaded.sizeBytes)}"
                                    imported.isNotEmpty() -> "Installed from a file"
                                    else -> "Not installed"
                                },
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    when {
                        download.active -> DownloadProgress(download, viewModel::cancelModelDownload)
                        downloaded != null -> OutlinedButton(
                            onClick = { confirmRemove = true }, enabled = !state.busy,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Icon(Icons.Default.Delete, null, Modifier.size(ButtonDefaults.IconSize))
                            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                            Text("Remove download")
                        }
                        else -> {
                            (download as? DownloadState.Failed)?.let {
                                Text(it.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                            }
                            Button(onClick = onDownload) {
                                Icon(AppIcons.Download, null, Modifier.size(ButtonDefaults.IconSize))
                                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                                Text("Download model (${formatBytes(ParakeetUltraDownload.ENCODER_30S.sizeBytes)})")
                            }
                        }
                    }
                    TextButton(
                        onClick = { modelPicker.launch(arrayOf("application/octet-stream", "*/*")) },
                        enabled = !state.busy
                    ) { Text("Import .tflite file instead") }
                }
            }

            // ---- Cloud
            SectionLabel("Cloud · Mistral")
            SettingsCard {
                ListItem(
                    headlineContent = { Text("Mistral API key") },
                    supportingContent = {
                        Text(
                            when (keyStatus) {
                                null -> "Checking…"
                                MistralApiKeyStatus.CONFIGURED -> "Saved on this device, encrypted"
                                MistralApiKeyStatus.CONFIGURED_UNENCRYPTED -> "Saved on this device, not yet encrypted"
                                MistralApiKeyStatus.UNREADABLE -> "Could not be decrypted; enter it again"
                                else -> "Not set"
                            }
                        )
                    },
                    leadingContent = { Icon(AppIcons.Key, null) },
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable(enabled = !state.busy, onClickLabel = "Change Mistral API key", onClick = onEditKey)
                )
            }

            // ---- About
            SectionLabel("About")
            SettingsCard {
                val context = LocalContext.current
                val version = remember(context) {
                    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
                }
                ListItem(
                    headlineContent = { Text("Voice Notes $version") },
                    supportingContent = { Text(ULTRA_ATTRIBUTION) },
                    leadingContent = { Icon(Icons.Default.Info, null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
            }
            Spacer(Modifier.height(4.dp))
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Done") }
        }
    }

    if (confirmRemove) {
        RemoveModelDialog(
            onConfirm = { confirmRemove = false; viewModel.removeDownloadedModel() },
            onDismiss = { confirmRemove = false }
        )
    }
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth()
    ) { content() }
}
