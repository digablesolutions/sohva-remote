package com.mothandgravel.sohva.ui.pair

import android.os.Build
import android.view.KeyEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.mothandgravel.sohva.protocol.AtvRemote
import com.mothandgravel.sohva.protocol.BraviaApi
import com.mothandgravel.sohva.protocol.DiscoveredTv
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val httpClient: OkHttpClient,
) : ViewModel() {
    private val _state = MutableStateFlow(NoRemoteUiState())
    val state: StateFlow<NoRemoteUiState> = _state.asStateFlow()
    private var host: String? = null
    private var connection: AtvRemote.Connection? = null

    /** Connects once fast keys are paired (the certificate waits under the TV's address). */
    fun connect(host: String) {
        if (this.host == host && connection != null) return
        this.host = host
        val certificate = credentials.atvCertificate(CredentialStore.pendingAtvKey(host)) ?: return
        viewModelScope.launch {
            _state.update { it.copy(failed = false) }
            try {
                val remote = AtvRemote(identity.socketFactory(), Build.MODEL, BuildConfig.APPLICATION_ID, BuildConfig.VERSION_NAME)
                connection = remote.connect(host, certificate)
                _state.update { it.copy(connected = true) }
            } catch (e: Exception) {
                DebugLog.w(e) { "No-remote key channel failed" }
                _state.update { it.copy(failed = true) }
            }
        }
    }

    fun press(keyCode: Int) {
        if (connection?.sendKey(keyCode) != true) _state.update { it.copy(connected = false, failed = true) }
    }

    /** Asks the web API whether IP control is on now. */
    fun checkIpControl() {
        val host = host ?: return
        viewModelScope.launch {
            _state.update { it.copy(checking = true, stillOff = false) }
            val on = runCatching { BraviaApi("http://$host/", httpClient).getInterfaceInformation() }.isSuccess
            _state.update { it.copy(checking = false, ipControlOn = on, stillOff = !on) }
        }
    }

    fun consumeIpControlOn() = _state.update { it.copy(ipControlOn = false) }

    override fun onCleared() {
        val open = connection ?: return
        connection = null
        // Closing sends a TLS close alert: network I/O, never on the main thread.
        Dispatchers.IO.asExecutor().execute { open.close() }
    }
}

/**
 * "No remote?": pair fast keys, then a D-pad and the way through the TV's menus to IP control,
 * then on to the usual PIN pairing.
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
    LaunchedEffect(tv.host) { fastKeys.startUnsaved(tv.host, tv.name) }
    LaunchedEffect(pairing.step) { if (pairing.step == FastKeysStep.Done) viewModel.connect(tv.host) }
    LaunchedEffect(state.ipControlOn) {
        if (state.ipControlOn) {
            viewModel.consumeIpControlOn()
            onIpControlOn(tv.copy(webApi = true))
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.no_remote_title)) }) }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().wrapContentWidth().widthIn(max = 600.dp)
                .verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (pairing.step != FastKeysStep.Done) {
                Text(stringResource(R.string.no_remote_intro), textAlign = TextAlign.Center)
                FastKeysSteps(pairing, fastKeys, onCancel)
                return@Column
            }
            Text(stringResource(R.string.no_remote_steps), style = MaterialTheme.typography.bodyLarge)
            when {
                state.connected -> DPad(viewModel::press)
                state.failed -> {
                    Text(stringResource(R.string.no_remote_lost), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                    Button(onClick = { viewModel.connect(tv.host) }) { Text(stringResource(R.string.state_retry)) }
                }
                else -> CircularProgressIndicator()
            }
            if (state.stillOff) {
                Text(stringResource(R.string.no_remote_still_off), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.pair_cancel)) }
                Button(onClick = viewModel::checkIpControl, enabled = !state.checking) {
                    if (state.checking) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text(stringResource(R.string.no_remote_done))
                }
            }
        }
    }
}

/** Just what the TV's menus need: arrows, OK, Back, Home and the Settings key. */
@Composable
private fun DPad(press: (Int) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PadKey(Icons.Default.KeyboardArrowUp, R.string.remote_up) { press(KeyEvent.KEYCODE_DPAD_UP) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PadKey(Icons.AutoMirrored.Filled.KeyboardArrowLeft, R.string.remote_left) { press(KeyEvent.KEYCODE_DPAD_LEFT) }
            FilledTonalButton(onClick = { press(KeyEvent.KEYCODE_DPAD_CENTER) }, modifier = Modifier.size(72.dp)) {
                Text(stringResource(R.string.remote_ok))
            }
            PadKey(Icons.AutoMirrored.Filled.KeyboardArrowRight, R.string.remote_right) { press(KeyEvent.KEYCODE_DPAD_RIGHT) }
        }
        PadKey(Icons.Default.KeyboardArrowDown, R.string.remote_down) { press(KeyEvent.KEYCODE_DPAD_DOWN) }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth().wrapContentWidth()) {
            PadKey(Icons.AutoMirrored.Filled.ArrowBack, R.string.remote_back) { press(KeyEvent.KEYCODE_BACK) }
            PadKey(Icons.Default.Home, R.string.remote_home) { press(KeyEvent.KEYCODE_HOME) }
            PadKey(Icons.Default.Settings, R.string.no_remote_settings_key) { press(KeyEvent.KEYCODE_SETTINGS) }
        }
    }
}

@Composable
private fun PadKey(icon: ImageVector, label: Int, onClick: () -> Unit) {
    FilledTonalIconButton(onClick = onClick, modifier = Modifier.size(64.dp)) {
        Icon(icon, contentDescription = stringResource(label), modifier = Modifier.width(32.dp).height(32.dp))
    }
}
