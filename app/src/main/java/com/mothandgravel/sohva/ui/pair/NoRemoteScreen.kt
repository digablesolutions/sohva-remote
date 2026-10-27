package com.mothandgravel.sohva.ui.pair

import android.os.Build
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.mothandgravel.sohva.BuildConfig
import com.mothandgravel.sohva.R
import com.mothandgravel.sohva.data.AtvIdentity
import com.mothandgravel.sohva.data.CredentialStore
import com.mothandgravel.sohva.data.DebugLog
import com.mothandgravel.sohva.data.TvStore
import com.mothandgravel.sohva.protocol.AtvRemote
import com.mothandgravel.sohva.protocol.DiscoveredTv
import com.mothandgravel.sohva.protocol.TvChecker
import com.mothandgravel.sohva.ui.StepsCard
import com.mothandgravel.sohva.ui.remote.DPadRing
import com.mothandgravel.sohva.ui.remote.SystemKey
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

data class NoRemoteUiState(
    val connected: Boolean = false,
    val failed: Boolean = false,
    /** The TV's web API answers now: IP control is on, and PIN pairing can go ahead. */
    val ipControlOn: Boolean = false,
    val checking: Boolean = false,
    val stillOff: Boolean = false,
)

/**
 * Drives the TV's menus over its Android TV Remote service, which needs no setting on the TV, so
 * a user without the physical remote can switch IP control on and then pair the app properly.
 */
@HiltViewModel
class NoRemoteViewModel @Inject constructor(
    private val credentials: CredentialStore,
    private val identity: AtvIdentity,
    store: TvStore,
    httpClient: OkHttpClient,
) : ViewModel() {
    val haptics: StateFlow<Boolean> = store.hapticsEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    private val _state = MutableStateFlow(NoRemoteUiState())
    val state: StateFlow<NoRemoteUiState> = _state.asStateFlow()
    private val checker = TvChecker(httpClient)
    private var host: String? = null
    private var connection: AtvRemote.Connection? = null
    private var connecting: Job? = null

    /**
     * Connects once fast keys are paired (the certificate waits under the TV's address). The TV
     * now and then drops the channel while its menus are in use (seen on the KD-65XF9005): that
     * reconnects by itself, and only reconnects that keep failing ask the user to retry.
     */
    fun connect(host: String) {
        if (connecting?.isActive == true) return
        if (this.host == host && connection?.isOpen == true) return
        this.host = host
        closeConnection()
        val certificate = credentials.atvCertificate(CredentialStore.pendingAtvKey(host)) ?: return
        connecting = viewModelScope.launch {
            _state.update { it.copy(failed = false) }
            var attempts = 0
            while (attempts < RECONNECT_ATTEMPTS) {
                val opened = try {
                    AtvRemote(identity.socketFactory(), Build.MODEL, BuildConfig.APPLICATION_ID, BuildConfig.VERSION_NAME)
                        .connect(host, certificate)
                } catch (e: Exception) {
                    DebugLog.w(e) { "No-remote key channel failed" }
                    attempts++
                    delay(RECONNECT_DELAY_MS)
                    continue
                }
                connection = opened
                attempts = 0
                _state.update { it.copy(connected = true) }
                val cause = opened.awaitClosed()
                DebugLog.w(cause) { "No-remote key channel closed; reconnecting" }
                if (connection !== opened) return@launch
                connection = null
                delay(RECONNECT_DELAY_MS)
            }
            _state.update { it.copy(connected = false, failed = true) }
        }
    }

    /** A key lost while the channel reconnects is not resent: nothing happens, and the user presses again. */
    fun press(keyCode: Int) {
        connection?.sendKey(keyCode)
    }

    /** Asks the web API whether it answers now, the same way the connection check does. */
    fun checkIpControl() {
        val host = host ?: return
        viewModelScope.launch {
            _state.update { it.copy(checking = true, stillOff = false) }
            val on = checker.webApiAnswers(host)
            _state.update { it.copy(checking = false, ipControlOn = on, stillOff = !on) }
        }
    }

    fun consumeIpControlOn() = _state.update { it.copy(ipControlOn = false) }

    /**
     * Cancelled by the user: forget the fast keys paired for this address. Not in [onCleared]: going
     * on to the PIN pairing clears this view model too, and that pairing adopts the certificate.
     */
    fun discard(host: String) = credentials.clearPendingAtvCertificate(host)

    override fun onCleared() {
        connecting?.cancel()
        closeConnection()
    }

    /** Closing sends a TLS close alert: network I/O, never on the main thread. */
    private fun closeConnection() {
        val open = connection ?: return
        connection = null
        Dispatchers.IO.asExecutor().execute { open.close() }
    }
}

