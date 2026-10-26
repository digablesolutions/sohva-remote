package fi.digable.braviaremote.ui.remote

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.SettingsInputComposite
import androidx.compose.material.icons.filled.SettingsInputHdmi
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.SubcomposeAsyncImage
import fi.digable.braviaremote.BuildConfig
import fi.digable.braviaremote.R
import fi.digable.braviaremote.data.AppLanguage
import fi.digable.braviaremote.data.ConnectionState
import fi.digable.braviaremote.data.SavedTv
import fi.digable.braviaremote.protocol.ExternalInput
import fi.digable.braviaremote.protocol.RemoteKey
import java.util.Date

private enum class MoreTab(val labelRes: Int, val icon: ImageVector) {
    WATCH(R.string.more_watch, Icons.Default.Apps),
    CHANNELS(R.string.more_channels, Icons.Default.LiveTv),
    KEYS(R.string.more_keys, Icons.Default.Dialpad),
    TIMERS(R.string.more_timers, Icons.Default.Schedule),
    SCENES(R.string.more_scenes, Icons.Default.AutoAwesome),
    SETTINGS(R.string.more_settings, Icons.Default.Settings),
}

/** Everything that is not on the main remote: inputs and apps, channels, extra keys, timers, scenes, settings. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreSheet(viewModel: RemoteViewModel, onDismiss: () -> Unit, onAddTv: () -> Unit, onFastKeys: (SavedTv) -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        // The full height of the sheet, so long tabs scroll inside it instead of leaving the remote half-covered.
        MoreContent(viewModel, onDone = onDismiss, onAddTv = onAddTv, onFastKeys = onFastKeys, modifier = Modifier.fillMaxHeight())
    }
}

/**
 * The More tabs: in the bottom sheet on a phone, always open beside the remote on a wide screen.
 * [onDone] closes the sheet after picking a source, channel or scene; inline it does nothing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreContent(
    viewModel: RemoteViewModel,
    onDone: () -> Unit,
    onAddTv: () -> Unit,
    onFastKeys: (SavedTv) -> Unit,
    modifier: Modifier = Modifier,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val haptics by viewModel.hapticsEnabled.collectAsStateWithLifecycle()
    Column(modifier) {
        // Six fixed tabs, an icon over a short label, so every tab is in sight: a scrolling row hid the
        // last two off the edge. The content-slot Tab skips the default 16 dp side padding the labels need.
        PrimaryTabRow(selectedTabIndex = tab, containerColor = Color.Transparent) {
            MoreTab.entries.forEachIndexed { i, t ->
                Tab(
                    selected = tab == i,
                    onClick = { tab = i },
                    selectedContentColor = MaterialTheme.colorScheme.primary,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ) {
                    Column(Modifier.padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(t.icon, contentDescription = null, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(t.labelRes),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp).padding(top = 12.dp)) {
            when (MoreTab.entries[tab]) {
                MoreTab.WATCH -> WatchTab(viewModel, onDone)
                MoreTab.CHANNELS -> ChannelsTab(viewModel, onDone)
                MoreTab.KEYS -> KeysTab(viewModel, haptics)
                MoreTab.TIMERS -> TimersTab(viewModel)
                MoreTab.SCENES -> ScenesTab(viewModel, onDone)
                MoreTab.SETTINGS -> SettingsTab(viewModel, haptics, onAddTv, onFastKeys)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WatchTab(viewModel: RemoteViewModel, onDone: () -> Unit) {
    val inputs by viewModel.inputs.collectAsStateWithLifecycle()
    val sources by viewModel.tvSources.collectAsStateWithLifecycle()
    val apps by viewModel.apps.collectAsStateWithLifecycle()
    val favorites by viewModel.favoriteApps.collectAsStateWithLifecycle()
    val haptic = LocalHapticFeedback.current
    val playing by viewModel.playing.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        if (inputs.isEmpty()) viewModel.loadInputs()
        // Always: a list shown from the phone's cache is refreshed in the background.
        viewModel.loadApps()
    }

    if (inputs.isEmpty() && sources.isEmpty() && apps.isEmpty()) {
        EmptyOrLoading(loading = state is ConnectionState.Connecting) {
            viewModel.loadInputs()
            viewModel.loadApps()
        }
        return
    }

    // A CEC device can be listed more than once (as an audio system and as a player); show its name once.
    val rows = inputs.distinctBy { it.displayName }.sortedByDescending { it.connection }
    val playingUri = playing?.uri
    LazyVerticalGrid(
        columns = GridCells.Adaptive(88.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) { SectionHeader(stringResource(R.string.more_inputs)) }
        sources.firstOrNull()?.let { tuner ->
            item(span = { GridItemSpan(maxLineSpan) }) {
                InputRow(
                    name = stringResource(R.string.more_tv_input),
                    icon = Icons.Default.Tv,
                    connected = true,
                    current = playingUri?.startsWith(tuner) == true,
                    onClick = { viewModel.selectInput(tuner); onDone() },
                )
            }
        }
        items(rows, key = { it.uri }, span = { GridItemSpan(maxLineSpan) }) { input ->
            InputRow(
                name = input.displayName,
                icon = inputIcon(input),
                connected = input.connection,
                current = input.uri == playingUri,
                onClick = { viewModel.selectInput(input.uri); onDone() },
            )
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                SectionHeader(stringResource(R.string.more_apps))
                Text(
                    stringResource(R.string.favorite_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(apps, key = { it.uri }) { app ->
            val pinned = favorites.any { it.uri == app.uri }
            val pinLabel = stringResource(if (pinned) R.string.favorite_remove else R.string.favorite_add)
            Column(
                Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .combinedClickable(
                        onClick = { viewModel.launchApp(app.uri); onDone() },
                        onLongClickLabel = pinLabel,
                        onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.toggleFavoriteApp(app)
                        },
                    )
                    .padding(vertical = 8.dp, horizontal = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box {
                    AppIcon(app.icon)
                    if (pinned) {
                        Icon(
                            Icons.Default.Star,
                            contentDescription = stringResource(R.string.favorite_pinned),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.align(Alignment.TopEnd).size(18.dp)
                                .background(MaterialTheme.colorScheme.surface, CircleShape),
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    app.title,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            TextButton(onClick = { viewModel.loadInputs(); viewModel.loadApps() }) { Text(stringResource(R.string.more_refresh)) }
        }
    }
}

/** The TV's icon for an app; a generic tile while it loads, or when the TV has none (some 404). */
@Composable
private fun AppIcon(url: String) {
    val shape = RoundedCornerShape(12.dp)
    val placeholder = @Composable {
        Box(
            Modifier.size(56.dp).clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Default.Apps, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    if (url.isBlank()) {
        placeholder()
        return
    }
    SubcomposeAsyncImage(
        model = url,
        contentDescription = null,
        modifier = Modifier.size(56.dp).clip(shape),
        loading = { placeholder() },
        error = { placeholder() },
    )
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

/** The tuner's channels with their numbers; the one on now is marked and scrolled into view. */
@Composable
private fun ChannelsTab(viewModel: RemoteViewModel, onDone: () -> Unit) {
    val channels by viewModel.channels.collectAsStateWithLifecycle()
    val playing by viewModel.playing.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    var loaded by remember { mutableStateOf(channels.isNotEmpty()) }
    LaunchedEffect(Unit) {
        if (channels.isEmpty()) viewModel.loadChannels().join()
        loaded = true
    }

    if (channels.isEmpty()) {
        if (loaded && state !is ConnectionState.Connecting) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.more_channels_empty))
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { viewModel.loadChannels() }) { Text(stringResource(R.string.more_refresh)) }
            }
        } else {
            EmptyOrLoading(loading = true) {}
        }
        return
    }

    val currentIndex = channels.indexOfFirst { it.uri == playing?.uri }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (currentIndex - 2).coerceAtLeast(0))
    LazyColumn(state = listState) {
        items(channels, key = { it.uri }) { channel ->
            val current = channel.uri == playing?.uri
            ListItem(
                headlineContent = { Text(channel.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingContent = {
                    Text(
                        channel.number,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(44.dp),
                    )
                },
                trailingContent = if (current) {
                    { Icon(Icons.Default.Check, contentDescription = stringResource(R.string.more_now_on), tint = MaterialTheme.colorScheme.primary) }
                } else null,
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable { viewModel.selectInput(channel.uri); onDone() },
            )
        }
    }
}

@Composable
private fun InputRow(name: String, icon: ImageVector, connected: Boolean, current: Boolean, onClick: () -> Unit) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    ListItem(
        headlineContent = { Text(name) },
        supportingContent = if (connected) null else {
            { Text(stringResource(R.string.more_not_connected)) }
        },
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = if (current) {
            { Icon(Icons.Default.Check, contentDescription = stringResource(R.string.more_now_on), tint = MaterialTheme.colorScheme.primary) }
        } else null,
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent,
            headlineColor = if (connected) MaterialTheme.colorScheme.onSurface else dim,
            leadingIconColor = if (connected) MaterialTheme.colorScheme.onSurface else dim,
        ),
        modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick),
    )
}

