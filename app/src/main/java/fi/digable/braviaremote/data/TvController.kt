package fi.digable.braviaremote.data

import android.os.Build
import fi.digable.braviaremote.BuildConfig
import fi.digable.braviaremote.di.ApplicationScope
import fi.digable.braviaremote.protocol.AtvEvent
import fi.digable.braviaremote.protocol.AtvKeyDirection
import fi.digable.braviaremote.protocol.AtvRemote
import fi.digable.braviaremote.protocol.AtvTextEdit
import fi.digable.braviaremote.protocol.BraviaApi
import fi.digable.braviaremote.protocol.BraviaAuth
import fi.digable.braviaremote.protocol.BraviaException
import fi.digable.braviaremote.protocol.ExternalInput
import fi.digable.braviaremote.protocol.IrccCodeTable
import fi.digable.braviaremote.protocol.PinPairing
import fi.digable.braviaremote.protocol.PlayingContent
import fi.digable.braviaremote.protocol.PowerStatus
import fi.digable.braviaremote.protocol.RemoteKey
import fi.digable.braviaremote.protocol.TvApp
import fi.digable.braviaremote.protocol.TvChannel
import fi.digable.braviaremote.protocol.VolumeInfo
import fi.digable.braviaremote.protocol.WakeOnLan
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient

/**
 * The live session with the active TV. One per app; survives screen rotations and navigation.
 *
 * Responsibilities: pick up the active [SavedTv], hold its [BraviaApi] with the right cookie,
 * keep [state] current, send keys in order and fast, renew the cookie before it expires, and
 * find the TV again by UUID when its IP changes.
 *
 * Keys go over the Android TV Remote channel ("fast keys", [AtvRemote]) when the TV is paired for
 * it and the channel is up, which needs the app in the foreground; otherwise over IRCC.
 */
