package fi.digable.braviaremote.ui.remote

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import fi.digable.braviaremote.R
import fi.digable.braviaremote.protocol.AtvEvent

/**
 * Types into the text field the TV has open, with the phone's keyboard or by voice. The phone's
 * field starts from what the TV holds and then leads: each change is sent as the difference (see
 * [RemoteViewModel.typeOnTv]). The TV's echoes are not written back while typing, since each one
 * is a step behind and would fight the keyboard's own corrections.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TypeOnTvSheet(field: AtvEvent.TextField, viewModel: RemoteViewModel, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var value by remember { mutableStateOf(TextFieldValue(field.text, TextRange(field.text.length))) }
    val focus = remember { FocusRequester() }
    val context = LocalContext.current
    val canListen = remember { SpeechRecognizer.isRecognitionAvailable(context) }

    fun change(newText: String) {
        viewModel.typeOnTv(value.text, newText)
        value = TextFieldValue(newText, TextRange(newText.length))
    }

    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (result.resultCode == Activity.RESULT_OK && !spoken.isNullOrBlank()) {
            val joiner = if (value.text.isEmpty() || value.text.endsWith(" ")) "" else " "
            change(value.text + joiner + spoken)
        }
    }

    LaunchedEffect(Unit) { focus.requestFocus() }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp).imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.type_title), style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { next ->
                        if (next.text != value.text) viewModel.typeOnTv(value.text, next.text)
                        value = next
                    },
                    placeholder = { Text(field.hint.ifBlank { stringResource(R.string.type_hint) }, maxLines = 1) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { viewModel.submitOnTv(); onDismiss() }),
                    modifier = Modifier.weight(1f).focusRequester(focus),
                )
                if (canListen) {
                    FilledTonalIconButton(
                        onClick = {
                            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
                                .putExtra(RecognizerIntent.EXTRA_PROMPT, context.getString(R.string.type_speak))
                            try {
                                speech.launch(intent)
                            } catch (e: ActivityNotFoundException) {
                                // No recognizer after all; the keyboard's own mic still works.
                            }
                        },
                        modifier = Modifier.padding(top = 6.dp),
                    ) { Icon(Icons.Default.Mic, contentDescription = stringResource(R.string.type_voice)) }
                }
                FilledTonalIconButton(
                    onClick = { viewModel.submitOnTv(); onDismiss() },
                    modifier = Modifier.padding(top = 6.dp),
                ) { Icon(Icons.Default.Search, contentDescription = stringResource(R.string.type_search)) }
            }
        }
    }
}
