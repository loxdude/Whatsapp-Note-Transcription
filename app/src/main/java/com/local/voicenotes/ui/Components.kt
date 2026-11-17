package com.local.voicenotes.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Material 3 Expressive "connected button group" used as a single-choice switch: segments are
 * separated by a small gap, the outer corners are fully round and the selected segment morphs
 * to a pill. (Segmented buttons are deprecated in M3 Expressive; material3 1.3 has no
 * ButtonGroup yet, so it is drawn here.)
 */
@Composable
fun <T> ConnectedToggleGroup(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = 56.dp,
    content: @Composable RowScope.(option: T, selected: Boolean) -> Unit
) {
    Row(modifier.height(height), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEachIndexed { index, option ->
            val isSelected = option == selected
            val full = height / 2
            val inner by animateDpAsState(if (isSelected) full else 10.dp, spring(dampingRatio = 0.6f), label = "corner")
            val start = if (index == 0) full else inner
            val end = if (index == options.lastIndex) full else inner
            val container by animateColorAsState(
                if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                label = "container"
            )
            val contentColor by animateColorAsState(
                if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                label = "content"
            )
            Surface(
                selected = isSelected,
                onClick = { if (!isSelected) onSelect(option) },
                enabled = enabled,
                shape = RoundedCornerShape(topStart = start, bottomStart = start, topEnd = end, bottomEnd = end),
                color = container,
                contentColor = contentColor,
                modifier = Modifier.weight(1f).fillMaxHeight().semantics { role = Role.RadioButton }
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) { content(option, isSelected) }
            }
        }
    }
}

/**
 * True once [busy] has lasted [delayMillis]; false again as soon as it ends. Most runs take a
 * few hundred milliseconds, and showing progress UI for those only produces a flash.
 */
@Composable
fun rememberSettledBusy(busy: Boolean, delayMillis: Long = 600): Boolean {
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(busy, delayMillis) {
        if (busy) { delay(delayMillis); settled = true } else settled = false
    }
    return busy && settled
}

/**
 * Settled busy for the app state: preparing a model after an engine switch (about a second) is
 * not work the user started and stays invisible unless it really takes long.
 */
@Composable
fun rememberSettledWork(state: com.local.voicenotes.AppUiState): Boolean =
    rememberSettledBusy(
        state.busy,
        if (state.progress is com.local.voicenotes.domain.TranscriptionProgress.LoadingModel) 2_500 else 600
    )

/** Icon on a tinted circle, used as the leading visual of cards. */
@Composable
fun IconBadge(
    icon: ImageVector,
    container: Color = MaterialTheme.colorScheme.secondaryContainer,
    content: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    size: Dp = 48.dp
) {
    Surface(shape = CircleShape, color = container, contentColor = content, modifier = Modifier.size(size)) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null, modifier = Modifier.size(size * 0.5f)) }
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text, modifier = modifier.padding(start = 4.dp),
        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary
    )
}

/** Decorative voice waveform; it ripples while [animated]. */
@Composable
fun Waveform(animated: Boolean, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    val phase = if (animated) {
        val transition = rememberInfiniteTransition(label = "wave")
        transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "phase").value
    } else 0f
    Canvas(modifier) {
        val bars = 28
        val gap = size.width / bars
        val barWidth = gap * 0.45f
        for (i in 0 until bars) {
            val x = i * gap + (gap - barWidth) / 2
            // A fixed "speech-like" envelope, modulated by a travelling wave while busy.
            val envelope = 0.25f + 0.75f * abs(sin(i * 0.55f) * sin(i * 0.21f + 0.6f)).toFloat()
            val wave = if (animated) 0.55f + 0.45f * sin(2 * PI * (phase - i / bars.toFloat())).toFloat() else 1f
            val h = (size.height * envelope * wave).coerceAtLeast(barWidth)
            drawRoundRect(
                color = color.copy(alpha = if (animated) 0.9f else 0.55f),
                topLeft = Offset(x, (size.height - h) / 2),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(barWidth / 2, barWidth / 2)
            )
        }
    }
}
