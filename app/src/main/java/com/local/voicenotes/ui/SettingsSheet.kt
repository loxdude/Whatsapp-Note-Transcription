package com.local.voicenotes.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.local.voicenotes.AppUiState
import com.local.voicenotes.AppViewModel
import com.local.voicenotes.inference.MistralApiKeyStatus
import com.local.voicenotes.isCloud
import com.local.voicenotes.model.CohereArabicModel
import com.local.voicenotes.model.DownloadSpec
import com.local.voicenotes.model.DownloadState
import com.local.voicenotes.model.LocalModelChoice
import com.local.voicenotes.model.ModelSpecs
import com.local.voicenotes.model.ParakeetUltraDownload
import com.local.voicenotes.model.TargetLanguage
import com.local.voicenotes.model.TranslationModel
import com.local.voicenotes.model.TranslationSettings
import com.local.voicenotes.model.active
import com.local.voicenotes.model.formatBytes
import com.local.voicenotes.model.translationModelsReplacedBy
import com.local.voicenotes.translate.TranslationText

/** Attribution for the bundled and downloadable models and native libraries. */
private const val ULTRA_ATTRIBUTION =
    "Parakeet Ultra graphs from litert-community/parakeet-ultra (CC-BY-4.0), converted from " +
        "NVIDIA parakeet-tdt-0.6b-v3 via moondream/parakeet-ultra. Arabic: Cohere Transcribe Arabic " +
        "GGUF from cstr (Apache-2.0), run with CrispASR and ggml (MIT). Translation: Gemma 4 from " +
        "litert-community (Apache-2.0) via LiteRT-LM. Opus decoding uses libopus, opusfile and " +
        "libogg (BSD licences)."

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    state: AppUiState,
    viewModel: AppViewModel,
    downloads: Map<String, DownloadState>,
    installed: Set<String>,
    localModel: LocalModelChoice,
    translation: TranslationSettings,
    keyStatus: MistralApiKeyStatus?,
    onDismiss: () -> Unit,
    onDownload: (DownloadSpec) -> Unit,
    onEditKey: () -> Unit
) {
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val dynamicColor by viewModel.dynamicColor.collectAsStateWithLifecycle()
    val cloudConsent by viewModel.cloudUploadConsent.collectAsStateWithLifecycle()
    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importModel)
    }
    /** Spec id (or [TRANSLATION_MODELS]) whose removal awaits confirmation. */
    var confirmRemove by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmSwitch by remember { mutableStateOf<Pair<TranslationModel, List<String>>?>(null) }
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
                    trailingContent = { Switch(checked = dynamicColor, onCheckedChange = null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.toggleable(value = dynamicColor, role = Role.Switch, onValueChange = viewModel::setDynamicColor)
                )
            }

            // ---- Local transcription
            SectionLabel("Local transcription")
            SettingsCard {
                val arabicRecord = state.models.firstOrNull { it.id == CohereArabicModel.MODEL_ID }
                val downloaded = state.models.firstOrNull { it.id == ParakeetUltraDownload.MODEL_ID }
                val imported = state.models.filter {
                    it.enabled && !it.isCloud() && it.id != ParakeetUltraDownload.MODEL_ID && !CohereArabicModel.isArabic(it)
                }
                val spec = localModel.spec
                val download = downloads[spec.id] ?: DownloadState.Idle
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        IconBadge(AppIcons.Smartphone, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                        Column(Modifier.weight(1f)) {
                            Text("Speech model", style = MaterialTheme.typography.titleMedium)
                            Text(
                                when {
                                    download.active -> "Downloading…"
                                    localModel == LocalModelChoice.ARABIC && arabicRecord != null ->
                                        "Installed · ${formatBytes(arabicRecord.sizeBytes)}"
                                    localModel == LocalModelChoice.PARAKEET && downloaded != null ->
                                        "Installed · ${formatBytes(downloaded.sizeBytes)}"
                                    localModel == LocalModelChoice.PARAKEET && imported.isNotEmpty() -> "Installed from a file"
                                    else -> "Not installed"
                                },
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    LocalModelChoice.entries.forEach { choice ->
                        ChoiceRow(
                            label = choice.label, detail = choice.detail, selected = choice == localModel,
                            enabled = !state.working, onClick = { viewModel.setLocalModel(choice) }
                        )
                    }
                    val removable = if (localModel == LocalModelChoice.ARABIC) arabicRecord else downloaded
                    ModelDownloadControls(
                        spec = spec, installed = removable != null, download = download,
                        busy = state.busy,
                        onDownload = { onDownload(spec) },
                        onCancel = viewModel::cancelModelDownload,
                        onRemove = { confirmRemove = spec.id }
                    )
                    if (localModel == LocalModelChoice.PARAKEET) {
                        TextButton(
                            onClick = { modelPicker.launch(arrayOf("application/octet-stream", "*/*")) },
                            enabled = !state.busy
                        ) { Text("Import .tflite file instead") }
                    }
                }
            }

            // ---- Translation
            SectionLabel("Translation")
            SettingsCard {
                ListItem(
                    headlineContent = { Text("Translation") },
                    supportingContent = { Text("Translate transcripts on this phone") },
                    leadingContent = { Icon(AppIcons.Translate, null) },
                    trailingContent = { Switch(checked = translation.enabled, onCheckedChange = null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.toggleable(
                        value = translation.enabled, role = Role.Switch,
                        onValueChange = { viewModel.setTranslationSettings(translation.copy(enabled = it)) }
                    )
                )
                val installedTranslation = TranslationModel.specs.filter { it.id in installed }
                if (translation.enabled) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerLow)
                    TranslationOptions(
                        translation = translation, installed = installed, downloads = downloads,
                        onDownload = onDownload, viewModel = viewModel,
                        onSwitch = { model, replaced -> confirmSwitch = model to replaced },
                        onRemove = { confirmRemove = TRANSLATION_MODELS }
                    )
                } else if (installedTranslation.isNotEmpty()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerLow)
                    ListItem(
                        headlineContent = { Text("Translation model installed") },
                        supportingContent = { Text(formatBytes(installedTranslation.sumOf { it.sizeBytes })) },
                        trailingContent = {
                            TextButton(onClick = { confirmRemove = TRANSLATION_MODELS }) {
                                Text("Remove", color = MaterialTheme.colorScheme.error)
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
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
                // Consent is remembered after the first upload; this is the only way to withdraw it.
                if (cloudConsent) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerLow)
                    ListItem(
                        headlineContent = { Text("Uploads allowed") },
                        supportingContent = { Text("Transcribe uploads to Mistral without asking") },
                        leadingContent = { Icon(AppIcons.Cloud, null) },
                        trailingContent = { TextButton(onClick = viewModel::revokeCloudUploadConsent) { Text("Revoke") } },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                }
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

    confirmRemove?.let { target ->
        val specs = if (target == TRANSLATION_MODELS) TranslationModel.specs.filter { it.id in installed }
        else listOfNotNull(ModelSpecs.byId(target))
        RemoveModelDialog(
            title = if (target == TRANSLATION_MODELS) "Remove the translation model?" else "Remove the ${specs.firstOrNull()?.title ?: "model"}?",
            sizeBytes = specs.sumOf { it.sizeBytes },
            onConfirm = {
                confirmRemove = null
                if (target == TRANSLATION_MODELS) viewModel.removeTranslationModels()
                else specs.firstOrNull()?.let(viewModel::removeSpeechModel)
            },
            onDismiss = { confirmRemove = null }
        )
    }
    confirmSwitch?.let { (model, replaced) ->
        val removed = TranslationModel.specs.filter { it.id in replaced }
        SwitchTranslationModelDialog(
            removed = removed.joinToString { it.title },
            sizeBytes = removed.sumOf { it.sizeBytes },
            onConfirm = { confirmSwitch = null; viewModel.switchTranslationModel(model) },
            onDismiss = { confirmSwitch = null }
        )
    }
}

/** Marker for "all translation models" in the removal confirmation. */
private const val TRANSLATION_MODELS = "translation-models"

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TranslationOptions(
    translation: TranslationSettings,
    installed: Set<String>,
    downloads: Map<String, DownloadState>,
    onDownload: (DownloadSpec) -> Unit,
    viewModel: AppViewModel,
    onSwitch: (TranslationModel, List<String>) -> Unit,
    onRemove: () -> Unit
) {
    val spec = translation.model.spec
    val download = downloads[spec.id] ?: DownloadState.Idle
    val anyActive = TranslationModel.specs.any { (downloads[it.id] ?: DownloadState.Idle).active }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Model", style = MaterialTheme.typography.titleSmall)
        TranslationModel.entries.forEach { model ->
            ChoiceRow(
                label = model.label, detail = null, selected = model == translation.model, enabled = !anyActive,
                onClick = {
                    if (model != translation.model) {
                        val replaced = translationModelsReplacedBy(model, installed)
                        if (replaced.isEmpty()) viewModel.switchTranslationModel(model)
                        else onSwitch(model, replaced.map { it.id })
                    }
                }
            )
        }
        ModelDownloadControls(
            spec = spec, installed = spec.id in installed, download = download, busy = false,
            onDownload = { onDownload(spec) }, onCancel = viewModel::cancelModelDownload, onRemove = onRemove
        )
        Text("Translate into", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TargetLanguage.entries.forEach { target ->
                FilterChip(
                    selected = target == translation.target,
                    onClick = { viewModel.setTranslationSettings(translation.copy(target = target)) },
                    label = { Text(target.label, maxLines = 1) }
                )
            }
        }
        if (translation.target == TargetLanguage.CUSTOM) {
            var draft by rememberSaveable { mutableStateOf(translation.customLanguage) }
            val error = TranslationText.customLanguageError(draft)
            OutlinedTextField(
                value = draft,
                onValueChange = { value ->
                    draft = value.take(TranslationText.MAX_LANGUAGE_CHARS + 10)
                    if (TranslationText.customLanguageError(draft) == null) {
                        viewModel.setTranslationSettings(
                            translation.copy(customLanguage = TranslationText.normalizeLanguage(draft))
                        )
                    }
                },
                label = { Text("Language") },
                placeholder = { Text("e.g. French") },
                singleLine = true,
                isError = error != null,
                supportingText = { error?.let { Text(it) } },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/** A radio option with an optional second line. */
@Composable
private fun ChoiceRow(label: String, detail: String?, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            detail?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Download, progress (with Cancel), retry after a failure, or removal of one model file. */
@Composable
private fun ModelDownloadControls(
    spec: DownloadSpec,
    installed: Boolean,
    download: DownloadState,
    busy: Boolean,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onRemove: () -> Unit
) {
    when {
        download.active -> DownloadProgress(download, onCancel)
        installed -> OutlinedButton(
            onClick = onRemove, enabled = !busy,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
        ) {
            Icon(Icons.Default.Delete, null, Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text("Remove download")
        }
        else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            (download as? DownloadState.Failed)?.let {
                Text(it.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            Button(onClick = onDownload) {
                Icon(AppIcons.Download, null, Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(if (download is DownloadState.Failed) "Retry download" else "Download (${formatBytes(spec.sizeBytes)})")
            }
        }
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