@Singleton
class TvController @Inject constructor(
    private val store: TvStore,
    private val credentials: CredentialStore,
    private val atvIdentity: AtvIdentity,
    private val discovery: AndroidDiscovery,
    private val httpClient: OkHttpClient,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.NoTv)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    /**
     * The TV no longer accepts the fast-keys pairing (removed on the TV, or replaced by another
     * pairing). Keys still work over IRCC, but slower and without knowing the app in front, so the
     * remote asks to pair again instead of degrading silently.
     */
    private val _fastKeysRefused = MutableStateFlow(false)
    val fastKeysRefused: StateFlow<Boolean> = _fastKeysRefused.asStateFlow()

    private val _inputs = MutableStateFlow<List<ExternalInput>>(emptyList())
    val inputs: StateFlow<List<ExternalInput>> = _inputs.asStateFlow()

    private val _tvSources = MutableStateFlow<List<String>>(emptyList())
    val tvSources: StateFlow<List<String>> = _tvSources.asStateFlow()

    private val _apps = MutableStateFlow<List<TvApp>>(emptyList())
    val apps: StateFlow<List<TvApp>> = _apps.asStateFlow()

    /** Broadcast channels of every tuner source, in the TV's order. */
    private val _channels = MutableStateFlow<List<TvChannel>>(emptyList())
    val channels: StateFlow<List<TvChannel>> = _channels.asStateFlow()

    /** What the TV is showing; null when unknown, e.g. an Android TV app is in front. */
    private val _playing = MutableStateFlow<PlayingContent?>(null)
    val playing: StateFlow<PlayingContent?> = _playing.asStateFlow()

    /** The Android package in front, as the fast key channel reports it; null when unknown. */
    private val _currentApp = MutableStateFlow<String?>(null)
    val currentApp: StateFlow<String?> = _currentApp.asStateFlow()

    /** A text field on the TV with its keyboard up (from the fast key channel); null otherwise. */
    private val _tvTextField = MutableStateFlow<AtvEvent.TextField?>(null)
    val tvTextField: StateFlow<AtvEvent.TextField?> = _tvTextField.asStateFlow()

    /** The live-TV channel watched before the current one, for "jump back"; null until there is one. */
    private val previousChannelUri = MutableStateFlow<String?>(null)
    private var currentChannelUri: String? = null
    val previousChannel: StateFlow<TvChannel?> by lazy {
        combine(previousChannelUri, _channels) { uri, channels -> channels.firstOrNull { it.uri == uri } }
            .stateIn(scope, SharingStarted.Eagerly, null)
    }

    val activeTv: StateFlow<SavedTv?> get() = _activeTv
    private val _activeTv = MutableStateFlow<SavedTv?>(null)

    /** True once the saved TVs have been read, so [state] NoTv means "no TV" rather than "not read yet". */
    private val tvsLoaded = MutableStateFlow(false)

    @Volatile private var api: BraviaApi? = null
    @Volatile private var codes: IrccCodeTable = IrccCodeTable.DEFAULT
    private var connectJob: Job? = null
    private var volumeRefreshJob: Job? = null
    private var channelRefreshJob: Job? = null

    @Volatile private var foreground = false
    @Volatile private var fastKeys: AtvRemote.Connection? = null
    private var fastKeysJob: Job? = null

    /** Key presses run one after another so fast taps keep their order. */
    private val sendQueue = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    /** Keys queued or in flight; auto-repeats are skipped while the TV is still busy. */
    private val pendingKeys = AtomicInteger(0)

    init {
        scope.launch {
            for (work in sendQueue) runCatching { work() }.onFailure { DebugLog.w(it) { "Queued TV call failed" } }
        }
        scope.launch {
            _playing.collect { playing ->
                val uri = playing?.uri?.takeIf { it.startsWith("tv:") } ?: return@collect
                if (uri == currentChannelUri) return@collect
                currentChannelUri?.let { previousChannelUri.value = it }
                currentChannelUri = uri
            }
        }
        scope.launch {
            combine(store.savedTvs, store.activeTvId) { tvs, activeId ->
                tvs.firstOrNull { it.id == activeId } ?: tvs.firstOrNull()
            }.distinctUntilChanged().collect { tv ->
                _activeTv.value = tv
                tvsLoaded.value = true
                if (tv == null) {
                    api = null
                    _state.value = ConnectionState.NoTv
                } else if (api?.host != tv.host || state.value.tvOrNull?.id != tv.id) {
                    connect(tv)
                }
            }
        }
    }

    // ---- connection ----

    fun refresh() {
        _activeTv.value?.let { connect(it) }
    }

    private fun connect(tv: SavedTv) {
        connectJob?.cancel()
        stopFastKeys()
        // Set before the job starts, so a caller waiting in awaitSession() never sees the old state.
        _state.value = ConnectionState.Connecting(tv)
        connectJob = scope.launch {
            _inputs.value = emptyList()
            _apps.value = emptyList()
            _channels.value = emptyList()
            _playing.value = null
            _currentApp.value = null
            previousChannelUri.value = null
            currentChannelUri = null
            DebugLog.d { "Connecting to ${tv.name} at ${tv.host}" }
            val cookie = credentials.cookie(tv.id)
            val client = BraviaApi(
                "http://${tv.host}/", httpClient,
                cookie?.let { BraviaAuth.Cookie(it.value) } ?: BraviaAuth.None,
            )
            api = client
            codes = IrccCodeTable(credentials.irccCodes(tv.id))
            try {
                renewCookieIfNeeded(tv, client, cookie)
                val power = client.getPowerStatus()
                DebugLog.d { "Power status: $power" }
                when (power) {
                    PowerStatus.ACTIVE -> {
                        _state.value = ConnectionState.Connected(tv, fetchVolume(client))
                        warmUp(tv, client)
                        scope.launch { restoreVolumeAfterSleep(tv, client) }
                    }
                    else -> _state.value = ConnectionState.Standby(tv)
                }
            } catch (e: BraviaException.Unauthorized) {
                DebugLog.w(e) { "Connect: unauthorized" }
                _state.value = ConnectionState.Unauthorized(tv)
            } catch (e: BraviaException.ApiError) {
                DebugLog.w(e) { "Connect: API error ${e.code}" }
                _state.value = if (e.isStandby) ConnectionState.Standby(tv) else ConnectionState.Connected(tv)
            } catch (e: BraviaException.Unreachable) {
                DebugLog.w(e) { "Connect: unreachable" }
                if (!rediscover(tv)) _state.value = ConnectionState.Unreachable(tv)
            } catch (e: BraviaException) {
                // The TV answered something odd; treat it as on and let key presses go through.
                DebugLog.w(e) { "Connect: unexpected answer" }
                _state.value = ConnectionState.Connected(tv)
            }
        }
    }

    /** Things that are nice to have cached once the TV is up: inputs, apps, the TV's own key table. */
    private fun warmUp(tv: SavedTv, client: BraviaApi) {
        startFastKeys()
        warmUpKeys()
        refreshPlaying()
        scope.launch {
            // The card names the app in front from this list; the cached one is there at once.
            if (_apps.value.isEmpty()) _apps.value = store.cachedApps(tv.id)
            loadInputs()
            // Lets a channel swipe name the next channel before the TV reports it.
            loadChannels()
            if (!codes.isFromTv(RemoteKey.CONFIRM)) {
                runCatching {
                    val table = client.getRemoteControllerInfo()
                    if (table.isNotEmpty()) {
                        credentials.saveIrccCodes(tv.id, table)
                        codes = IrccCodeTable(table)
                    }
                    DebugLog.d { "TV key table: ${table.size} keys" }
                }.onFailure { DebugLog.w(it) { "Key table fetch failed; using defaults" } }
            }
            if (tv.mac == null) {
                runCatching {
                    val info = client.getSystemInformation()
                    if (info.macAddr.isNotBlank()) store.upsert(tv.copy(mac = info.macAddr), makeActive = false)
                }.onFailure { DebugLog.w(it) { "System information fetch failed" } }
            }
        }
    }

    private suspend fun renewCookieIfNeeded(tv: SavedTv, client: BraviaApi, cookie: CredentialStore.Cookie?) {
        cookie ?: return
        val expires = cookie.expiresAtEpochMs
        val soon = System.currentTimeMillis() + RENEW_AHEAD_MS
        if (expires != null && expires > soon) return
        try {
            val renewed = pairing(client).renew(cookie.value)
            credentials.saveCookie(tv.id, CredentialStore.Cookie(renewed.cookie, renewed.expiresAtEpochMs))
            client.auth = BraviaAuth.Cookie(renewed.cookie)
        } catch (e: BraviaException.Unauthorized) {
            throw e
        } catch (e: BraviaException) {
            // Renewal is best effort while the old cookie still works.
            DebugLog.w(e) { "Cookie renewal failed" }
        }
    }

    /** IP changed? Look for the same UUID on the network and retry once. */
    private suspend fun rediscover(tv: SavedTv): Boolean {
        val uuid = tv.uuid ?: return false
        val found = runCatching { discovery.findByUuid(uuid) }
            .onFailure { DebugLog.w(it) { "Rediscovery failed" } }
            .getOrNull() ?: return false
        if (found.host == tv.host) return false
        DebugLog.d { "TV moved from ${tv.host} to ${found.host}" }
        store.updateHost(tv.id, found.host)
        return true // the store change re-triggers connect()
    }

    /**
     * For callers outside the remote screen (Quick Settings tiles, the widget, the sleep timer),
     * which may run in a process that has only just started: waits until the session has settled.
     * A TV last seen in standby or out of reach is asked again, since it may have changed since. A
     * connected session is not reconnected: that would empty the lists the remote screen shows.
     * Null when no TV is saved or the TV does not answer in time.
     *
     * Android cuts a cached app off the network and destroys its sockets, so pooled HTTP connections
     * are dropped first: reusing one fails with "Software caused connection abort".
     */
    suspend fun awaitSession(): ConnectionState? = withTimeoutOrNull(SESSION_WAIT_MS) {
        httpClient.connectionPool.evictAll()
        tvsLoaded.first { it }
        when (state.value) {
            is ConnectionState.Standby, is ConnectionState.Unreachable -> refresh()
            else -> Unit
        }
        state.first { it !is ConnectionState.Connecting }.takeIf { it !is ConnectionState.NoTv }
    }

    fun pairing(client: BraviaApi): PinPairing =
        PinPairing(client, clientId = "SohvaRemote:${credentials.clientId}", nickname = DeviceName.forTv)

    // ---- fast keys (Android TV Remote channel) ----

    /** The remote screen is visible: keep the fast key channel up. */
    fun onForeground() {
        foreground = true
        startFastKeys()
    }

    /** The app left the screen: the channel is not kept open in the background. */
    fun onBackground() {
        foreground = false
        stopFastKeys()
    }

    private fun startFastKeys() {
        if (!foreground || fastKeysJob?.isActive == true) return
        val tv = state.value.tvOrNull ?: return
        if (state.value !is ConnectionState.Connected) return
        val certificate = credentials.atvCertificate(tv.id) ?: return
        fastKeysJob = scope.launch {
            var backoffMs = FAST_KEYS_RETRY_MS
            while (isActive && foreground && state.value is ConnectionState.Connected) {
                var connection: AtvRemote.Connection? = null
                try {
                    val remote = AtvRemote(
                        atvIdentity.socketFactory(), Build.MODEL, BuildConfig.APPLICATION_ID, BuildConfig.VERSION_NAME,
                    )
                    val started = System.nanoTime()
                    // Not cancelled midway: an opened connection has to reach the finally below,
                    // or its reader would keep answering the TV's pings forever.
                    connection = withContext(NonCancellable) {
                        remote.connect(tv.host, certificate) { onFastKeysEvent(tv, it) }
                    }
                    ensureActive()
                    fastKeys = connection
                    backoffMs = FAST_KEYS_RETRY_MS
                    _fastKeysRefused.value = false
                    DebugLog.d { "Fast keys up in ${(System.nanoTime() - started) / 1_000_000} ms" }
                    val cause = connection.awaitClosed()
                    DebugLog.w(cause) { "Fast keys channel closed" }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: BraviaException.Unauthorized) {
                    DebugLog.w(e) { "Fast keys: the TV's certificate changed; pair fast keys again" }
                    _fastKeysRefused.value = true
                    return@launch
                } catch (e: BraviaException.Protocol) {
                    // Most likely the TV no longer knows our certificate (pairing removed on the TV).
                    DebugLog.w(e) { "Fast keys refused; pair fast keys again" }
                    _fastKeysRefused.value = true
                    return@launch
                } catch (e: Exception) {
                    DebugLog.w(e) { "Fast keys connect failed; retrying in $backoffMs ms" }
                } finally {
                    fastKeys = null
                    _tvTextField.value = null
                    // Closing sends a TLS alert: this runs on the app scope, not the main thread.
                    connection?.close()
                }
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(FAST_KEYS_MAX_RETRY_MS)
            }
        }
    }

    private fun stopFastKeys() {
        fastKeysJob?.cancel()
        fastKeysJob = null
    }

    private fun onFastKeysEvent(tv: SavedTv, event: AtvEvent) {
        when (event) {
            is AtvEvent.Volume -> {
                val current = state.value
                if (current is ConnectionState.Connected && current.tv.id == tv.id) {
                    val target = current.volume?.target ?: "speaker"
                    _state.value = current.copy(volume = VolumeInfo(target, event.level, 0, event.max, event.muted))
                }
            }
            is AtvEvent.Received -> DebugLog.d { "TV sent ${event.describe()}" }
            is AtvEvent.CurrentApp -> {
                if (_currentApp.value == event.packageName) return
                DebugLog.d { "App in front: ${event.packageName}" }
                _currentApp.value = event.packageName
                // The card names the app from the TV's app list, and what's playing changes with it.
                if (_apps.value.isEmpty()) scope.launch { loadApps() }
                refreshPlaying(delayMs = APP_SWITCH_SETTLE_MS)
            }
            is AtvEvent.TextField -> _tvTextField.value = event
            AtvEvent.TextFieldClosed -> _tvTextField.value = null
            // Only seen as "started" on connect so far; standby shows up as the channel closing.
            is AtvEvent.Power -> DebugLog.d { "Fast keys power: ${event.on}" }
        }
    }

    // ---- keys ----

    /**
     * Wakes the TV's key service ahead of the first press when it has been idle (see
     * [BraviaApi.warmUpIrcc]). Not queued: it takes up to ~700 ms (the POST plus its settle time),
     * and a fast key must not wait for it. It never races an IRCC key either: [BraviaApi.sendIrcc]
     * waits for a warm-up in flight to finish, settle time included.
     */
    fun warmUpKeys() {
        val client = api ?: return
        if (state.value !is ConnectionState.Connected) return
        scope.launch {
            runCatching { client.warmUpIrcc() }.onFailure { DebugLog.w(it) { "Key service warm-up failed" } }
        }
    }

    /**
     * Queues one key. A [repeat] from a held button is skipped while an earlier key is still
     * queued or in flight: a key takes the TV 170-660 ms, so a fixed repeat rate would build a
     * backlog that keeps moving the focus after the button is released. Taps are never skipped.
     *
     * The transport is chosen when the key's turn comes, so a fast key never overtakes an IRCC key
     * still in flight. A key handed to the fast channel is never resent over IRCC.
     *
     * The result completes once the key has been sent or has failed, for callers that must not let
     * the process go before that (a widget or tile tap); the remote screen ignores it.
     */
    /**
     * Holds [key] down on the TV until called again with [down] false: Android TV's long press
     * (OK opens a context menu). Only the fast key channel can hold a key; without it the start of
     * a hold becomes one ordinary press.
     */
    fun hold(key: RemoteKey, down: Boolean) {
        val client = api ?: return
        val code = key.atvKeyCode
        sendQueue.trySend {
            runCatching {
                val channel = fastKeys
                if (code != null && channel != null && channel.isOpen &&
                    channel.sendKey(code, if (down) AtvKeyDirection.START_LONG else AtvKeyDirection.END_LONG)
                ) {
                    DebugLog.d { "Key $key ${if (down) "held" else "released"} over fast keys" }
                } else if (down) {
                    client.sendIrcc(codes.codeFor(key))
                    DebugLog.d { "Key $key: no fast keys, sent as one press" }
                }
            }.onFailure { DebugLog.w(it) { "Hold $key failed" } }
        }
    }

    fun press(key: RemoteKey, repeat: Boolean = false): Deferred<Unit> {
        val done = CompletableDeferred<Unit>()
        val client = api ?: run {
            DebugLog.w { "Key $key dropped: no TV session" }
            return done.apply { complete(Unit) }
        }
        if (repeat && pendingKeys.get() > 0) {
            DebugLog.d { "Repeat $key skipped: TV still busy" }
            return done.apply { complete(Unit) }
        }
        DebugLog.d { if (repeat) "Key $key (repeat)" else "Key $key" }
        pendingKeys.incrementAndGet()
        sendQueue.trySend {
            try {
                val code = key.atvKeyCode
                val channel = fastKeys
                if (code != null && channel != null && channel.isOpen && channel.sendKey(code)) {
                    DebugLog.d { "Key $key over fast keys" }
                    afterKey(key, fast = true)
                } else {
                    client.sendIrcc(codes.codeFor(key))
                    afterKey(key, fast = false)
                }
            } catch (e: BraviaException.Unauthorized) {
                DebugLog.w(e) { "Key $key: unauthorized" }
                markUnauthorized()
            } catch (e: BraviaException.Unreachable) {
                DebugLog.w(e) { "Key $key: unreachable" }
                state.value.tvOrNull?.let { _state.value = ConnectionState.Unreachable(it) }
            } catch (e: Exception) {
                // Protocol hiccup on a single key: ignore, the next press will tell.
                DebugLog.w(e) { "Key $key failed" }
            } finally {
                pendingKeys.decrementAndGet()
                done.complete(Unit)
            }
        }
        return done
    }

    private fun afterKey(key: RemoteKey, fast: Boolean) {
        when (key) {
            // Over fast keys the TV pushes the new volume itself.
            RemoteKey.VOLUME_UP, RemoteKey.VOLUME_DOWN -> if (!fast) scheduleVolumeRefresh()
            RemoteKey.MUTE -> scheduleVolumeRefresh()
            RemoteKey.POWER, RemoteKey.POWER_OFF, RemoteKey.WAKE_UP -> scope.launch { delay(1500); refresh() }
            RemoteKey.HOME, RemoteKey.RETURN -> refreshPlaying(delayMs = 1000)
            // So the card shows the new channel's name, e.g. after a swipe.
            RemoteKey.CHANNEL_UP, RemoteKey.CHANNEL_DOWN -> afterChannelKey(next = key == RemoteKey.CHANNEL_UP)
            else -> Unit
        }
    }

    private fun scheduleVolumeRefresh() {
        volumeRefreshJob?.cancel()
        volumeRefreshJob = scope.launch {
            // Read once quickly for feedback, then again once an HDMI-CEC sound system has
            // reported its own level back to the TV (about 350 ms after the key).
            for (wait in VOLUME_REFRESH_DELAYS_MS) {
                delay(wait)
                val client = api ?: return@launch
                val tv = state.value.tvOrNull ?: return@launch
                fetchVolume(client)?.let { vol ->
                    DebugLog.d { "Volume ${vol.volume}${if (vol.mute) " (muted)" else ""}" }
                    if (state.value is ConnectionState.Connected || state.value is ConnectionState.Standby) {
                        _state.value = ConnectionState.Connected(tv, vol)
                    }
                }
            }
        }
    }

    private suspend fun fetchVolume(client: BraviaApi) =
        runCatching { client.getVolumeInformation().firstOrNull { it.target == "speaker" } ?: client.getVolumeInformation().firstOrNull() }
            .onFailure { DebugLog.w(it) { "Volume fetch failed" } }
            .getOrNull()

    /** Types into, deletes from or submits the TV's text field; false when no field is open. */
    fun editTvText(edit: AtvTextEdit): Boolean {
        val channel = fastKeys ?: return false
        return channel.edit(edit).also { if (!it) DebugLog.w { "Text edit $edit refused: no open text field" } }
    }

    // ---- power ----

    /** A double tap on Power (screen, tile or widget) must not switch the TV off and straight back on. */
    private val powerGuard = PowerGuard()

    fun togglePower(): Job? {
        _activeTv.value ?: return null
        if (!powerGuard.tryAcquire()) {
            DebugLog.d { "Power: ignored, pressed again too soon" }
            return null
        }
        return scope.launch {
            if (state.value is ConnectionState.Connected) powerOff() else powerOn()
        }
    }

    /**
     * Asks the TV whether it is on rather than trusting [state], which may be minutes old for a tile
     * or the widget, then switches it the other way.
     */
    suspend fun togglePowerNow() {
        if (!powerGuard.tryAcquire()) {
            DebugLog.d { "Power: ignored, pressed again too soon" }
            return
        }
        val session = awaitSession() ?: return
        val client = api ?: return
        val on = if (session is ConnectionState.Connected) {
            runCatching { client.getPowerStatus() == PowerStatus.ACTIVE }.getOrDefault(true)
        } else {
            false
        }
        if (on) powerOff() else powerOn()
    }

    /**
     * Turns the TV off and never on: the sleep timer must not wake a TV someone already switched
     * off, so there is no fallback to the toggling Power key, only to the discrete PowerOff key.
     */
    suspend fun powerOff() {
        val client = api ?: return
        runCatching { client.setPowerStatus(false) }
            .onFailure {
                DebugLog.w(it) { "setPowerStatus(off) failed; sending PowerOff key" }
                press(RemoteKey.POWER_OFF).await()
            }
        delay(1200)
        refresh()
    }

    /**
     * The sleep timer's last minute: steps the volume down to a third by [endsAt], then switches
     * the TV off, never on, like [powerOff]. [stillWanted] is asked before every step: a timer
     * cancelled or replaced meanwhile stops the fade and the volume goes back up.
     *
     * The volume cannot be put back before standby without being heard (any step unmutes the TV,
     * and in standby it refuses volume changes), so the old level is remembered and restored the
     * next time the app sees the TV on ([restoreVolumeAfterSleep]).
     */
    suspend fun fadeOutAndOff(endsAt: Long, stillWanted: suspend () -> Boolean) {
        val tv = state.value.tvOrNull
        if (awaitSession() !is ConnectionState.Connected || tv == null) return
        val client = api ?: return
        val start = fetchVolume(client)
        val fadeMs = (endsAt - System.currentTimeMillis()).coerceAtLeast(0)
        if (start == null || start.mute || start.volume <= FADE_FLOOR_MIN) {
            delay(fadeMs)
            if (stillWanted()) powerOff()
            return
        }
        val floor = (start.volume / 3).coerceAtLeast(FADE_FLOOR_MIN)
        var faded = false
        try {
            for (step in 1..FADE_STEPS) {
                delay(fadeMs / FADE_STEPS)
                if (!stillWanted()) return
                stepVolumeTo(client, start.volume - (start.volume - floor) * step / FADE_STEPS)
            }
            if (!stillWanted()) return
            faded = true
        } finally {
            if (!faded) withContext(NonCancellable) { stepVolumeTo(client, start.volume) }
        }
        val reached = fetchVolume(client)?.volume ?: floor
        store.setVolumeRestore(TvStore.VolumeRestore(tv.id, start.volume, reached))
        DebugLog.d { "Sleep fade: ${start.volume} -> $reached, TV off" }
        powerOff()
    }

    /**
     * Gives the TV back the volume it had before a sleep-timer fade, if it still sits at the faded
     * level (someone who already turned it up keeps their level). Runs when the TV is seen on; an
     * HDMI-CEC sound system answers volume reads only some seconds after the TV wakes, so this
     * waits for it, and the reminder is kept until the restore has happened or is not needed.
     */
    private suspend fun restoreVolumeAfterSleep(tv: SavedTv, client: BraviaApi) {
        val restore = store.volumeRestore.first()?.takeIf { it.tvId == tv.id } ?: return
        // A TV that has just woken answers slowly or not at all for several seconds.
        delay(RESTORE_SETTLE_MS)
        repeat(RESTORE_ATTEMPTS) {
            val volume = fetchVolume(client)
            when {
                volume == null -> Unit
                volume.mute || kotlin.math.abs(volume.volume - restore.faded) > VOLUME_STEP -> {
                    // Already restored, or someone set their own level: leave it.
                    store.setVolumeRestore(null)
                    return
                }
                else -> {
                    DebugLog.d { "Restoring volume ${restore.level} after the sleep fade" }
                    if (setVolumeWithRetries(client, restore.level)) {
                        store.setVolumeRestore(null)
                        return
                    }
                }
            }
            delay(RESTORE_RETRY_MS)
        }
    }

    /**
     * Moves the volume to [target]. With an HDMI-CEC sound system each setAudioVolume moves one
     * step (2) toward the target whatever its value, and the new level shows up late, so this
     * repeats while reading the level back: it stops when the level is there, or has not moved
     * for a few reads (the TV refuses), stepping back if a late report made it go past.
     */
    private suspend fun stepVolumeTo(client: BraviaApi, target: Int) {
        var last: Int? = null
        var stalls = 0
        repeat(MAX_VOLUME_STEPS) {
            val now = fetchVolume(client)?.volume ?: return
            if (kotlin.math.abs(now - target) < VOLUME_STEP) return
            if (now == last && ++stalls > MAX_VOLUME_STALLS) return
            if (now != last) stalls = 0
            last = now
            runCatching { client.setAudioVolume(target.toString()) }.onFailure {
                DebugLog.w(it) { "setAudioVolume failed" }
                return
            }
            // The sound system reports its new level back to the TV after 350-600 ms.
            delay(VOLUME_SETTLE_MS)
        }
    }

    /** Whether the TV is on right now, asked fresh; null when there is no TV to ask. */
    private suspend fun isOn(): Boolean? {
        val session = awaitSession() ?: return null
        if (session !is ConnectionState.Connected) return false
        // A cached process can still say Connected after the TV was switched off with its own
        // remote: ask the TV, and if it does not say on, connect again to learn its real state.
        val power = api?.let { client -> runCatching { client.getPowerStatus() }.getOrNull() }
        if (power == PowerStatus.ACTIVE) return true
        refresh()
        return state.first { it !is ConnectionState.Connecting } is ConnectionState.Connected
    }

    /**
     * For a wake-up or a scene: switches the TV on if it is off and waits until it answers and has
     * settled (a just-woken TV answers slowly for some seconds). True when the TV is on.
     */
    suspend fun ensureOn(): Boolean {
        if (isOn() ?: return false) return true
        powerOn()
        val on = withTimeoutOrNull(WAKE_WAIT_MS) {
            // The first read after waking can still say standby; ask again until the TV is up.
            // Not once it is: a refresh goes back through Connecting, and the loop would never end.
            while (state.value !is ConnectionState.Connected) {
                delay(WAKE_POLL_MS)
                when (state.value) {
                    is ConnectionState.Connected, is ConnectionState.Connecting -> Unit
                    else -> refresh()
                }
            }
        } != null
        if (on) delay(RESTORE_SETTLE_MS)
        return on
    }

    /**
     * Switches the TV on if needed, then to [target] and [volume] when given: a wake-up or a scene.
     * A volume given here replaces any restore pending from the sleep fade. With [onlyIfOn] (a
     * scheduled scene) a TV that is off is left off, and nothing changes.
     */
    suspend fun apply(target: TvTarget?, volume: Int?, onlyIfOn: Boolean = false): Boolean {
        if (onlyIfOn && isOn() != true) {
            DebugLog.d { "TV off: ${target?.name ?: "scene"} skipped" }
            return false
        }
        if (volume != null) store.setVolumeRestore(null)
        if (!ensureOn()) return false
        val client = api ?: return false
        if (target != null) {
            runCatching {
                if (target.kind == TvTarget.Kind.APP) client.setActiveApp(target.uri) else client.setPlayContent(target.uri)
            }.onFailure { DebugLog.w(it) { "Switching to ${target.name} failed" } }
            refreshPlaying(delayMs = 1500)
            // Switching input moves the sound (TV speakers or the CEC soundbar) about a second later,
            // which resets the volume the TV reports: set the scene's volume after that.
            if (volume != null) delay(SWITCH_SETTLE_MS)
        }
        if (volume != null) setVolumeWithRetries(client, volume)
        DebugLog.d { "Applied ${target?.name ?: "no target"}, volume ${volume ?: "unchanged"}" }
        return true
    }

    /** Steps to [level] and checks; a TV that has just woken may need a few tries. */
    private suspend fun setVolumeWithRetries(client: BraviaApi, level: Int): Boolean {
        repeat(RESTORE_ATTEMPTS) {
            stepVolumeTo(client, level)
            // Read again a moment later: a TV still settling can report the level and then move.
            delay(VOLUME_CONFIRM_MS)
            val now = fetchVolume(client)
            if (now != null && kotlin.math.abs(now.volume - level) <= VOLUME_STEP) {
                scheduleVolumeRefresh()
                return true
            }
            delay(RESTORE_RETRY_MS)
        }
        DebugLog.w { "Volume $level not reached" }
        return false
    }

    private suspend fun powerOn() {
        val tv = _activeTv.value ?: return
        val client = api ?: return
        val s = state.value
        tv.mac?.let { mac ->
            runCatching { WakeOnLan.send(mac, tv.host) }.onFailure { DebugLog.w(it) { "Wake-on-LAN failed" } }
        }
        val woke = withTimeoutOrNull(2500) {
            runCatching { client.setPowerStatus(true) }
                .onFailure { DebugLog.w(it) { "setPowerStatus(on) failed" } }
                .isSuccess
        } ?: false
        DebugLog.d { "Power on: woke=$woke" }
        if (!woke && s !is ConnectionState.Unreachable) press(RemoteKey.POWER).await()
        delay(2000)
        refresh()
    }

    fun setVolume(level: Int) {
        val client = api ?: return
        sendQueue.trySend {
            stepVolumeTo(client, level)
            scheduleVolumeRefresh()
        }
    }

    /**
     * Mutes or unmutes the TV from outside the remote screen (a phone call), reading the TV's
     * current mute first. True when it changed it, false when it already was that way, null when
     * the TV is off or did not answer.
     */
    suspend fun setMutedNow(muted: Boolean): Boolean? {
        if (awaitSession() !is ConnectionState.Connected) return null
        val client = api ?: return null
        val current = fetchVolume(client) ?: return null
        if (current.mute == muted) return false
        return runCatching { client.setAudioMute(muted) }
            .onFailure { DebugLog.w(it) { "setAudioMute($muted) failed" } }
            .getOrNull()
            ?.let { true }
            .also { if (it == true) scheduleVolumeRefresh() }
    }

    /** Completes once the change has been sent, like [press]. */
    fun toggleMute(): Deferred<Unit>? {
        val client = api ?: return null
        val muted = (state.value as? ConnectionState.Connected)?.volume?.mute ?: return press(RemoteKey.MUTE)
        val done = CompletableDeferred<Unit>()
        sendQueue.trySend {
            try {
                runCatching { client.setAudioMute(!muted) }.onFailure {
                    DebugLog.w(it) { "setAudioMute failed; sending Mute key" }
                    press(RemoteKey.MUTE)
                }
                scheduleVolumeRefresh()
            } finally {
                done.complete(Unit)
            }
        }
        return done
    }

    // ---- inputs & apps ----

    suspend fun loadInputs() {
        val client = api ?: return
        try {
            _inputs.value = client.getCurrentExternalInputsStatus()
            _tvSources.value = runCatching { client.getTvSources() }
                .onFailure { DebugLog.w(it) { "Tuner source list fetch failed" } }
                .getOrDefault(emptyList())
            DebugLog.d { "Inputs: ${_inputs.value.size}, tuner sources: ${_tvSources.value}" }
        } catch (e: BraviaException.Unauthorized) {
            markUnauthorized()
        } catch (e: Exception) {
            // keep the old list
            DebugLog.w(e) { "Input list fetch failed" }
        }
    }

    /** Shows the last known list at once, then replaces it with the TV's current one. */
    suspend fun loadApps() {
        val client = api ?: return
        val tvId = state.value.tvOrNull?.id
        if (_apps.value.isEmpty() && tvId != null) _apps.value = store.cachedApps(tvId)
        try {
            val apps = client.getApplicationList().filter { it.title.isNotBlank() }.sortedBy { it.title.lowercase() }
            _apps.value = apps
            if (tvId != null) store.saveApps(tvId, apps)
        } catch (e: BraviaException.Unauthorized) {
            markUnauthorized()
        } catch (e: Exception) {
            // keep the old list
            DebugLog.w(e) { "App list fetch failed" }
        }
    }

    suspend fun loadChannels() {
        val client = api ?: return
        if (_tvSources.value.isEmpty()) loadInputs()
        try {
            _channels.value = _tvSources.value.flatMap { client.getChannels(it) }
            DebugLog.d { "Channels: ${_channels.value.size}" }
        } catch (e: BraviaException.Unauthorized) {
            markUnauthorized()
        } catch (e: Exception) {
            // keep the old list
            DebugLog.w(e) { "Channel list fetch failed" }
        }
    }

    fun selectInput(uri: String) {
        val client = api ?: return
        sendQueue.trySend {
            runCatching { client.setPlayContent(uri) }.onFailure { DebugLog.w(it) { "setPlayContent($uri) failed" } }
            refreshPlaying(delayMs = 1500)
        }
    }

    fun launchApp(uri: String) {
        val client = api ?: return
        sendQueue.trySend {
            runCatching { client.setActiveApp(uri) }.onFailure { DebugLog.w(it) { "setActiveApp failed" } }
            refreshPlaying(delayMs = 1500)
        }
    }

    /**
     * Shows the neighbouring channel at once (channel up follows the TV's channel list order),
     * then confirms with the TV. The TV takes one to two seconds to report the new channel, so it is
     * read a few times, and reads that still name the channel we left are ignored. Another channel
     * key restarts this, so quick swipes end on the last channel.
     */
    private fun afterChannelKey(next: Boolean) {
        val left = _playing.value
        val channels = _channels.value
        val index = channels.indexOfFirst { it.uri == left?.uri }
        if (left != null && index >= 0) {
            val target = channels[(index + if (next) 1 else -1).mod(channels.size)]
            _playing.value = PlayingContent(uri = target.uri, title = target.title, source = left.source)
        }
        val client = api ?: return
        channelRefreshJob?.cancel()
        channelRefreshJob = scope.launch {
            CHANNEL_REFRESH_DELAYS_MS.forEachIndexed { i, wait ->
                delay(wait)
                val now = runCatching { client.getPlayingContentInfo() }
                    .onFailure { DebugLog.w(it) { "Playing content fetch failed" } }
                    .getOrNull() ?: return@forEachIndexed
                val last = i == CHANNEL_REFRESH_DELAYS_MS.lastIndex
                if (now.uri != left?.uri || last) {
                    DebugLog.d { "Playing: ${now.uri} ${now.raw}" }
                    _playing.value = now
                }
            }
        }
    }

    /** Re-reads what the TV is showing, after [delayMs] for the TV to finish switching. */
    private var nextProgrammeJob: Job? = null

    /** Asks again just after the programme on now ends, so the card shows the next one. */
    private fun scheduleNextProgramme(playing: PlayingContent?) {
        nextProgrammeJob?.cancel()
        val end = playing?.programWindow?.second ?: return
        val wait = end - System.currentTimeMillis() + PROGRAMME_END_GRACE_MS
        if (wait <= 0 || wait > PROGRAMME_MAX_WAIT_MS) return
        nextProgrammeJob = scope.launch {
            delay(wait)
            if (_playing.value?.uri == playing.uri) refreshPlaying()
        }
    }

    fun refreshPlaying(delayMs: Long = 0) {
        val client = api ?: return
        scope.launch {
            delay(delayMs)
            if (state.value !is ConnectionState.Connected) return@launch
            runCatching { client.getPlayingContentInfo() }
                .onSuccess {
                    DebugLog.d { "Playing: ${it?.uri ?: "nothing reported"} ${it?.raw.orEmpty()}" }
                    _playing.value = it
                    scheduleNextProgramme(it)
                }
                .onFailure { DebugLog.w(it) { "Playing content fetch failed" } }
        }
    }

    // ---- TVs ----

    suspend fun forget(tv: SavedTv) {
        if (state.value.tvOrNull?.id == tv.id) stopFastKeys()
        credentials.clear(tv.id)
        store.remove(tv.id)
    }

    suspend fun switchTo(tv: SavedTv) {
        store.setActive(tv.id)
        store.savedTvs.first() // make sure the write landed before callers continue
    }

    private fun markUnauthorized() {
        DebugLog.w { "TV rejected our credentials; pairing needed" }
        state.value.tvOrNull?.let { _state.value = ConnectionState.Unauthorized(it) }
    }

    private companion object {
        /** Renew a cookie that expires within three days; the TV issues them for about 14. */
        const val RENEW_AHEAD_MS = 3L * 24 * 60 * 60 * 1000

        /** Volume re-reads after a volume key: quick feedback, then the sound system's settled level. */
        val VOLUME_REFRESH_DELAYS_MS = longArrayOf(250, 600)

        /** Gaps between reads of what is playing after a channel key (at 0.8, 1.8 and 3 s). */
        val CHANNEL_REFRESH_DELAYS_MS = longArrayOf(800, 1000, 1200)

        /** Lets the TV finish switching apps before asking what is playing. */
        const val APP_SWITCH_SETTLE_MS = 700L

        /** Fast key reconnects back off from 2 s to 30 s; IRCC carries the keys meanwhile. */
        const val FAST_KEYS_RETRY_MS = 2_000L
        const val FAST_KEYS_MAX_RETRY_MS = 30_000L

        /** Volume steps in the sleep timer's fade, one every ten seconds. */
        const val FADE_STEPS = 6

        /** The fade ends at a third of the volume, never below this. */
        const val FADE_FLOOR_MIN = 4

        /** One volume key moves an HDMI-CEC sound system's level by 2. */
        const val VOLUME_STEP = 2
        const val MAX_VOLUME_STEPS = 60
        const val VOLUME_SETTLE_MS = 600L
        const val MAX_VOLUME_STALLS = 2

        /** A just-woken TV gets time to settle before the volume restore, then a few tries. */
        const val RESTORE_SETTLE_MS = 8_000L

        /** How long a wake-up or scene waits for the TV to come on, asking every few seconds. */
        const val WAKE_WAIT_MS = 45_000L
        const val WAKE_POLL_MS = 3_000L
        const val RESTORE_ATTEMPTS = 4
        const val RESTORE_RETRY_MS = 3_000L
        const val SWITCH_SETTLE_MS = 2_500L
        /** The TV switches its now-playing info a little after the scheduled end. */
        const val PROGRAMME_END_GRACE_MS = 5_000L
        const val PROGRAMME_MAX_WAIT_MS = 6 * 60 * 60_000L
        const val VOLUME_CONFIRM_MS = 1_500L

        /** How long a tile, widget or timer waits for the TV to answer before giving up. */
        const val SESSION_WAIT_MS = 6_000L
    }
}
