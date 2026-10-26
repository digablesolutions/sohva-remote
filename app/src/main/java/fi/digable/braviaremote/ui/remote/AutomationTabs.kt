package fi.digable.braviaremote.ui.remote

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fi.digable.braviaremote.BuildConfig
import fi.digable.braviaremote.R
import fi.digable.braviaremote.data.Scene
import fi.digable.braviaremote.data.TvTarget
import fi.digable.braviaremote.data.WakeUp
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Calendar
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

/** The phone's timers: the sleep timer and the wake-up. */
@Composable
fun TimersTab(viewModel: RemoteViewModel) {
    Column(Modifier.verticalScroll(rememberScrollState()).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ExactAlarmNotice()
        SleepTimerSection(viewModel)
        WakeUpSection(viewModel)
    }
}

/**
 * Timers only run on time, and can only reach the TV with the app closed, with exact alarms. Play
 * lets a remote ask for them only in settings, so say so and link there until they are allowed.
 */
@Composable
private fun ExactAlarmNotice() {
    val context = LocalContext.current
    val alarms = remember { context.getSystemService(AlarmManager::class.java) }
    var allowed by remember { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()
        onPauseOrDispose { }
    }
    if (allowed) return
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.exact_alarm_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(stringResource(R.string.exact_alarm_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
            FilledTonalButton(onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
                }
            }) { Text(stringResource(R.string.exact_alarm_allow)) }
        }
    }
}

/** Switch the TV on at a time on chosen days, to a channel and volume. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WakeUpSection(viewModel: RemoteViewModel) {
    val loaded by viewModel.wakeUp.collectAsStateWithLifecycle()
    val wakeUp = loaded ?: return
    val targets by viewModel.targets.collectAsStateWithLifecycle()
    var pickTime by remember { mutableStateOf(false) }
    val context = LocalContext.current
    fun update(change: WakeUp.() -> WakeUp) = viewModel.setWakeUp(wakeUp.change())

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.wake_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                val next = wakeUp.nextAfter(ZonedDateTime.now())
                Text(
                    if (next == null) stringResource(R.string.wake_hint) else stringResource(
                        R.string.wake_next,
                        next.dayOfWeek.getDisplayName(TextStyle.FULL_STANDALONE, appLocale()),
                        clock(context, next.hour * 60 + next.minute),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = wakeUp.enabled, onCheckedChange = { on -> update { copy(enabled = on) } })
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pickTime = true }) {
                Text(clock(context, wakeUp.hour * 60 + wakeUp.minute), style = MaterialTheme.typography.titleMedium)
            }
            if (BuildConfig.DEBUG) TextButton(onClick = viewModel::tryWakeUp) { Text("Try in 1 min") }
        }
        DayChips(wakeUp.days) { days -> update { copy(days = days) } }
        TargetPicker(targets, wakeUp.target, stringResource(R.string.target_keep)) { target -> update { copy(target = target) } }
        VolumeSetting(wakeUp.volume) { volume -> update { copy(volume = volume) } }
    }

    if (pickTime) {
        TimeDialog(wakeUp.hour, wakeUp.minute, onDismiss = { pickTime = false }) { hour, minute ->
            update { copy(hour = hour, minute = minute, enabled = true) }
        }
    }
}

/**
 * The days of the week as seven round toggles, Monday first, sharing the width so all seven fit even
 * in a dialog; [onChange] gets the new set of ISO day numbers.
 */
@Composable
private fun DayChips(days: Set<Int>, onChange: (Set<Int>) -> Unit) {
    val locale = appLocale()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (day in WakeUp.WEEK) {
            val n = day.value
            val on = n in days
            val name = day.getDisplayName(TextStyle.FULL_STANDALONE, locale)
            // The whole cell, at least 48 dp tall, takes the tap; the circle inside stays small.
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .toggleable(value = on, role = Role.Checkbox) { onChange(if (on) days - n else days + n) }
                    .semantics { contentDescription = name },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .widthIn(max = 40.dp)
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(CircleShape)
                        .background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent)
                        .border(1.dp, if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        day.getDisplayName(TextStyle.SHORT_STANDALONE, locale).take(2),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(hour: Int, minute: Int, onDismiss: () -> Unit, onPick: (Int, Int) -> Unit) {
    val state = rememberTimePickerState(initialHour = hour, initialMinute = minute)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                onPick(state.hour, state.minute)
                onDismiss()
            }) { Text(stringResource(R.string.dialog_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
        text = { TimePicker(state) },
    )
}

/**
 * The app's language, which can differ from the phone's (per-app language in Android settings), so
 * day names follow the strings around them.
 */
@Composable
private fun appLocale(): Locale = LocalConfiguration.current.locales[0]

/** A time of day in the phone's own format (12/24 hours, separator), as the card shows programme times. */
private fun clock(context: Context, minutes: Int): String {
    val at = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, minutes / 60)
        set(Calendar.MINUTE, minutes % 60)
    }
    return DateFormat.getTimeFormat(context).format(at.time)
}

