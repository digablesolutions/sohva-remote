package com.mothandgravel.sohva.ui.pair

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import com.mothandgravel.sohva.data.AtvIdentity
import com.mothandgravel.sohva.data.CredentialStore
import com.mothandgravel.sohva.data.DeviceName
import com.mothandgravel.sohva.data.DebugLog
import com.mothandgravel.sohva.data.SavedTv
import com.mothandgravel.sohva.data.TvStore
import com.mothandgravel.sohva.protocol.AtvPairing
import com.mothandgravel.sohva.protocol.BraviaException
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

    /** Where the TV's certificate is kept: the saved TV's id, or a holding place before there is one. */
    private var certificateKey: String? = null
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
            certificateKey = saved.id
            _state.update { it.copy(tvName = saved.name) }
            connect()
        }
    }

    /**
     * Pairs a TV that is not saved yet because it refuses the web API pairing (IP control off). The
     * certificate waits under [CredentialStore.pendingAtvKey] until the TV is saved.
     */
    fun startUnsaved(host: String, name: String) {
        if (tv != null) return
        tv = SavedTv(id = CredentialStore.pendingAtvKey(host), name = name, model = "", host = host)
        certificateKey = CredentialStore.pendingAtvKey(host)
        _state.update { it.copy(tvName = name) }
        connect()
    }

    /** Opens a pairing connection; the TV then shows a new code. */
    fun connect() {
        val tv = tv ?: return
        closeSession()
        viewModelScope.launch {
            _state.update { it.copy(step = FastKeysStep.Connecting, code = "") }
            try {
                val pairing = withContext(Dispatchers.IO) {
                    identity.ensureOwnCertificate()
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
                credentials.saveAtvCertificate(certificateKey ?: tv.id, session.serverCertificate.encoded)
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