/** Picks an icon from the TV's `meta:` hint, with a game pad for consoles the TV calls players. */
private fun inputIcon(input: ExternalInput): ImageVector {
    val console = Regex("playstation|xbox|nintendo|switch", RegexOption.IGNORE_CASE)
    return when {
        console.containsMatchIn(input.displayName) -> Icons.Default.SportsEsports
        input.icon == "meta:game" -> Icons.Default.SportsEsports
        input.icon == "meta:audiosystem" || input.icon == "meta:freeuse" -> Icons.Default.Speaker
        input.icon == "meta:playbackdevice" || input.icon == "meta:recordingdevice" -> Icons.Default.PlayCircleOutline
        input.icon == "meta:wifidisplay" -> Icons.Default.Cast
        input.icon == "meta:composite" || input.icon == "meta:component" -> Icons.Default.SettingsInputComposite
        else -> Icons.Default.SettingsInputHdmi
    }
}

/** Number pad with Guide and Info, track and stop keys, colour keys and the rarer remote keys. */
@Composable
private fun KeysTab(viewModel: RemoteViewModel, haptics: Boolean) {
    Column(
        Modifier.verticalScroll(rememberScrollState()).fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        for (row in RemoteKey.digits.take(9).chunked(3)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                for (key in row) PadKey(stringResource(R.string.more_digit, key.digit ?: 0), key.digit.toString(), haptics) { viewModel.press(key) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            PadKey(stringResource(R.string.key_guide), stringResource(R.string.key_guide), haptics) { viewModel.press(RemoteKey.GUIDE) }
            PadKey(stringResource(R.string.more_digit, 0), "0", haptics) { viewModel.press(RemoteKey.NUM0) }
            PadKey(stringResource(R.string.key_info), stringResource(R.string.key_info), haptics) { viewModel.press(RemoteKey.DISPLAY) }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 8.dp)) {
            RemoteButton(stringResource(R.string.remote_previous), icon = Icons.Default.SkipPrevious, size = 56.dp, haptics = haptics, onPress = { viewModel.press(RemoteKey.PREV) })
            RemoteButton(stringResource(R.string.remote_stop), icon = Icons.Default.Stop, size = 56.dp, haptics = haptics, onPress = { viewModel.press(RemoteKey.STOP) })
            RemoteButton(stringResource(R.string.remote_next), icon = Icons.Default.SkipNext, size = 56.dp, haptics = haptics, onPress = { viewModel.press(RemoteKey.NEXT) })
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ColorKey(stringResource(R.string.key_red), Color(0xFFD32F2F), haptics) { viewModel.press(RemoteKey.RED) }
            ColorKey(stringResource(R.string.key_green), Color(0xFF388E3C), haptics) { viewModel.press(RemoteKey.GREEN) }
            ColorKey(stringResource(R.string.key_yellow), Color(0xFFFBC02D), haptics) { viewModel.press(RemoteKey.YELLOW) }
            ColorKey(stringResource(R.string.key_blue), Color(0xFF1976D2), haptics) { viewModel.press(RemoteKey.BLUE) }
        }

        val keys = listOf(
            R.string.key_subtitle to RemoteKey.SUBTITLE,
            R.string.key_audio to RemoteKey.AUDIO,
            R.string.key_input to RemoteKey.INPUT,
            R.string.key_exit to RemoteKey.EXIT,
            R.string.key_teletext to RemoteKey.TELETEXT,
            R.string.key_netflix to RemoteKey.NETFLIX,
            R.string.key_sleep to RemoteKey.SLEEP_TIMER,
            R.string.key_picture_off to RemoteKey.PICTURE_OFF,
        )
        for (pair in keys.chunked(2)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                for ((label, key) in pair) {
                    FilledTonalButton(onClick = { viewModel.press(key) }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(label), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** The phone's own sleep timer: pick a time, see when the TV goes off, cancel. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SleepTimerSection(viewModel: RemoteViewModel) {
    val sleepAt by viewModel.sleepAt.collectAsStateWithLifecycle()
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        SectionHeader(stringResource(R.string.sleep_title))
        val at = sleepAt
        if (at != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Bedtime, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.sleep_running, DateFormat.getTimeFormat(context).format(Date(at))),
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { viewModel.cancelSleepTimer() }) { Text(stringResource(R.string.sleep_cancel)) }
            }
        } else {
            Text(
                stringResource(R.string.sleep_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FlowRow(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (minutes in SLEEP_MINUTES) {
                FilterChip(
                    selected = false,
                    onClick = { viewModel.startSleepTimer(minutes) },
                    label = { Text(stringResource(R.string.sleep_minutes, minutes)) },
                )
            }
        }
    }
}

/**
 * The app's language: the phone's, or English or Finnish whatever the phone uses. The languages are
 * named in themselves, so each is recognisable from the other.
 */
@Composable
private fun LanguageSetting() {
    val context = LocalContext.current
    val current = remember { AppLanguage.current(context) }
    var choosing by remember { mutableStateOf(false) }
    val names = mapOf("en" to stringResource(R.string.language_en), "fi" to stringResource(R.string.language_fi))
    val phone = stringResource(R.string.language_phone)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { choosing = true }.padding(vertical = 8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.language_title))
            Text(
                current?.let { names[it] } ?: phone,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (choosing) {
        AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text(stringResource(R.string.language_title)) },
            text = {
                Column {
                    for (tag in listOf(null) + AppLanguage.TAGS) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(selected = tag == current, role = Role.RadioButton) {
                                    choosing = false
                                    if (tag != current) context.findActivity()?.let { AppLanguage.set(it, tag) }
                                }
                                .padding(vertical = 12.dp),
                        ) {
                            RadioButton(selected = tag == current, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Text(tag?.let { names[it] } ?: phone)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { choosing = false }) { Text(stringResource(R.string.dialog_cancel)) } },
        )
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Mute the TV while a phone call rings or is on; asks for the phone-state permission when turned on. */
@Composable
private fun CallMuteSetting(viewModel: RemoteViewModel) {
    val enabled by viewModel.muteOnCalls.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.setMuteOnCalls(granted)
    }
    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.call_mute_title))
            Text(
                stringResource(R.string.call_mute_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = enabled && granted,
            onCheckedChange = { on ->
                if (on && !granted) permission.launch(Manifest.permission.READ_PHONE_STATE) else viewModel.setMuteOnCalls(on)
            },
        )
    }
}

/** The remote keys as a notification; asks for the notification permission on Android 13+. */
@Composable
private fun NotificationRemoteSetting(viewModel: RemoteViewModel) {
    val enabled by viewModel.notificationRemoteEnabled.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.setNotificationRemote(granted)
    }
    val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.notif_remote_title))
            Text(
                stringResource(R.string.notif_remote_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = enabled && granted,
            onCheckedChange = { on ->
                if (on && !granted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    viewModel.setNotificationRemote(on)
                }
            },
        )
    }
}

