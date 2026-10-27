package fi.digable.braviaremote.ui.remote

import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarDuration
import android.provider.Settings
import android.net.Uri
import android.app.AlarmManager
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.delay
import androidx.compose.runtime.produceState
import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.format.DateFormat
import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.core.content.ContextCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fi.digable.braviaremote.R
import fi.digable.braviaremote.data.ConnectionState
import fi.digable.braviaremote.data.LocalNetwork
import fi.digable.braviaremote.data.SavedTv
import fi.digable.braviaremote.protocol.ExternalInput
import fi.digable.braviaremote.protocol.PlayingContent
import fi.digable.braviaremote.protocol.RemoteKey
import java.util.Date
import kotlin.math.abs
import kotlinx.coroutines.launch

/**
 * The one-screen remote, laid out for one-handed use: what's on and where to switch at the top,
 * the D-pad ring and Back/Home/Guide/Options under the thumb, volume and channel rockers with
 * playback at the bottom. Everything else lives one tap away in [MoreSheet].
 */
@Composable
fun RemoteScreen(
    onAddTv: () -> Unit,
    onPairAgain: (SavedTv) -> Unit,
    onFastKeys: (SavedTv) -> Unit,
    viewModel: RemoteViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val haptics by viewModel.hapticsEnabled.collectAsStateWithLifecycle()
    val touchpad by viewModel.touchpad.collectAsStateWithLifecycle()
    val inputs by viewModel.inputs.collectAsStateWithLifecycle()
    val tvSources by viewModel.tvSources.collectAsStateWithLifecycle()
    val playing by viewModel.playing.collectAsStateWithLifecycle()
    val appTitle by viewModel.currentAppTitle.collectAsStateWithLifecycle()
    val sleepAt by viewModel.sleepAt.collectAsStateWithLifecycle()
    val tvTextField by viewModel.tvTextField.collectAsStateWithLifecycle()
    val previousChannel by viewModel.previousChannel.collectAsStateWithLifecycle()
    val scenes by viewModel.scenes.collectAsStateWithLifecycle()
    val favoriteApps by viewModel.favoriteApps.collectAsStateWithLifecycle()
    val fastKeysRefused by viewModel.fastKeysRefused.collectAsStateWithLifecycle()
    val adBreak by viewModel.adBreakState.collectAsStateWithLifecycle()
    // The ad break counts down in a notification; asked for once, the break works without it.
    // The break starts while the question is still up, so a yes shows its countdown then.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.showAdBreakNotification()
    }
    var showMore by remember { mutableStateOf(false) }
    var showKeyboard by remember { mutableStateOf(false) }
    // Opens when the TV's keyboard comes up, not on every echo, so a dismissed sheet stays closed.
    val textFieldOpen = tvTextField != null
    LaunchedEffect(textFieldOpen) { showKeyboard = textFieldOpen }
    val snackbars = remember { SnackbarHostState() }
    val uiScope = rememberCoroutineScope()
    val adBreakLate = stringResource(R.string.ad_break_late)
    val openSettings = stringResource(R.string.exact_alarm_allow)
    LaunchedEffect(Unit) { viewModel.voiceReplies.collect { snackbars.showSnackbar(it) } }
    val context = LocalContext.current
    val canListen = remember { SpeechRecognizer.isRecognitionAvailable(context) }
    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val guesses = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
        if (result.resultCode == Activity.RESULT_OK && !guesses.isNullOrEmpty()) viewModel.runVoice(guesses)
    }
    val listenPrompt = stringResource(R.string.voice_prompt)
    val onVoice: (() -> Unit)? = if (canListen) {
        {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                .putExtra(RecognizerIntent.EXTRA_PROMPT, listenPrompt)
            try {
                speech.launch(intent)
            } catch (e: ActivityNotFoundException) {
                // No recognizer after all.
            }
        }
    } else null

    LifecycleResumeEffect(Unit) {
        viewModel.onShown()
        onPauseOrDispose { viewModel.onHidden() }
    }

    // What's on, where to switch, and the chips under the card. [inlineMore]: the More tabs are
    // already on screen (two panes), so the card has no "More" button.
    val header: @Composable (inlineMore: Boolean) -> Unit = { inlineMore ->
        NowPlayingCard(
            state = state,
            sources = quickSources(inputs, tvSources, stringResource(R.string.more_tv_input)),
            playing = playing,
            appTitle = appTitle,
            sleepAt = sleepAt,
            inputs = inputs,
            haptics = haptics,
            onPower = viewModel::togglePower,
            onVoice = onVoice,
            onRetry = viewModel::refresh,
            onPairAgain = onPairAgain,
            onSelect = viewModel::selectInput,
            onChannel = viewModel::changeChannel,
            onVolume = viewModel::setVolume,
            onMore = if (inlineMore) null else { { showMore = true } },
        )
        val tv = state.tvOrNull
        if (fastKeysRefused && tv != null) {
            // Keys still work over IRCC; say why the remote got slower and how to fix it.
            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.fast_keys_refused),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                FilledTonalButton(onClick = { onFastKeys(tv) }) { Text(stringResource(R.string.fast_keys_pair_again)) }
            }
        }
        val onLiveTv = playing?.uri?.startsWith("tv:") == true
        QuickChips(
            adBreak = adBreak,
            canStartAdBreak = onLiveTv && state is ConnectionState.Connected,
            onAdBreak = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                viewModel.startAdBreak()
                // Without exact alarms Android may end the break minutes late; say so, with the way to fix it.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    !context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
                ) {
                    uiScope.launch {
                        val result = snackbars.showSnackbar(adBreakLate, actionLabel = openSettings, duration = SnackbarDuration.Long)
                        if (result == SnackbarResult.ActionPerformed) {
                            context.startActivity(
                                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")),
                            )
                        }
                    }
                }
            },
            onAdBreakMore = viewModel::extendAdBreak,
            onAdBreakEnd = viewModel::endAdBreak,
            previous = previousChannel?.takeIf { onLiveTv && state is ConnectionState.Connected },
            favorites = favoriteApps,
            scenes = scenes,
            onJumpBack = viewModel::jumpBack,
            onApp = { viewModel.launchApp(it.uri) },
            onScene = viewModel::runScene,
        )
        if (textFieldOpen && !showKeyboard) {
            FilledTonalButton(onClick = { showKeyboard = true }, modifier = Modifier.padding(top = 12.dp)) {
                Icon(Icons.Default.Keyboard, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.type_title))
            }
        }
    }
    // The pad (ring or touchpad), the system keys and the volume and channel rockers.
    val controls: @Composable () -> Unit = {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (touchpad) {
                Touchpad(haptics, viewModel::press, viewModel::pressRepeat, viewModel::hold)
            } else {
                DPadRing(haptics, viewModel::press, viewModel::pressRepeat)
            }
            // Switches between the ring and the touchpad; remembered.
            IconButton(onClick = { viewModel.setTouchpad(!touchpad) }, modifier = Modifier.align(Alignment.TopEnd)) {
                Icon(
                    if (touchpad) Icons.Default.Gamepad else Icons.Default.TouchApp,
                    contentDescription = stringResource(if (touchpad) R.string.touchpad_off else R.string.touchpad_on),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        // Captioned like the keys on the physical remote: an icon alone did not say "Guide".
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SystemKey(stringResource(R.string.remote_back), stringResource(R.string.remote_back), Icons.AutoMirrored.Filled.Undo, haptics) { viewModel.press(RemoteKey.RETURN) }
            SystemKey(stringResource(R.string.remote_home), stringResource(R.string.remote_home), Icons.Default.Home, haptics) { viewModel.press(RemoteKey.HOME) }
            SystemKey(stringResource(R.string.key_guide), stringResource(R.string.key_guide_short), Icons.AutoMirrored.Filled.ListAlt, haptics) { viewModel.press(RemoteKey.GUIDE) }
            SystemKey(stringResource(R.string.remote_options), stringResource(R.string.remote_options), Icons.Default.Menu, haptics) { viewModel.press(RemoteKey.ACTION_MENU) }
        }
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Rocker(
                label = stringResource(R.string.remote_volume_short),
                upLabel = stringResource(R.string.remote_volume_up), upIcon = Icons.Default.Add,
                downLabel = stringResource(R.string.remote_volume_down), downIcon = Icons.Default.Remove,
                haptics = haptics,
                onUp = { viewModel.press(RemoteKey.VOLUME_UP) },
                onDown = { viewModel.press(RemoteKey.VOLUME_DOWN) },
                onUpRepeat = { viewModel.pressRepeat(RemoteKey.VOLUME_UP) },
                onDownRepeat = { viewModel.pressRepeat(RemoteKey.VOLUME_DOWN) },
            )
            Column(
                Modifier.weight(1f).padding(horizontal = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val muted = (state as? ConnectionState.Connected)?.volume?.mute == true
                TextButton(onClick = viewModel::toggleMute) {
                    Icon(
                        if (muted) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(if (muted) R.string.remote_unmute else R.string.remote_mute))
                }
                PlaybackBar(haptics, viewModel::press)
            }
            Rocker(
                label = stringResource(R.string.remote_channel_short),
                upLabel = stringResource(R.string.remote_channel_up), upIcon = Icons.Default.KeyboardArrowUp,
                downLabel = stringResource(R.string.remote_channel_down), downIcon = Icons.Default.KeyboardArrowDown,
                haptics = haptics,
                onUp = { viewModel.press(RemoteKey.CHANNEL_UP) },
                onDown = { viewModel.press(RemoteKey.CHANNEL_DOWN) },
                onUpRepeat = { viewModel.pressRepeat(RemoteKey.CHANNEL_UP) },
                onDownRepeat = { viewModel.pressRepeat(RemoteKey.CHANNEL_DOWN) },
            )
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbars) }) { padding ->
        BoxWithConstraints(Modifier.padding(padding).fillMaxSize()) {
            if (maxWidth >= TWO_PANE_MIN_WIDTH && maxHeight >= TWO_PANE_MIN_HEIGHT) {
                // A tablet on its side, three columns: the More tabs as a rail on the left, what's on
                // and the chosen tab in the middle, the remote on the right under the thumb. The two
                // panels share the top and bottom edges.
                var moreTab by rememberSaveable { mutableIntStateOf(0) }
                Row(Modifier.fillMaxSize().padding(top = 16.dp, bottom = 16.dp, end = 24.dp)) {
                    MoreTabRail(moreTab, onTab = { moreTab = it }, modifier = Modifier.fillMaxHeight())
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        header(true)
                        Panel(Modifier.weight(1f).padding(top = 10.dp)) {
                            MoreTabBody(moreTab, viewModel, {}, onAddTv, onFastKeys, Modifier.fillMaxSize().fadeBottom())
                        }
                    }
                    Spacer(Modifier.width(24.dp))
                    Panel(Modifier.width(REMOTE_WIDTH).fillMaxHeight()) {
                        Column(
                            Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            controls()
                        }
                    }
                }
            } else if (maxWidth >= TALL_PANE_MIN_WIDTH && maxHeight >= TALL_PANE_MIN_HEIGHT) {
                // A tablet held upright: the More tabs in a panel between the card and the remote,
                // which keeps its phone size at the bottom, under the thumb.
                Column(
                    Modifier
                        .align(Alignment.TopCenter)
                        .widthIn(max = TALL_WIDTH)
                        .fillMaxHeight()
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    header(true)
                    Panel(Modifier.weight(1f).padding(top = 10.dp, bottom = 24.dp)) {
                        MoreContent(viewModel, onDone = {}, onAddTv = onAddTv, onFastKeys = onFastKeys, fadeBottom = true)
                    }
                    Column(Modifier.widthIn(max = REMOTE_WIDTH), horizontalAlignment = Alignment.CenterHorizontally) {
                        controls()
                    }
                }
            } else {
                // A phone: one column, the keys at the bottom under the thumb.
                Column(
                    Modifier
                        .align(Alignment.TopCenter)
                        .widthIn(max = REMOTE_WIDTH)
                        .fillMaxHeight()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    header(false)
                    Spacer(Modifier.weight(1f))
                    controls()
                }
            }
        }
    }

    val field = tvTextField
    if (showKeyboard && field != null) {
        TypeOnTvSheet(field, viewModel, onDismiss = { showKeyboard = false })
    }

    if (showMore) {
        MoreSheet(
            viewModel = viewModel,
            onDismiss = { showMore = false },
            onAddTv = { showMore = false; onAddTv() },
            onFastKeys = { tv -> showMore = false; onFastKeys(tv) },
        )
    }
}

