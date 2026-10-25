package fi.digable.braviaremote.ui.remote

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.indication
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * A round remote key. Fires [onPress] on touch-down (not on release) so the TV reacts as early
 * as possible, vibrates if [haptics], and when [repeat] is set keeps firing while held (F20).
 * Exposed to TalkBack as a button with [label] (N7).
 */
@Composable
fun RemoteButton(
    label: String,
    onPress: () -> Unit,
    modifier: Modifier = Modifier,
    onRepeat: () -> Unit = onPress,
    icon: ImageVector? = null,
    text: String? = null,
    size: Dp = 64.dp,
    repeat: Boolean = false,
    haptics: Boolean = true,
    enabled: Boolean = true,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val interaction = remember { MutableInteractionSource() }
    var repeatJob by remember { mutableStateOf<Job?>(null) }

    fun fire(action: () -> Unit = onPress) {
        if (haptics) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        action()
    }

    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(if (enabled) containerColor else containerColor.copy(alpha = 0.4f))
            .indication(interaction, androidx.compose.material3.ripple())
            .semantics(mergeDescendants = true) {
                contentDescription = label
                role = Role.Button
                onClick { fire(); true }
            }
            .pointerInput(enabled, repeat, haptics) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = { offset ->
                        val press = PressInteraction.Press(offset)
                        interaction.tryEmit(press)
                        fire()
                        if (repeat) {
                            repeatJob?.cancel()
                            repeatJob = scope.launch {
                                delay(INITIAL_DELAY_MS)
                                while (isActive) {
                                    fire(onRepeat)
                                    delay(REPEAT_INTERVAL_MS)
                                }
                            }
                        }
                        val released = tryAwaitRelease()
                        repeatJob?.cancel()
                        repeatJob = null
                        interaction.tryEmit(if (released) PressInteraction.Release(press) else PressInteraction.Cancel(press))
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        when {
            icon != null -> Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(size * 0.45f))
            text != null -> Text(text, color = contentColor, style = MaterialTheme.typography.titleMedium)
        }
    }
}

private const val INITIAL_DELAY_MS = 400L
private const val REPEAT_INTERVAL_MS = 150L
