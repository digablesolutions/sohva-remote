package fi.digable.braviaremote.ui.pair

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fi.digable.braviaremote.R
import fi.digable.braviaremote.protocol.DiscoveredTv

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairScreen(
    tv: DiscoveredTv,
    onCancel: () -> Unit,
    onPaired: () -> Unit,
    viewModel: PairViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(tv) { viewModel.start(tv) }
    LaunchedEffect(state.step) { if (state.step is PairStep.Done) onPaired() }

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.pair_title, tv.name)) }) }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (val step = state.step) {
                PairStep.Requesting -> {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.pair_requesting))
                }
                PairStep.EnterPin, PairStep.Confirming -> {
                    Text(stringResource(R.string.pair_instructions), textAlign = TextAlign.Center)
                    Spacer(Modifier.height(24.dp))
                    OutlinedTextField(
                        value = state.pin,
                        onValueChange = viewModel::onPinChange,
                        label = { Text(stringResource(R.string.pair_pin_label)) },
                        isError = state.wrongPin,
                        supportingText = if (state.wrongPin) {
                            { Text(stringResource(R.string.pair_wrong_pin)) }
                        } else null,
                        singleLine = true,
                        enabled = step == PairStep.EnterPin,
                        textStyle = TextStyle(fontSize = 32.sp, textAlign = TextAlign.Center, letterSpacing = 12.sp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { viewModel.confirm() }),
                        modifier = Modifier.width(220.dp),
                    )
                    Spacer(Modifier.height(24.dp))
                    Row {
                        OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.pair_cancel)) }
                        Spacer(Modifier.width(16.dp))
                        Button(onClick = viewModel::confirm, enabled = step == PairStep.EnterPin && state.pin.length == 4) {
                            if (step == PairStep.Confirming) {
                                CircularProgressIndicator(Modifier.height(18.dp).width(18.dp), strokeWidth = 2.dp)
                            } else {
                                Text(stringResource(R.string.pair_button))
                            }
                        }
                    }
                }
                is PairStep.Failed -> {
                    Text(
                        if (step.notSupported) stringResource(R.string.pair_not_supported)
                        else stringResource(R.string.pair_failed, step.message ?: ""),
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(24.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
                        OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.pair_cancel)) }
                        Spacer(Modifier.width(16.dp))
                        Button(onClick = viewModel::requestPin) { Text(stringResource(R.string.state_retry)) }
                    }
                }
                PairStep.Done -> Unit
            }
        }
    }
}