/**
 * The programme on now, when the TV reports it (live TV): its title, start and end, and how far
 * along it is. Ticks every half minute while shown.
 */
@Composable
private fun ProgrammeLine(playing: PlayingContent) {
    val title = playing.programTitle ?: return
    val window = playing.programWindow
    val now by produceState(System.currentTimeMillis(), window) {
        while (true) {
            value = System.currentTimeMillis()
            delay(30_000)
        }
    }
    val context = LocalContext.current
    Column(Modifier.padding(top = 6.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (window != null) {
            val time = android.text.format.DateFormat.getTimeFormat(context)
            val (start, end) = window
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(R.string.programme_time, time.format(java.util.Date(start)), time.format(java.util.Date(end))),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LinearProgressIndicator(
                    progress = { ((now - start).toFloat() / (end - start)).coerceIn(0f, 1f) },
                    modifier = Modifier.width(96.dp).height(3.dp).clip(RoundedCornerShape(2.dp)),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    drawStopIndicator = {},
                )
            }
        }
    }
}

/** A source the user can jump to from the card: the tuner and each connected input, once per name. */
private data class QuickSource(val name: String, val uri: String)

private fun quickSources(inputs: List<ExternalInput>, tvSources: List<String>, tvName: String): List<QuickSource> =
    buildList {
        tvSources.firstOrNull()?.let { add(QuickSource(tvName, it)) }
        // A CEC device can be listed both as itself and as its HDMI port; show its name once.
        inputs.filter { it.connection }.distinctBy { it.displayName }.forEach { add(QuickSource(it.displayName, it.uri)) }
    }

