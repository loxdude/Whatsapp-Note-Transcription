package com.local.voicenotes.ui

import android.net.Uri
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.local.voicenotes.AppViewModel
import com.local.voicenotes.model.formatBytes
import com.local.voicenotes.share.VoiceNoteFile

/** Newest WhatsApp voice notes first, read from the folder the user granted once. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceNoteSheet(
    viewModel: AppViewModel,
    onPick: (Uri) -> Unit,
    onChangeFolder: () -> Unit,
    onSystemPicker: () -> Unit,
    onDismiss: () -> Unit
) {
    var notes by remember { mutableStateOf<List<VoiceNoteFile>?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        viewModel.recentVoiceNotes()
            .onSuccess { notes = it }
            .onFailure { failed = true }
    }
    val context = LocalContext.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 8.dp)) {
            Text(
                "WhatsApp voice notes", style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 8.dp)
            )
            val list = notes
            when {
                failed || (list != null && list.isEmpty()) -> Text(
                    if (failed) "The folder can no longer be read. Choose it again."
                    else "No voice notes found in this folder.",
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(32.dp)
                )
                list == null -> Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
                    items(list, key = { it.uri.toString() }) { note ->
                        ListItem(
                            headlineContent = {
                                Text(
                                    DateUtils.formatDateTime(
                                        context, note.lastModifiedMillis,
                                        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH
                                    )
                                )
                            },
                            supportingContent = {
                                Text(
                                    "${DateUtils.getRelativeTimeSpanString(note.lastModifiedMillis)} · ${formatBytes(note.sizeBytes)}",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            leadingContent = { IconBadge(AppIcons.Mic, size = 40.dp) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable(onClickLabel = "Transcribe this voice note") { onPick(note.uri) }
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onChangeFolder) { Text("Change folder") }
                TextButton(onClick = onSystemPicker) { Text("Browse all") }
            }
        }
    }
}
