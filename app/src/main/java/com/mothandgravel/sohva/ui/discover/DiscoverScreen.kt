package com.mothandgravel.sohva.ui.discover

import androidx.compose.material.icons.filled.Info
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mothandgravel.sohva.R
import com.mothandgravel.sohva.ui.StepsCard
import com.mothandgravel.sohva.protocol.DiscoveredTv

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(
    canGoBack: Boolean,
    onBack: () -> Unit,
    onTvChosen: (DiscoveredTv) -> Unit,
    viewModel: DiscoverViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.probed) {
        state.probed?.let { tv ->
            viewModel.consumeProbed()
            onTvChosen(tv)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.discover_title)) },
                navigationIcon = {
                    if (canGoBack) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.remote_back))
                        }
                    }
                },
            )
        },
    ) { padding ->
        // On a tablet the list stays at a readable width, centred.
        LazyColumn(Modifier.padding(padding).fillMaxWidth().wrapContentWidth().widthIn(max = 600.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
            if (state.wifiOff) {
                item { Text(stringResource(R.string.discover_wifi_off), color = MaterialTheme.colorScheme.error) }
                item { Spacer(Modifier.height(12.dp)) }
            }
            items(state.found, key = { it.host }) { tv ->
                ListItem(
                    headlineContent = { Text(tv.name) },
                    supportingContent = {
                        Column {
                            Text(stringResource(R.string.discover_tv_model, tv.model.ifBlank { "Bravia" }, tv.host))
                            if (!tv.webApi) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Text(stringResource(R.string.discover_ip_control_off))
                                }
                            }
                        }
                    },
                    leadingContent = { Icon(Icons.Default.Tv, contentDescription = null) },
                    modifier = Modifier.clickable { onTvChosen(tv) },
                )
            }
            item {
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (state.searching) {
                        CircularProgressIndicator(Modifier.width(24.dp).height(24.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.discover_searching))
                    } else {
                        if (state.found.isEmpty()) {
                            Text(stringResource(R.string.discover_none_found), Modifier.weight(1f))
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                        Spacer(Modifier.width(12.dp))
                        OutlinedButton(onClick = viewModel::search) { Text(stringResource(R.string.discover_search_again)) }
                    }
                }
            }
            item { TvSetupSteps() }
            item {
                Spacer(Modifier.height(24.dp))
                Text(stringResource(R.string.discover_manual_title), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Column {
                    OutlinedTextField(
                        value = state.manualIp,
                        onValueChange = viewModel::onManualIpChange,
                        placeholder = { Text(stringResource(R.string.discover_manual_hint)) },
                        isError = state.manualError,
                        supportingText = if (state.manualError) {
                            { Text(stringResource(R.string.discover_manual_invalid)) }
                        } else null,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { viewModel.addManual() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = viewModel::addManual, enabled = !state.probing, modifier = Modifier.align(Alignment.End)) {
                        Text(stringResource(R.string.discover_manual_add))
                    }
                }
            }
        }
    }
}

/**
 * How setup goes: usually just the code, and what to do when the TV is not found or refuses. A
 * first-time user, or anyone whose TV is not found, otherwise has no way to know.
 */
@Composable
private fun TvSetupSteps() {
    StepsCard(
        title = stringResource(R.string.setup_title),
        steps = listOf(R.string.setup_step_menu, R.string.setup_step_auth, R.string.setup_step_start).map { stringResource(it) },
        footer = stringResource(R.string.setup_footer),
    )
}