private fun isPlaying(source: QuickSource, playing: PlayingContent?): Boolean {
    val uri = playing?.uri ?: return false
    return uri == source.uri || (source.uri.startsWith("tv:") && uri.startsWith(source.uri))
}

@Composable
private fun NowPlayingCard(
    state: ConnectionState,
    sources: List<QuickSource>,
    playing: PlayingContent?,
    appTitle: String?,
    sleepAt: Long?,
    inputs: List<ExternalInput>,
    haptics: Boolean,
    onPower: () -> Unit,
    onVoice: (() -> Unit)?,
    onRetry: () -> Unit,
    onPairAgain: (SavedTv) -> Unit,
    onSelect: (String) -> Unit,
    onChannel: (next: Boolean) -> Unit,
    onVolume: (Int) -> Unit,
    onMore: (() -> Unit)?,
) {
    val volumeDrag = rememberVolumeDrag((state as? ConnectionState.Connected)?.volume?.volume)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    state.tvOrNull?.name.orEmpty().uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                when (state) {
                    is ConnectionState.Connected -> {
                        val input = inputs.firstOrNull { it.uri == playing?.uri }
                        val headline = input?.displayName
                            ?: playing?.title?.ifBlank { null }
                            // An Android app in front: the TV reports no playing content then.
                            ?: appTitle
                            ?: stringResource(R.string.remote_source_unknown)
                        if (playing?.uri?.startsWith("tv:") == true) {
                            ChannelSwipe(headline, haptics, onChannel)
                        } else {
                            Text(headline, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        val volume = state.volume
                        val volumeText = when {
                            volume == null -> null
                            volume.mute -> stringResource(R.string.remote_volume_lower_muted)
                            else -> stringResource(R.string.remote_volume_lower, volumeDrag.level ?: volume.volume)
                        }
                        val detail = playing?.title?.takeIf { it.isNotBlank() && it != headline }
                        val line = listOfNotNull(detail, volumeText)
                        if (line.isNotEmpty()) {
                            Text(
                                if (line.size == 2) stringResource(R.string.remote_source_detail, line[0], line[1]) else line[0],
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    else -> StatusLine(state)
                }
                if (state is ConnectionState.Connected) playing?.let { ProgrammeLine(it) }
                if (sleepAt != null) SleepLine(sleepAt)
            }
            Spacer(Modifier.width(12.dp))
            if (onVoice != null && state is ConnectionState.Connected) {
                VoiceKey(haptics, onVoice)
                Spacer(Modifier.width(10.dp))
            }
            PowerKey(state is ConnectionState.Connected, haptics, onPower)
        }

        when (state) {
            is ConnectionState.Connected -> {
                state.volume?.let { volume -> VolumeBar(volume, volumeDrag, haptics, onVolume) }
                // With the More tabs in sight (a tablet) the Sources tab lists these already.
                if (onMore != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    val switchTo = stringResource(R.string.remote_switch_to)
                    // A plain scrolling row, not a LazyRow: sources arrive in two loads, and a
                    // LazyRow keeps its first visible chip anchored, scrolling "TV" off-screen.
                    val scroll = rememberScrollState()
                    Row(
                        Modifier
                            .weight(1f)
                            .horizontalFade(scroll)
                            .horizontalScroll(scroll)
                            .semantics { contentDescription = switchTo },
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        sources.forEach { source ->
                            FilterChip(
                                selected = isPlaying(source, playing),
                                onClick = { onSelect(source.uri) },
                                label = { Text(source.name, maxLines = 1) },
                            )
                        }
                    }
                    TextButton(onClick = onMore) { Text(stringResource(R.string.remote_all_sources)) }
                }
            }
            is ConnectionState.Connecting -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            is ConnectionState.Unreachable -> TextButton(onClick = onRetry) { Text(stringResource(R.string.state_retry)) }
            is ConnectionState.Unauthorized -> TextButton(onClick = { onPairAgain(state.tv) }) { Text(stringResource(R.string.state_pair_again)) }
            else -> Unit
        }
    }
}

/**
 * The channel name on live TV: swipe left for the next channel, right for the previous one, like
 * flicking through cards. The name follows the finger a little and springs back.
 */
@Composable
private fun ChannelSwipe(name: String, haptics: Boolean, onChannel: (next: Boolean) -> Unit) {
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val threshold = with(LocalDensity.current) { 48.dp.toPx() }
    val currentOnChannel by rememberUpdatedState(onChannel)
    val currentHaptics by rememberUpdatedState(haptics)
    val nextLabel = stringResource(R.string.remote_channel_up)
    val previousLabel = stringResource(R.string.remote_channel_down)
    val hint = stringResource(R.string.remote_swipe_channels)
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        val dx = offset.value
                        if (abs(dx) > threshold) {
                            if (currentHaptics) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            currentOnChannel(dx < 0)
                        }
                        scope.launch { offset.animateTo(0f) }
                    },
                    onDragCancel = { scope.launch { offset.animateTo(0f) } },
                ) { change, dragAmount ->
                    change.consume()
                    scope.launch { offset.snapTo(offset.value + dragAmount) }
                }
            }
            .semantics {
                stateDescription = hint
                customActions = listOf(
                    CustomAccessibilityAction(nextLabel) { currentOnChannel(true); true },
                    CustomAccessibilityAction(previousLabel) { currentOnChannel(false); true },
                )
            },
    ) {
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = null, tint = dim, modifier = Modifier.size(20.dp))
        Text(
            name,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f, fill = false)
                .graphicsLayer {
                    translationX = offset.value * 0.5f
                    alpha = 1f - (abs(offset.value) / (threshold * 4)).coerceAtMost(0.5f)
                },
        )
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = dim, modifier = Modifier.size(20.dp))
    }
}

