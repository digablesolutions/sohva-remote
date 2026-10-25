package fi.digable.braviaremote.ui.pair

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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fi.digable.braviaremote.R
import fi.digable.braviaremote.protocol.AtvPairing

/** Pairs fast keys (the TV's Android TV Remote Service) with the 6-character code the TV shows. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FastKeysScreen(
    tvId: String,
    onClose: () -> Unit,
    viewModel: FastKeysViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(tvId) { viewModel.start(tvId) }

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.fast_keys_title)) }) }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (val step = state.step) {
                FastKeysStep.Connecting -> {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.fast_keys_connecting), textAlign = TextAlign.Center)
                }
                FastKeysStep.EnterCode, FastKeysStep.Confirming -> {
                    Text(stringResource(R.string.fast_keys_instructions, state.tvName), textAlign = TextAlign.Center)
                    Spacer(Modifier.height(24.dp))
                    OutlinedTextField(
                        value = state.code,
                        onValueChange = viewModel::onCodeChange,
                        label = { Text(stringResource(R.string.fast_keys_code_label)) },
                        isError = state.wrongCode,
                        supportingText = if (state.wrongCode) {
                            { Text(stringResource(R.string.fast_keys_wrong_code)) }
                        } else null,
                        singleLine = true,
                        enabled = step == FastKeysStep.EnterCode,
                        textStyle = TextStyle(fontSize = 28.sp, textAlign = TextAlign.Center, letterSpacing = 8.sp),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Characters,
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Ascii,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = { viewModel.confirm() }),
                        modifier = Modifier.width(260.dp),
                    )
                    Spacer(Modifier.height(24.dp))
                    Row {
                        OutlinedButton(onClick = onClose) { Text(stringResource(R.string.pair_cancel)) }
                        Spacer(Modifier.width(16.dp))
                        Button(
                            onClick = viewModel::confirm,
                            enabled = step == FastKeysStep.EnterCode && state.code.length == AtvPairing.CODE_LENGTH,
                        ) {
                            if (step == FastKeysStep.Confirming) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Text(stringResource(R.string.pair_button))
                            }
                        }
                    }
                }
                is FastKeysStep.Failed -> {
                    Text(
                        stringResource(R.string.fast_keys_failed, step.message ?: ""),
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(24.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        OutlinedButton(onClick = onClose) { Text(stringResource(R.string.pair_cancel)) }
                        Spacer(Modifier.width(16.dp))
                        Button(onClick = viewModel::connect) { Text(stringResource(R.string.state_retry)) }
                    }
                }
                FastKeysStep.Done -> {
                    Text(stringResource(R.string.fast_keys_done, state.tvName), textAlign = TextAlign.Center)
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = onClose) { Text(stringResource(R.string.fast_keys_close)) }
                }
            }
        }
    }
}