/** One-tap setups: listed with Run and Delete, plus a dialog to add one. */
@Composable
fun ScenesTab(viewModel: RemoteViewModel, onDone: () -> Unit) {
    val scenes by viewModel.scenes.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Scene?>(null) }
    var deleting by remember { mutableStateOf<Scene?>(null) }
    LazyColumn {
        if (scenes.any { it.scheduled }) item { ExactAlarmNotice() }
        item {
            Text(
                stringResource(R.string.scenes_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        items(scenes, key = { it.id }) { scene ->
            ListItem(
                modifier = Modifier.clickable { editing = scene },
                headlineContent = { Text(scene.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text(sceneSummary(scene), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                leadingContent = { Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { viewModel.runScene(scene); onDone() }) { Text(stringResource(R.string.scene_run)) }
                        IconButton(onClick = { deleting = scene }) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.scene_delete, scene.name))
                        }
                    }
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
        item {
            FilledTonalButton(onClick = { editing = Scene(UUID.randomUUID().toString(), "") }, modifier = Modifier.padding(top = 8.dp)) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.padding(start = 8.dp))
                Text(stringResource(R.string.scene_add))
            }
        }
    }
    deleting?.let { scene ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.scene_delete_title, scene.name)) },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteScene(scene); deleting = null }) { Text(stringResource(R.string.scene_delete_confirm)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.dialog_cancel)) } },
        )
    }
    editing?.let { scene -> SceneDialog(viewModel, scene, isNew = scenes.none { it.id == scene.id }, onDismiss = { editing = null }) }
}

@Composable
private fun sceneSummary(scene: Scene): String {
    val parts = listOfNotNull(
        scene.target?.name,
        scene.volume?.let { stringResource(R.string.tile_volume, it) },
    )
    val what = parts.ifEmpty { listOf(stringResource(R.string.scene_power_only)) }.joinToString(" · ")
    if (!scene.scheduled) return what
    val context = LocalContext.current
    return what + "\n" + daysLabel(scene.days) + " " + scene.times.sorted().joinToString(", ") { clock(context, it) }
}

/** "Mon–Fri", "Every day", or the chosen days' short names. */
@Composable
private fun daysLabel(days: Set<Int>): String {
    val locale = appLocale()
    fun short(n: Int) = WakeUp.WEEK[n - 1].getDisplayName(TextStyle.SHORT_STANDALONE, locale)
    return when (days) {
        (1..7).toSet() -> stringResource(R.string.days_every)
        (1..5).toSet() -> "${short(1)}–${short(5)}"
        else -> days.sorted().joinToString(" ") { short(it) }
    }
}

@Composable
private fun SceneDialog(viewModel: RemoteViewModel, initial: Scene, isNew: Boolean, onDismiss: () -> Unit) {
    val targets by viewModel.targets.collectAsStateWithLifecycle()
    var scene by remember { mutableStateOf(initial) }
    var pickTime by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (isNew) R.string.scene_add else R.string.scene_edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = scene.name,
                    onValueChange = { scene = scene.copy(name = it) },
                    label = { Text(stringResource(R.string.scene_name)) },
                    placeholder = { Text(stringResource(R.string.scene_name_example)) },
                    singleLine = true,
                )
                TargetPicker(targets, scene.target, stringResource(R.string.target_none)) { scene = scene.copy(target = it) }
                VolumeSetting(scene.volume) { scene = scene.copy(volume = it) }
                ScheduleSetting(
                    scene = scene,
                    onDays = { scene = scene.copy(days = it) },
                    onRemoveTime = { time -> scene = scene.copy(times = scene.times - time) },
                    onAddTime = { pickTime = true },
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = scene.name.isNotBlank(),
                onClick = { viewModel.saveScene(scene.copy(name = scene.name.trim())); onDismiss() },
            ) { Text(stringResource(R.string.scene_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
    )
    if (pickTime) {
        TimeDialog(19, 0, onDismiss = { pickTime = false }) { hour, minute ->
            val time = hour * 60 + minute
            if (time !in scene.times) {
                scene = scene.copy(times = (scene.times + time).sorted(), days = scene.days.ifEmpty { (1..5).toSet() })
            }
        }
    }
}

/** When the scene runs by itself: days and times. A schedule never switches the TV on. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScheduleSetting(scene: Scene, onDays: (Set<Int>) -> Unit, onRemoveTime: (Int) -> Unit, onAddTime: () -> Unit) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.scene_schedule), style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(R.string.scene_schedule_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (time in scene.times) {
                InputChip(
                    selected = false,
                    onClick = { onRemoveTime(time) },
                    label = { Text(clock(context, time)) },
                    trailingIcon = {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.scene_remove_time, clock(context, time)),
                            modifier = Modifier.size(18.dp),
                        )
                    },
                )
            }
            AssistChip(
                onClick = onAddTime,
                label = { Text(stringResource(R.string.scene_add_time)) },
                leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
        }
        if (scene.times.isNotEmpty()) DayChips(scene.days, onDays)
    }
}

/** A dropdown of the TV's channels, inputs and apps, with [noneLabel] for "nothing". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TargetPicker(targets: List<TvTarget>, selected: TvTarget?, noneLabel: String, onSelect: (TvTarget?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
        OutlinedTextField(
            value = selected?.name ?: noneLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.target_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(noneLabel) }, onClick = { onSelect(null); open = false })
            for (target in targets) {
                DropdownMenuItem(
                    text = { Text(target.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = { onSelect(target); open = false },
                )
            }
        }
    }
}

/** "Set the volume" with a switch and, when on, a slider; null keeps whatever volume the TV has. */
@Composable
private fun VolumeSetting(volume: Int?, onChange: (Int?) -> Unit) {
    var slider by remember(volume) { mutableFloatStateOf((volume ?: DEFAULT_VOLUME).toFloat()) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.volume_set))
                Text(
                    if (volume == null) stringResource(R.string.volume_keep_hint) else stringResource(R.string.tile_volume, slider.roundToInt()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = volume != null, onCheckedChange = { on -> onChange(if (on) slider.roundToInt() else null) })
        }
        if (volume != null) {
            Slider(
                value = slider,
                onValueChange = { slider = it },
                onValueChangeFinished = { onChange(slider.roundToInt()) },
                valueRange = 0f..100f,
                steps = 49,
            )
        }
    }
}

private const val DEFAULT_VOLUME = 20