/** The sleep timer, when set: a moon and the time the TV goes off. */
@Composable
private fun SleepLine(at: Long) {
    val time = DateFormat.getTimeFormat(LocalContext.current).format(Date(at))
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
        Icon(Icons.Default.Bedtime, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.sleep_off_at, time), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun StatusLine(state: ConnectionState) {
    val context = LocalContext.current
    val text = when (state) {
        is ConnectionState.NoTv, is ConnectionState.Connected -> return
        is ConnectionState.Connecting -> stringResource(R.string.state_connecting, state.tv.name)
        is ConnectionState.Standby -> stringResource(R.string.state_standby, state.tv.name)
        is ConnectionState.Unreachable -> {
            // Read when the TV stops answering, so the message names the phone-side cause if any.
            val network = remember(state) { LocalNetwork.current(context) }
            stringResource(
                when (network) {
                    LocalNetwork.NO_NETWORK -> R.string.state_unreachable_offline
                    LocalNetwork.MOBILE_ONLY -> R.string.state_unreachable_mobile
                    LocalNetwork.VPN -> R.string.state_unreachable_vpn
                    LocalNetwork.OK -> R.string.state_unreachable
                },
                state.tv.name,
            )
        }
        is ConnectionState.Unauthorized -> stringResource(R.string.state_unauthorized, state.tv.name)
    }
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
}

/**
 * Under the card: "jump back" to the channel watched before (on live TV), the pinned apps, then the
 * user's scenes. Nothing at all when there is none of them.
 */
@Composable
private fun QuickChips(
    adBreak: fi.digable.braviaremote.data.AdBreakState?,
    canStartAdBreak: Boolean,
    onAdBreak: () -> Unit,
    onAdBreakMore: () -> Unit,
    onAdBreakEnd: () -> Unit,
    previous: fi.digable.braviaremote.protocol.TvChannel?,
    favorites: List<fi.digable.braviaremote.protocol.TvApp>,
    scenes: List<fi.digable.braviaremote.data.Scene>,
    onJumpBack: () -> Unit,
    onApp: (fi.digable.braviaremote.protocol.TvApp) -> Unit,
    onScene: (fi.digable.braviaremote.data.Scene) -> Unit,
) {
    if (adBreak == null && !canStartAdBreak && previous == null && favorites.isEmpty() && scenes.isEmpty()) return
    val scroll = rememberScrollState()
    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp).horizontalFade(scroll).horizontalScroll(scroll),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (adBreak != null) {
            AdBreakChips(adBreak.endsAt, onMore = onAdBreakMore, onEnd = onAdBreakEnd)
        } else if (canStartAdBreak) {
            val hint = stringResource(R.string.ad_break_hint)
            AssistChip(
                onClick = onAdBreak,
                label = { Text(stringResource(R.string.ad_break), maxLines = 1) },
                leadingIcon = { Icon(Icons.Default.Timer, contentDescription = null, modifier = Modifier.size(18.dp)) },
                modifier = Modifier.semantics { contentDescription = hint },
            )
        }
        if (previous != null) {
            AssistChip(
                onClick = onJumpBack,
                label = { Text(previous.title, maxLines = 1) },
                leadingIcon = { Icon(Icons.Default.History, contentDescription = stringResource(R.string.jump_back), modifier = Modifier.size(18.dp)) },
            )
        }
        for (app in favorites) {
            AssistChip(
                onClick = { onApp(app) },
                label = { Text(app.title, maxLines = 1) },
                leadingIcon = {
                    coil.compose.AsyncImage(
                        model = app.icon.ifBlank { null },
                        contentDescription = null,
                        modifier = Modifier.size(18.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp)),
                    )
                },
            )
        }
        for (scene in scenes) {
            AssistChip(
                onClick = { onScene(scene) },
                label = { Text(scene.name, maxLines = 1) },
                leadingIcon = { Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)) },
            )
        }
    }
}

