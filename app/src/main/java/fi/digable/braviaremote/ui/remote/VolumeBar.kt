package fi.digable.braviaremote.ui.remote

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import fi.digable.braviaremote.R
import fi.digable.braviaremote.protocol.VolumeInfo
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/** A volume level picked on the bar, shown instead of the TV's own until the TV gets there. */
@Stable
class VolumeDrag {
    var level by mutableStateOf<Int?>(null)
    var dragging by mutableStateOf(false)
}

/** Lets go of the picked level once the TV reports it, or after a while if it never does. */
@Composable
fun rememberVolumeDrag(reported: Int?): VolumeDrag {
    val drag = remember { VolumeDrag() }
    LaunchedEffect(drag.level, drag.dragging, reported) {
        val level = drag.level ?: return@LaunchedEffect
        if (drag.dragging) return@LaunchedEffect
        // A sound system on HDMI can land one step off the level asked for.
        if (reported != null && abs(reported - level) > 1) delay(HOLD_MS)
        drag.level = null
    }
    return drag
}

/**
 * The volume bar on the TV card. Drag it sideways to change the volume: the level moves with the
 * finger from where it was, so a stray touch never jumps it, and the TV follows while dragging.
 */
@Composable
fun VolumeBar(volume: VolumeInfo, drag: VolumeDrag, haptics: Boolean, onVolume: (Int) -> Unit) {
    val view = LocalView.current
    val current by rememberUpdatedState(volume)
    val currentOnVolume by rememberUpdatedState(onVolume)
    val currentHaptics by rememberUpdatedState(haptics)
    val shown = drag.level ?: volume.volume
    val range = (volume.maxVolume - volume.minVolume).coerceAtLeast(1)
    val thickness by animateDpAsState(if (drag.dragging) 8.dp else 4.dp, label = "volume bar")
    val description = stringResource(R.string.remote_volume_level, shown)
    // The speaker marks this bar as the volume, not the programme's progress just above it.
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(
            if (volume.mute) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .weight(1f)
                // Taller than the bar: a thumb needs room to grab it.
                .height(28.dp)
                .semantics {
                    contentDescription = description
                    progressBarRangeInfo = ProgressBarRangeInfo(shown.toFloat(), volume.minVolume.toFloat()..volume.maxVolume.toFloat())
                    setProgress { target ->
                        currentOnVolume(target.roundToInt().coerceIn(current.minVolume, current.maxVolume))
                        true
                    }
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        // Up and down belong to whatever scrolls the screen; only sideways is volume.
                        val first = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                            ?: return@awaitEachGesture
                        val start = drag.level ?: current.volume
                        var dx = 0f
                        var last = start
                        drag.dragging = true
                        try {
                            horizontalDrag(first.id) { change ->
                                dx += change.positionChange().x
                                change.consume()
                                val level = draggedVolume(start, dx, size.width.toFloat(), current.minVolume, current.maxVolume)
                                if (level != last) {
                                    last = level
                                    drag.level = level
                                    if (currentHaptics) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                    currentOnVolume(level)
                                }
                            }
                        } finally {
                            drag.dragging = false
                        }
                    }
                },
        ) {
            LinearProgressIndicator(
                progress = { ((shown - volume.minVolume).toFloat() / range).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(thickness).clip(RoundedCornerShape(thickness / 2)),
                color = if (volume.mute) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                drawStopIndicator = {},
            )
        }
    }
}

/** The level after dragging [dx] pixels along a bar [width] pixels wide, starting from [start]. */
internal fun draggedVolume(start: Int, dx: Float, width: Float, min: Int, max: Int): Int {
    if (width <= 0f) return start
    val range = (max - min).coerceAtLeast(1)
    return (start + (dx / width * range).roundToInt()).coerceIn(min, max)
}

/** How long a picked level stays on the bar while the TV has not reported it yet. */
private const val HOLD_MS = 4_000L
