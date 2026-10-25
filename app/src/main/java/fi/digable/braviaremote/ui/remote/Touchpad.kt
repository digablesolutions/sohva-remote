package fi.digable.braviaremote.ui.remote

import android.os.SystemClock
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import fi.digable.braviaremote.R
import fi.digable.braviaremote.protocol.RemoteKey
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.sign

/**
 * The D-pad as a surface for the thumb, for use without looking at the phone: a swipe sends one
 * arrow per step of travel, a tap sends OK, and resting the finger after a swipe keeps moving that
 * way until it lifts. Every key goes through the same queue as the buttons (fast keys when open).
 */
@Composable
fun Touchpad(haptics: Boolean, press: (RemoteKey) -> Unit, pressRepeat: (RemoteKey) -> Unit, modifier: Modifier = Modifier) {
    val view = LocalView.current
    val density = LocalDensity.current
    val currentPress by rememberUpdatedState(press)
    val currentRepeat by rememberUpdatedState(pressRepeat)
    val currentHaptics by rememberUpdatedState(haptics)
    var touch by remember { mutableStateOf<Offset?>(null) }
    val glow = MaterialTheme.colorScheme.primary
    val labels = listOf(
        stringResource(R.string.remote_up) to RemoteKey.UP,
        stringResource(R.string.remote_down) to RemoteKey.DOWN,
        stringResource(R.string.remote_left) to RemoteKey.LEFT,
        stringResource(R.string.remote_right) to RemoteKey.RIGHT,
    )
    val description = stringResource(R.string.touchpad_description)
    val okLabel = stringResource(R.string.remote_ok)

    fun send(key: RemoteKey, repeat: Boolean) {
        if (currentHaptics) {
            view.performHapticFeedback(if (key == RemoteKey.CONFIRM) HapticFeedbackConstants.KEYBOARD_TAP else HapticFeedbackConstants.CLOCK_TICK)
        }
        if (repeat) currentRepeat(key) else currentPress(key)
    }

    Box(
        modifier
            .fillMaxWidth()
            .height(252.dp)
            .clip(RoundedCornerShape(36.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(36.dp))
            .drawBehind {
                touch?.let { drawCircle(glow.copy(alpha = 0.18f), radius = 56.dp.toPx(), center = it) }
            }
            .semantics {
                contentDescription = description
                onClick(label = okLabel) { send(RemoteKey.CONFIRM, repeat = false); true }
                customActions = labels.map { (label, key) -> CustomAccessibilityAction(label) { send(key, repeat = false); true } }
            }
            .pointerInput(Unit) {
                val stepper = SwipeStepper(firstStep = with(density) { FIRST_STEP.toPx() }, step = with(density) { STEP.toPx() })
                val slop = viewConfiguration.touchSlop
                val restPx = with(density) { REST.toPx() }
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    touch = down.position
                    stepper.reset()
                    var travelled = 0f
                    var lastKey: RemoteKey? = null
                    var otherFinger = false
                    // Where the finger last really moved; drifting less than REST from it is resting.
                    var anchor = down.position
                    // When the finger has rested this long after a swipe, repeat that direction.
                    var repeatAt = Long.MAX_VALUE
                    try {
                        while (true) {
                            val wait = if (lastKey == null) Long.MAX_VALUE else (repeatAt - SystemClock.uptimeMillis()).coerceAtLeast(0)
                            val event = withTimeoutOrNull(wait) { awaitPointerEvent() }
                            if (event == null) {
                                lastKey?.let { send(it, repeat = true) }
                                repeatAt = SystemClock.uptimeMillis() + REPEAT_MS
                                continue
                            }
                            if (event.changes.any { it.id != down.id && it.pressed }) otherFinger = true
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            // Consumed elsewhere: the gesture was cancelled, so no tap either.
                            if (change.isConsumed) break
                            val delta = change.positionChange()
                            change.consume()
                            touch = change.position
                            travelled += delta.getDistance()
                            // The last move can come with the release (a quick flick): count it first.
                            val keys = stepper.feed(delta.x, delta.y)
                            for (key in keys) {
                                send(key, repeat = false)
                                lastKey = key
                            }
                            // Jitter is rest, a slow drag is not: measure from the anchor, not per event.
                            if (keys.isNotEmpty() || (change.position - anchor).getDistance() >= restPx) {
                                anchor = change.position
                                repeatAt = SystemClock.uptimeMillis() + HOLD_MS
                            }
                            if (!change.pressed) {
                                if (lastKey == null && !otherFinger && travelled < slop) send(RemoteKey.CONFIRM, repeat = false)
                                break
                            }
                        }
                    } finally {
                        touch = null
                    }
                }
            },
    ) {
        Text(
            stringResource(R.string.touchpad_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.Center).padding(24.dp),
        )
    }
}

private val FIRST_STEP = 28.dp
private val STEP = 52.dp
private const val HOLD_MS = 400L
private const val REPEAT_MS = 140L
/** A finger that stays within this of where it last moved is resting, so jitter still repeats. */
private val REST = 4.dp

/**
 * Turns finger travel into arrow keys: the first key after [firstStep] of travel, then one per
 * [step]. The larger axis wins; travel on the other axis is halved with each key, so a slightly
 * slanted swipe stays one direction while an L-shaped one still turns the corner.
 */
internal class SwipeStepper(private val firstStep: Float, private val step: Float) {
    private var x = 0f
    private var y = 0f
    private var sent = 0

    fun reset() {
        x = 0f
        y = 0f
        sent = 0
    }

    /** Splits a long move into short pieces, so the keys do not depend on how events were batched. */
    fun feed(dx: Float, dy: Float): List<RemoteKey> {
        val pieces = ceil(hypot(dx, dy) / (firstStep / 4)).toInt().coerceAtLeast(1)
        return (1..pieces).flatMap { step(dx / pieces, dy / pieces) }
    }

    private fun step(dx: Float, dy: Float): List<RemoteKey> {
        x += dx
        y += dy
        val keys = mutableListOf<RemoteKey>()
        while (true) {
            val threshold = if (sent == 0) firstStep else step
            when {
                abs(x) >= threshold && abs(x) >= abs(y) -> {
                    keys += if (x > 0) RemoteKey.RIGHT else RemoteKey.LEFT
                    x -= sign(x) * threshold
                    y /= 2
                }
                abs(y) >= threshold -> {
                    keys += if (y > 0) RemoteKey.DOWN else RemoteKey.UP
                    y -= sign(y) * threshold
                    x /= 2
                }
                else -> return keys
            }
            sent++
        }
    }
}