/** A running ad break: "Back in 2:41" (tap to come back now) and "+1 min". */
@Composable
private fun AdBreakChips(endsAt: Long, onMore: () -> Unit, onEnd: () -> Unit) {
    val left by produceState(endsAt - System.currentTimeMillis(), endsAt) {
        while (true) {
            value = endsAt - System.currentTimeMillis()
            delay(1_000L - System.currentTimeMillis() % 1_000L)
        }
    }
    val seconds = ((left + 999) / 1000).coerceAtLeast(0)
    FilterChip(
        selected = true,
        onClick = onEnd,
        label = { Text(stringResource(R.string.ad_break_back_in, "%d:%02d".format(seconds / 60, seconds % 60)), maxLines = 1) },
        leadingIcon = { Icon(Icons.Default.Timer, contentDescription = null, modifier = Modifier.size(18.dp)) },
        trailingIcon = { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.ad_break_end), modifier = Modifier.size(18.dp)) },
    )
    AssistChip(onClick = onMore, label = { Text(stringResource(R.string.ad_break_more), maxLines = 1) })
}

/** Speak a channel, app, input or volume; the reply comes back as a snackbar. */
@Composable
private fun VoiceKey(haptics: Boolean, onPress: () -> Unit) {
    RemoteButton(
        label = stringResource(R.string.voice_button),
        icon = Icons.Default.Mic,
        onPress = onPress,
        size = 48.dp,
        haptics = haptics,
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.primary,
        modifier = Modifier.border(1.5.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
    )
}

/** Small and outlined: the rarest, most disruptive key should not compete with the others. */
@Composable
private fun PowerKey(isOn: Boolean, haptics: Boolean, onPress: () -> Unit) {
    val tint = if (isOn) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
    RemoteButton(
        label = stringResource(R.string.remote_power),
        icon = Icons.Default.PowerSettingsNew,
        onPress = onPress,
        size = 48.dp,
        haptics = haptics,
        containerColor = Color.Transparent,
        contentColor = tint,
        modifier = Modifier.border(1.5.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
    )
}

/** One round target with the arrows on its rim, like the ring on the physical remote. */
@Composable
private fun DPadRing(haptics: Boolean, press: (RemoteKey) -> Unit, pressRepeat: (RemoteKey) -> Unit) {
    val ring = 252.dp
    val arrow = 72.dp
    val edge = 4.dp
    val transparent = Color.Transparent
    val arrowColor = MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        Modifier
            .size(ring)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
    ) {
        RemoteButton(stringResource(R.string.remote_up), icon = Icons.Default.KeyboardArrowUp, size = arrow, repeat = true, haptics = haptics,
            containerColor = transparent, contentColor = arrowColor, onPress = { press(RemoteKey.UP) }, onRepeat = { pressRepeat(RemoteKey.UP) },
            modifier = Modifier.align(Alignment.TopCenter).padding(top = edge))
        RemoteButton(stringResource(R.string.remote_left), icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft, size = arrow, repeat = true, haptics = haptics,
            containerColor = transparent, contentColor = arrowColor, onPress = { press(RemoteKey.LEFT) }, onRepeat = { pressRepeat(RemoteKey.LEFT) },
            modifier = Modifier.align(Alignment.CenterStart).padding(start = edge))
        RemoteButton(stringResource(R.string.remote_right), icon = Icons.AutoMirrored.Filled.KeyboardArrowRight, size = arrow, repeat = true, haptics = haptics,
            containerColor = transparent, contentColor = arrowColor, onPress = { press(RemoteKey.RIGHT) }, onRepeat = { pressRepeat(RemoteKey.RIGHT) },
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = edge))
        RemoteButton(stringResource(R.string.remote_down), icon = Icons.Default.KeyboardArrowDown, size = arrow, repeat = true, haptics = haptics,
            containerColor = transparent, contentColor = arrowColor, onPress = { press(RemoteKey.DOWN) }, onRepeat = { pressRepeat(RemoteKey.DOWN) },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = edge))
        RemoteButton(
            stringResource(R.string.remote_ok), text = stringResource(R.string.remote_ok), size = 100.dp, haptics = haptics,
            containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary,
            onPress = { press(RemoteKey.CONFIRM) },
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

@Composable
private fun SystemKey(label: String, caption: String, icon: androidx.compose.ui.graphics.vector.ImageVector, haptics: Boolean, onPress: () -> Unit) {
    Column(Modifier.width(76.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        RemoteButton(
            label, icon = icon, size = 60.dp, haptics = haptics, onPress = onPress,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
        )
        Text(
            caption,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // The button already says this to TalkBack.
            modifier = Modifier.padding(top = 6.dp).clearAndSetSemantics {},
        )
    }
}

/** A vertical pill with + and − like the volume and channel rockers on the physical remote. */
@Composable
private fun Rocker(
    label: String,
    upLabel: String,
    upIcon: androidx.compose.ui.graphics.vector.ImageVector,
    downLabel: String,
    downIcon: androidx.compose.ui.graphics.vector.ImageVector,
    haptics: Boolean,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onUpRepeat: () -> Unit,
    onDownRepeat: () -> Unit,
    width: Dp = 72.dp,
) {
    Column(
        Modifier
            .width(width)
            .clip(RoundedCornerShape(width / 2))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(width / 2))
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RemoteButton(upLabel, icon = upIcon, size = 60.dp, repeat = true, haptics = haptics, onPress = onUp, onRepeat = onUpRepeat,
            containerColor = Color.Transparent, contentColor = MaterialTheme.colorScheme.onSurface)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        RemoteButton(downLabel, icon = downIcon, size = 60.dp, repeat = true, haptics = haptics, onPress = onDown, onRepeat = onDownRepeat,
            containerColor = Color.Transparent, contentColor = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun PlaybackBar(haptics: Boolean, press: (RemoteKey) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(32.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(32.dp))
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        val tint = MaterialTheme.colorScheme.onSurface
        RemoteButton(stringResource(R.string.remote_rewind), icon = Icons.Default.FastRewind, size = 48.dp, haptics = haptics,
            containerColor = Color.Transparent, contentColor = tint, onPress = { press(RemoteKey.REWIND) })
        RemoteButton(stringResource(R.string.remote_play), icon = Icons.Default.PlayArrow, size = 48.dp, haptics = haptics,
            containerColor = Color.Transparent, contentColor = tint, onPress = { press(RemoteKey.PLAY) })
        RemoteButton(stringResource(R.string.remote_pause), icon = Icons.Default.Pause, size = 48.dp, haptics = haptics,
            containerColor = Color.Transparent, contentColor = tint, onPress = { press(RemoteKey.PAUSE) })
        RemoteButton(stringResource(R.string.remote_forward), icon = Icons.Default.FastForward, size = 48.dp, haptics = haptics,
            containerColor = Color.Transparent, contentColor = tint, onPress = { press(RemoteKey.FORWARD) })
    }
}

/**
 * Fades the ends of a horizontally scrolling row while there is more to scroll that way, so a chip cut
 * at the edge reads as "more this way" rather than as a layout fault. Put it before horizontalScroll.
 */
private fun Modifier.horizontalFade(scroll: ScrollState, width: Dp = 28.dp) = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val fade = width.toPx()
        if (scroll.value > 0) {
            drawRect(
                Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), endX = fade),
                size = Size(fade, size.height),
                blendMode = BlendMode.DstIn,
            )
        }
        if (scroll.value < scroll.maxValue) {
            drawRect(
                Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = size.width - fade, endX = size.width),
                topLeft = Offset(size.width - fade, 0f),
                size = Size(fade, size.height),
                blendMode = BlendMode.DstIn,
            )
        }
    }

/** The remote's width on a phone; wider screens keep it, so keys stay where the thumb expects them. */
private val REMOTE_WIDTH = 440.dp

/** From this window size (a tablet on its side) the More tabs sit beside the remote instead of in a sheet. */
private val TWO_PANE_MIN_WIDTH = 840.dp
private val TWO_PANE_MIN_HEIGHT = 560.dp

/** A tablet held upright: room for the More tabs between the card and the remote, at this width. */
private val TALL_PANE_MIN_WIDTH = 600.dp
private val TALL_PANE_MIN_HEIGHT = 1000.dp
private val TALL_WIDTH = 640.dp

/** A tablet panel: the card's surface, outline and corners, so the panes read as one set. */
@Composable
private fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp)),
    ) { content() }
}
