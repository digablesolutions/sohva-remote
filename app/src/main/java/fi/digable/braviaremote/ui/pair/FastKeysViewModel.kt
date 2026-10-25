package fi.digable.braviaremote.ui.pair

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fi.digable.braviaremote.data.AtvIdentity
import fi.digable.braviaremote.data.CredentialStore
import fi.digable.braviaremote.data.DeviceName
import fi.digable.braviaremote.data.DebugLog
import fi.digable.braviaremote.data.SavedTv
import fi.digable.braviaremote.data.TvStore
import fi.digable.braviaremote.protocol.AtvPairing
import fi.digable.braviaremote.protocol.BraviaException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

sealed interface FastKeysStep {
    data object Connecting : FastKeysStep
    data object EnterCode : FastKeysStep
    data object Confirming : FastKeysStep
    data class Failed(val message: String?) : FastKeysStep
    data object Done : FastKeysStep
}

data class FastKeysUiState(
    val tvName: String = "",
    val step: FastKeysStep = FastKeysStep.Connecting,
    val code: String = "",
    val wrongCode: Boolean = false,
)

/** Pairs the phone with the TV's Android TV Remote Service, which carries the fast keys. */
@HiltViewModel
class FastKeysViewModel @Inject constructor(
    private val store: TvStore,
    private val credentials: CredentialStore,
    private val identity: AtvIdentity,
) : ViewModel() {
    private val _state = MutableStateFlow(FastKeysUiState())
    val state: StateFlow<FastKeysUiState> = _state.asStateFlow()

    private var tv: SavedTv? = null
    private var session: AtvPairing.Session? = null

    fun start(tvId: String) {
        if (tv != null) return
        viewModelScope.launch {
            val saved = store.savedTvs.first().firstOrNull { it.id == tvId }
            if (saved == null) {
                _state.update { it.copy(step = FastKeysStep.Failed(null)) }
                return@launch
            }
            tv = saved
            _state.update { it.copy(tvName = saved.name) }
            connect()
        }
    }

    /** Opens a pairing connection; the TV then shows a new code. */
    fun connect() {
        val tv = tv ?: return
        closeSession()
        viewModelScope.launch {
            _state.update { it.copy(step = FastKeysStep.Connecting, code = "") }
            try {
                val pairing = withContext(Dispatchers.IO) {
                    AtvPairing(identity.socketFactory(), identity.publicKey, DeviceName.forTv)
                }
                session = pairing.start(tv.host)
                _state.update { it.copy(step = FastKeysStep.EnterCode) }
            } catch (e: Exception) {
                DebugLog.w(e) { "Android TV Remote pairing failed to start" }
                _state.update { it.copy(step = FastKeysStep.Failed(e.message)) }
            }
        }
    }

    fun onCodeChange(value: String) {
        val code = value.uppercase().filter { it in '0'..'9' || it in 'A'..'F' }.take(AtvPairing.CODE_LENGTH)
        _state.update { it.copy(code = code, wrongCode = false) }
    }

    fun confirm() {
        val tv = tv ?: return
        val session = session ?: return
        val code = _state.value.code
        if (code.length != AtvPairing.CODE_LENGTH) return
        viewModelScope.launch {
            _state.update { it.copy(step = FastKeysStep.Confirming) }
            try {
                session.confirm(code)
                credentials.saveAtvCertificate(tv.id, session.serverCertificate.encoded)
                this@FastKeysViewModel.session = null
                _state.update { it.copy(step = FastKeysStep.Done) }
            } catch (e: BraviaException.WrongPin) {
                // A typo caught on the phone keeps the session; a code the TV refused needs a new one.
                _state.update { it.copy(step = FastKeysStep.EnterCode, wrongCode = true, code = "") }
                if (!session.isOpen) connect()
            } catch (e: Exception) {
                DebugLog.w(e) { "Android TV Remote pairing failed" }
                _state.update { it.copy(step = FastKeysStep.Failed(e.message)) }
            }
        }
    }

    override fun onCleared() = closeSession()

    /** Closing a TLS socket sends a close alert, which is network I/O: never on the main thread. */
    private fun closeSession() {
        val open = session ?: return
        session = null
        Dispatchers.IO.asExecutor().execute { open.close() }
    }
}
