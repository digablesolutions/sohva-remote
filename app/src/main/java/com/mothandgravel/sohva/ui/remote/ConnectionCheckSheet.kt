package com.mothandgravel.sohva.ui.remote

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.mothandgravel.sohva.R
import com.mothandgravel.sohva.data.CredentialStore
import com.mothandgravel.sohva.data.DebugLog
import com.mothandgravel.sohva.data.LocalNetwork
import com.mothandgravel.sohva.data.SavedTv
import com.mothandgravel.sohva.protocol.BraviaApi
import com.mothandgravel.sohva.protocol.BraviaAuth
import com.mothandgravel.sohva.protocol.TvChecker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

data class ConnectionCheckUiState(
    val running: Boolean = true,
    val lines: List<CheckLine> = emptyList(),
    /** A fix the app tried itself (Remote start) and the TV refused. */
    val fixFailed: Boolean = false,
)

/** Runs [TvChecker] for one saved TV and applies the fixes the app can make by itself. */
@HiltViewModel
class ConnectionCheckViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val credentials: CredentialStore,
    private val httpClient: OkHttpClient,
) : ViewModel() {
    private val _state = MutableStateFlow(ConnectionCheckUiState())
    val state: StateFlow<ConnectionCheckUiState> = _state.asStateFlow()
    private val checker = TvChecker(httpClient)
    private var tv: SavedTv? = null
    private var checking: Job? = null

    /** Every opening checks afresh, at the TV's current address: an earlier result may be stale. */
    fun start(tv: SavedTv) {
        this.tv = tv
        _state.value = ConnectionCheckUiState()
        recheck()
    }

    fun recheck() {
        val tv = tv ?: return
        checking?.cancel()
        checking = viewModelScope.launch {
            _state.update { it.copy(running = true) }
            val check = checker.check(tv.host, auth(tv))
            val lines = checkLines(LocalNetwork.current(context), check, credentials.atvCertificate(tv.id) != null)
            _state.update { it.copy(running = false, lines = lines) }
        }
    }

    fun turnOnRemoteStart() {
        val tv = tv ?: return
        val auth = auth(tv) ?: return
        viewModelScope.launch {
            _state.update { it.copy(running = true, fixFailed = false) }
            val ok = runCatching { BraviaApi("http://${tv.host}/", httpClient, auth).setRemoteStart(true) }
                .onFailure { DebugLog.w(it) { "Turning on Remote start failed" } }
                .isSuccess
            _state.update { it.copy(fixFailed = !ok) }
            recheck()
        }
    }

    private fun auth(tv: SavedTv): BraviaAuth? = credentials.cookie(tv.id)?.let { BraviaAuth.Cookie(it.value) }
}

/**
 * "Check connection": the steps between this phone and the TV, each with a mark and, for a problem,
 * what to do about it. Fixes the app can make itself get a button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionCheckSheet(
    tv: SavedTv,
    onPairAgain: (SavedTv) -> Unit,
    onFastKeys: (SavedTv) -> Unit,
    onNoRemote: (SavedTv) -> Unit,
    onDismiss: () -> Unit,
    viewModel: ConnectionCheckViewModel = hiltViewModel(),
) {
    LaunchedEffect(tv) { viewModel.start(tv) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            // On a tablet the steps stay at a readable width, centred.
            Modifier.fillMaxWidth().wrapContentWidth().widthIn(max = 600.dp).padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column {
                Text(stringResource(R.string.check_title, tv.name), style = MaterialTheme.typography.titleMedium)
                Text(tv.host, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            state.lines.forEach { line ->
                CheckRow(line, enabled = !state.running) { fix ->
                    when (fix) {
                        CheckFix.PAIR_AGAIN -> { onDismiss(); onPairAgain(tv) }
                        CheckFix.SET_UP_FAST_KEYS -> { onDismiss(); onFastKeys(tv) }
                        CheckFix.USE_PHONE_AS_REMOTE -> { onDismiss(); onNoRemote(tv) }
                        CheckFix.TURN_ON_REMOTE_START -> viewModel.turnOnRemoteStart()
                    }
                }
            }
            if (state.fixFailed) {
                Text(stringResource(R.string.check_fix_failed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = viewModel::recheck, enabled = !state.running) { Text(stringResource(R.string.check_again)) }
                Spacer(Modifier.width(12.dp))
                if (state.running) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        }
    }
}

@Composable
private fun CheckRow(line: CheckLine, enabled: Boolean, onFix: (CheckFix) -> Unit) {
    // Green for fine, as on the power key; amber stays for things to press.
    val (icon, tint) = when (line.mark) {
        CheckMark.OK -> Icons.Default.CheckCircle to MaterialTheme.colorScheme.tertiary
        CheckMark.PROBLEM -> Icons.Default.Error to MaterialTheme.colorScheme.error
        CheckMark.NOTE -> Icons.Default.Info to MaterialTheme.colorScheme.onSurfaceVariant
    }
    val status = stringResource(
        when (line.mark) {
            CheckMark.OK -> R.string.check_mark_ok
            CheckMark.PROBLEM -> R.string.check_mark_problem
            CheckMark.NOTE -> R.string.check_mark_note
        },
    )
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.padding(top = 2.dp).size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            // TalkBack reads the step with its result, which the icon alone shows by colour.
            Column(Modifier.semantics(mergeDescendants = true) { stateDescription = status }) {
                Text(stringResource(line.title), style = MaterialTheme.typography.bodyLarge)
                line.detail?.let {
                    Text(stringResource(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            line.fix?.let { fix ->
                TextButton(onClick = { onFix(fix) }, enabled = enabled, contentPadding = PaddingValues(0.dp)) {
                    Text(
                        stringResource(
                            when (fix) {
                                CheckFix.PAIR_AGAIN -> R.string.state_pair_again
                                CheckFix.SET_UP_FAST_KEYS -> R.string.fast_keys_setup
                                CheckFix.TURN_ON_REMOTE_START -> R.string.check_remote_start_turn_on
                                CheckFix.USE_PHONE_AS_REMOTE -> R.string.no_remote_button
                            },
                        ),
                    )
                }
            }
        }
    }
}