/** Debug builds add a one-minute timer for trying the alarm on the phone. */
private val SLEEP_MINUTES = (if (BuildConfig.DEBUG) listOf(1) else emptyList()) + listOf(15, 30, 45, 60, 90, 120)

@Composable
private fun PadKey(label: String, text: String, haptics: Boolean, onPress: () -> Unit) {
    RemoteButton(
        label, text = text, size = 64.dp, haptics = haptics, onPress = onPress,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun ColorKey(label: String, color: Color, haptics: Boolean, onPress: () -> Unit) {
    RemoteButton(label, onPress = onPress, size = 48.dp, haptics = haptics, containerColor = color, contentColor = Color.White)
}

/** The saved TVs (switch, pair fast keys, forget, add), then the app's own settings. */
@Composable
private fun SettingsTab(viewModel: RemoteViewModel, haptics: Boolean, onAddTv: () -> Unit, onFastKeys: (SavedTv) -> Unit) {
    val tvs by viewModel.savedTvs.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val activeId = state.tvOrNull?.id
    var forgetting by remember { mutableStateOf<SavedTv?>(null) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item { SectionHeader(stringResource(R.string.more_tvs)) }
        items(tvs, key = { it.id }) { tv ->
            ListItem(
                headlineContent = { Text(tv.name) },
                supportingContent = {
                    Column {
                        Text(listOf(tv.model, tv.host).filter { it.isNotBlank() }.joinToString(" · "))
                        if (viewModel.hasFastKeys(tv)) {
                            Text(stringResource(R.string.fast_keys_on), color = MaterialTheme.colorScheme.primary)
                        } else {
                            TextButton(onClick = { onFastKeys(tv) }, contentPadding = PaddingValues(0.dp)) {
                                Text(stringResource(R.string.fast_keys_setup))
                            }
                        }
                    }
                },
                leadingContent = {
                    Icon(Icons.Default.Tv, contentDescription = null, tint = if (tv.id == activeId) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                },
                trailingContent = {
                    TextButton(onClick = { forgetting = tv }) { Text(stringResource(R.string.more_forget_tv)) }
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable { viewModel.switchTo(tv) },
            )
        }
        item {
            OutlinedButton(onClick = onAddTv, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.more_add_tv))
            }
        }
        item { SectionHeader(stringResource(R.string.more_this_phone)) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 16.dp)) {
                LanguageSetting()
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.more_haptics), Modifier.weight(1f))
                    Switch(checked = haptics, onCheckedChange = viewModel::setHaptics)
                }
                CallMuteSetting(viewModel)
                NotificationRemoteSetting(viewModel)
            }
        }
    }
    forgetting?.let { tv ->
        AlertDialog(
            onDismissRequest = { forgetting = null },
            title = { Text(stringResource(R.string.forget_title, tv.name)) },
            text = { Text(stringResource(R.string.forget_body)) },
            confirmButton = {
                TextButton(onClick = { viewModel.forget(tv); forgetting = null }) { Text(stringResource(R.string.more_forget_tv)) }
            },
            dismissButton = { TextButton(onClick = { forgetting = null }) { Text(stringResource(R.string.dialog_cancel)) } },
        )
    }
}

@Composable
private fun EmptyOrLoading(loading: Boolean, onRefresh: () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (loading) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.more_loading))
        } else {
            Text(stringResource(R.string.more_empty))
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onRefresh) { Text(stringResource(R.string.more_refresh)) }
        }
    }
}