/**
 * "No remote?": pair fast keys, then the way through the TV's menus as numbered steps with the
 * remote's own D-pad, then on to the usual PIN pairing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoRemoteScreen(
    tv: DiscoveredTv,
    onCancel: () -> Unit,
    onIpControlOn: (DiscoveredTv) -> Unit,
    fastKeys: FastKeysViewModel = hiltViewModel(),
    viewModel: NoRemoteViewModel = hiltViewModel(),
) {
    val pairing by fastKeys.state.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val haptics by viewModel.haptics.collectAsStateWithLifecycle()
    val cancel = { viewModel.discard(tv.host); onCancel() }
    BackHandler(onBack = cancel)
    LaunchedEffect(tv.host) { fastKeys.startUnsaved(tv.host, tv.name) }
    LaunchedEffect(pairing.step) { if (pairing.step == FastKeysStep.Done) viewModel.connect(tv.host) }
    LaunchedEffect(state.ipControlOn) {
        if (state.ipControlOn) {
            viewModel.consumeIpControlOn()
            onIpControlOn(tv.copy(webApi = true))
        }
    }
    val paired = pairing.step == FastKeysStep.Done

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.no_remote_title)) }) },
        // Done stays in reach however long the steps run (small phones, Finnish).
        bottomBar = {
            if (paired) {
                Column(
                    Modifier.fillMaxWidth().wrapContentWidth().widthIn(max = 600.dp).navigationBarsPadding().padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (state.stillOff) Text(stringResource(R.string.no_remote_still_off), color = MaterialTheme.colorScheme.error)
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.align(Alignment.End)) {
                        OutlinedButton(onClick = cancel) { Text(stringResource(R.string.pair_cancel)) }
                        Button(onClick = viewModel::checkIpControl, enabled = !state.checking) {
                            if (state.checking) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Text(stringResource(R.string.no_remote_done))
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(
            // On a tablet the steps stay at a readable width, centred.
            Modifier.padding(padding).fillMaxSize().wrapContentWidth().widthIn(max = 600.dp)
                .verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (!paired) {
                // Centred like the pairing steps under it, which other screens share.
                Text(stringResource(R.string.no_remote_intro), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                FastKeysSteps(pairing, fastKeys, cancel)
                return@Column
            }
            StepsCard(
                title = stringResource(R.string.no_remote_steps_title),
                steps = listOf(R.string.no_remote_step_open, R.string.no_remote_step_menu, R.string.no_remote_step_switch, R.string.no_remote_step_done)
                    .map { stringResource(it) },
            )
            when {
                state.connected -> Keys(haptics, viewModel::press)
                state.failed -> {
                    Text(stringResource(R.string.no_remote_lost), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                    Button(onClick = { viewModel.connect(tv.host) }) { Text(stringResource(R.string.state_retry)) }
                }
                else -> CircularProgressIndicator()
            }
        }
    }
}

/**
 * The remote's own D-pad ring with Back and Home, captioned like on the remote. No Settings key:
 * the KD-65XF9005 ignores KEYCODE_SETTINGS.
 */
@Composable
private fun Keys(haptics: Boolean, press: (Int) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        DPadRing(haptics, press = { key -> key.atvKeyCode?.let(press) }, pressRepeat = { key -> key.atvKeyCode?.let(press) })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SystemKey(stringResource(R.string.remote_back), stringResource(R.string.remote_back), Icons.AutoMirrored.Filled.Undo, haptics) { press(KeyEvent.KEYCODE_BACK) }
            SystemKey(stringResource(R.string.remote_home), stringResource(R.string.remote_home), Icons.Default.Home, haptics) { press(KeyEvent.KEYCODE_HOME) }
        }
    }
}

private const val RECONNECT_ATTEMPTS = 3
private const val RECONNECT_DELAY_MS = 1_000L
