package fi.digable.braviaremote.ui.remote

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import fi.digable.braviaremote.R
import fi.digable.braviaremote.data.AppShortcuts
import fi.digable.braviaremote.data.ConnectionState
import fi.digable.braviaremote.data.CredentialStore
import fi.digable.braviaremote.data.HardwareKeys
import fi.digable.braviaremote.data.SavedTv
import fi.digable.braviaremote.data.Scene
import fi.digable.braviaremote.data.SceneSchedule
import fi.digable.braviaremote.data.SleepTimer
import fi.digable.braviaremote.data.TvController
import fi.digable.braviaremote.data.TvStore
import fi.digable.braviaremote.data.TvTarget
import fi.digable.braviaremote.data.WakeUp
import fi.digable.braviaremote.data.WakeUpAlarm
import fi.digable.braviaremote.data.WakeUpService
import fi.digable.braviaremote.protocol.AtvEvent
import fi.digable.braviaremote.protocol.AtvTextEdit
import fi.digable.braviaremote.protocol.ExternalInput
import fi.digable.braviaremote.protocol.PlayingContent
import fi.digable.braviaremote.protocol.RemoteKey
import fi.digable.braviaremote.protocol.TvApp
import fi.digable.braviaremote.protocol.TvChannel
import fi.digable.braviaremote.shortcuts.NotificationRemote
import fi.digable.braviaremote.voice.VoiceAction
import fi.digable.braviaremote.voice.VoiceCommands
import fi.digable.braviaremote.voice.VoiceInbox
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class RemoteViewModel @Inject constructor(
    private val controller: TvController,
    private val store: TvStore,
    private val credentials: CredentialStore,
    private val sleepTimer: SleepTimer,
    private val wakeUpAlarm: WakeUpAlarm,
    private val sceneSchedule: SceneSchedule,
    private val notificationRemote: NotificationRemote,
    @ApplicationContext private val context: Context,
    hardwareKeys: HardwareKeys,
    voiceInbox: VoiceInbox,
) : ViewModel() {
    val state: StateFlow<ConnectionState> = controller.state
    val inputs: StateFlow<List<ExternalInput>> = controller.inputs
    val tvSources: StateFlow<List<String>> = controller.tvSources
    val apps: StateFlow<List<TvApp>> = controller.apps
    val channels: StateFlow<List<TvChannel>> = controller.channels
    val playing: StateFlow<PlayingContent?> = controller.playing
    val tvTextField: StateFlow<AtvEvent.TextField?> = controller.tvTextField

    /** Title of the Android app in front (from the fast key channel), when the TV's app list has it. */
    val currentAppTitle: StateFlow<String?> =
        combine(controller.currentApp, controller.apps) { packageName, apps ->
            packageName?.let { name -> apps.firstOrNull { it.isPackage(name) }?.title }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val savedTvs: StateFlow<List<SavedTv>> =
        store.savedTvs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    /** The channel watched before this one, for the "jump back" chip. */
    val previousChannel: StateFlow<TvChannel?> = controller.previousChannel

    /** Null until the saved settings are read: the defaults must never show, or be saved by a tap. */
    val wakeUp: StateFlow<WakeUp?> = wakeUpAlarm.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val scenes: StateFlow<List<Scene>> = store.scenes.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val favoriteApps: StateFlow<List<TvApp>> = store.favoriteApps.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun toggleFavoriteApp(app: TvApp) = viewModelScope.launch {
        AppShortcuts.publish(context, store.toggleFavoriteApp(app))
    }

    /** What a wake-up or scene can switch to: channels, then connected inputs, then apps. */
    val targets: StateFlow<List<TvTarget>> = combine(controller.channels, controller.inputs, controller.apps) { channels, inputs, apps ->
        channels.map { TvTarget(TvTarget.Kind.CHANNEL, it.uri, it.title) } +
            inputs.filter { it.connection }.distinctBy { it.displayName }.map { TvTarget(TvTarget.Kind.INPUT, stableInputUri(it.uri), it.displayName) } +
            apps.map { TvTarget(TvTarget.Kind.APP, it.uri, it.title) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** When the sleep timer turns the TV off (epoch ms); null when it is not set. */
    val sleepAt: StateFlow<Long?> =
        sleepTimer.endsAt.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val muteOnCalls: StateFlow<Boolean> =
        store.muteOnCalls.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val hapticsEnabled: StateFlow<Boolean> =
        store.hapticsEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val touchpad: StateFlow<Boolean> =
        store.touchpad.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    init {
        // Phone volume buttons drive the TV while the remote is open (F15).
        viewModelScope.launch { hardwareKeys.presses.collect { controller.press(it.key, it.repeat) } }
        viewModelScope.launch { voiceInbox.texts.collect { runVoice(listOf(it)) } }
    }

    fun press(key: RemoteKey) = controller.press(key)
    fun pressRepeat(key: RemoteKey) = controller.press(key, repeat = true)
    fun togglePower() = controller.togglePower()
    fun toggleMute() = controller.toggleMute()
    fun refresh() = controller.refresh()
    fun onShown() {
        controller.onForeground()
        controller.warmUpKeys()
        controller.refreshPlaying()
    }

    fun onHidden() = controller.onBackground()

    fun loadInputs() = viewModelScope.launch { controller.loadInputs() }
    fun loadApps() = viewModelScope.launch { controller.loadApps() }
    fun loadChannels() = viewModelScope.launch { controller.loadChannels() }
    fun changeChannel(next: Boolean) = controller.press(if (next) RemoteKey.CHANNEL_UP else RemoteKey.CHANNEL_DOWN)
    /** Makes the TV's text field go from [old] to [new]. */
    fun typeOnTv(old: String, new: String) = AtvTextEdit.between(old, new).forEach { controller.editTvText(it) }
    fun submitOnTv() = controller.editTvText(AtvTextEdit.Submit)
    private val _voiceReplies = Channel<String>(Channel.CONFLATED)

    /** One line per spoken command: what the app understood and did, or that it did not. */
    val voiceReplies: Flow<String> = _voiceReplies.receiveAsFlow()

    /** Runs the first of the recognizer's guesses that names something the TV has. */
    fun runVoice(guesses: List<String>) {
        val action = guesses.firstNotNullOfOrNull {
            VoiceCommands.parse(it, controller.channels.value, controller.apps.value, controller.inputs.value)
        }
        val reply = when (action) {
            null -> context.getString(R.string.voice_unknown, guesses.firstOrNull().orEmpty())
            is VoiceAction.SetVolume -> {
                controller.setVolume(action.level)
                context.getString(R.string.voice_volume, action.level)
            }
            is VoiceAction.StepVolume -> {
                val key = if (action.up) RemoteKey.VOLUME_UP else RemoteKey.VOLUME_DOWN
                repeat(VOICE_VOLUME_STEPS) { controller.press(key) }
                context.getString(if (action.up) R.string.voice_louder else R.string.voice_quieter)
            }
            is VoiceAction.Mute -> {
                val muted = (controller.state.value as? ConnectionState.Connected)?.volume?.mute
                if (muted != action.muted) controller.toggleMute()
                context.getString(if (action.muted) R.string.voice_mute else R.string.voice_unmute)
            }
            VoiceAction.PowerOff -> {
                viewModelScope.launch { controller.powerOff() }
                context.getString(R.string.voice_off)
            }
            is VoiceAction.Channel -> {
                controller.selectInput(action.channel.uri)
                context.getString(R.string.voice_switch, action.channel.title)
            }
            is VoiceAction.Input -> {
                controller.selectInput(action.input.uri)
                context.getString(R.string.voice_switch, action.input.displayName)
            }
            is VoiceAction.App -> {
                controller.launchApp(action.app.uri)
                context.getString(R.string.voice_switch, action.app.title)
            }
        }
        _voiceReplies.trySend(reply)
    }

    fun selectInput(uri: String) = controller.selectInput(uri)
    fun launchApp(uri: String) = controller.launchApp(uri)

    fun hasFastKeys(tv: SavedTv): Boolean = credentials.atvCertificate(tv.id) != null

    fun switchTo(tv: SavedTv) = viewModelScope.launch { controller.switchTo(tv) }
    fun forget(tv: SavedTv) = viewModelScope.launch { controller.forget(tv) }
    fun startSleepTimer(minutes: Int) = viewModelScope.launch { sleepTimer.start(minutes) }
    fun cancelSleepTimer() = viewModelScope.launch { sleepTimer.cancel() }
    fun jumpBack() = controller.previousChannel.value?.let { controller.selectInput(it.uri) }

    fun setWakeUp(wakeUp: WakeUp) = viewModelScope.launch { wakeUpAlarm.save(wakeUp) }
    fun tryWakeUp() {
        wakeUpAlarm.tryInOneMinute()
        _voiceReplies.trySend(context.getString(R.string.wake_trying))
    }

    fun saveScene(scene: Scene) = viewModelScope.launch {
        store.saveScene(scene)
        sceneSchedule.reschedule()
    }
    fun deleteScene(scene: Scene) = viewModelScope.launch {
        store.deleteScene(scene.id)
        sceneSchedule.reschedule()
    }
    /** In a foreground service, so leaving the app does not cut a scene short while the TV comes on. */
    fun runScene(scene: Scene) {
        _voiceReplies.trySend(context.getString(R.string.scene_running, scene.name))
        if (!WakeUpService.runScene(context, scene)) controller.let { c -> viewModelScope.launch { c.apply(scene.target, scene.volume) } }
    }

    fun setMuteOnCalls(enabled: Boolean) = viewModelScope.launch { store.setMuteOnCalls(enabled) }
    fun setHaptics(enabled: Boolean) = viewModelScope.launch { store.setHaptics(enabled) }
    fun setTouchpad(enabled: Boolean) = viewModelScope.launch { store.setTouchpad(enabled) }

    val notificationRemoteEnabled: StateFlow<Boolean> =
        store.notificationRemote.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setNotificationRemote(enabled: Boolean) = viewModelScope.launch {
        store.setNotificationRemote(enabled)
        notificationRemote.sync()
    }

    private companion object {
        /** "Louder" moves as far as a few presses of the volume key. */
        const val VOICE_VOLUME_STEPS = 3
    }
}

/**
 * A device found over HDMI-CEC (`extInput:cec?type=player&port=3&logicalAddr=4`) is listed only
 * while it is awake; a saved scene or wake-up uses its HDMI port instead, which always exists.
 */
internal fun stableInputUri(uri: String): String {
    if (!uri.startsWith("extInput:cec")) return uri
    val port = Regex("[?&]port=(\\d+)").find(uri)?.groupValues?.get(1) ?: return uri
    return "extInput:hdmi?port=$port"
}
