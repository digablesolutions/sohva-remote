package com.mothandgravel.sohva.ui.pair

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import com.mothandgravel.sohva.data.CredentialStore
import com.mothandgravel.sohva.data.SavedTv
import com.mothandgravel.sohva.data.TvController
import com.mothandgravel.sohva.data.TvStore
import com.mothandgravel.sohva.protocol.BraviaApi
import com.mothandgravel.sohva.protocol.BraviaAuth
import com.mothandgravel.sohva.protocol.BraviaException
import com.mothandgravel.sohva.protocol.DiscoveredTv
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.UUID
import javax.inject.Inject

sealed interface PairStep {
    data object Requesting : PairStep
    data object EnterPin : PairStep
    data object Confirming : PairStep
    data class Failed(val message: String?, val notSupported: Boolean = false) : PairStep
    data object Done : PairStep
}

data class PairUiState(
    val step: PairStep = PairStep.Requesting,
    val pin: String = "",
    val wrongPin: Boolean = false,
)

@HiltViewModel
class PairViewModel @Inject constructor(
    private val store: TvStore,
    private val credentials: CredentialStore,
    private val controller: TvController,
    httpClient: OkHttpClient,
) : ViewModel() {
    private val _state = MutableStateFlow(PairUiState())
    val state: StateFlow<PairUiState> = _state.asStateFlow()

    private lateinit var tv: DiscoveredTv
    private val api: BraviaApi by lazy { BraviaApi("http://${tv.host}/", httpClient) }
    private val pairing by lazy { controller.pairing(api) }

    fun start(tv: DiscoveredTv) {
        if (this::tv.isInitialized) return
        this.tv = tv
        requestPin()
    }

    fun requestPin() {
        viewModelScope.launch {
            _state.update { it.copy(step = PairStep.Requesting, wrongPin = false, pin = "") }
            try {
                pairing.requestPin()
                _state.update { it.copy(step = PairStep.EnterPin) }
            } catch (e: BraviaException.Unreachable) {
                _state.update { it.copy(step = PairStep.Failed(e.message)) }
            } catch (e: BraviaException) {
                _state.update { it.copy(step = PairStep.Failed(e.message, notSupported = true)) }
            } catch (e: Exception) {
                _state.update { it.copy(step = PairStep.Failed(e.message)) }
            }
        }
    }

    fun onPinChange(value: String) {
        val digits = value.filter(Char::isDigit).take(4)
        _state.update { it.copy(pin = digits, wrongPin = false) }
    }

    fun confirm() {
        val pin = _state.value.pin
        if (pin.length != 4) return
        viewModelScope.launch {
            _state.update { it.copy(step = PairStep.Confirming) }
            try {
                val result = pairing.confirmPin(pin)
                api.auth = BraviaAuth.Cookie(result.cookie)
                val info = runCatching { api.getSystemInformation() }.getOrNull()
                val existing = store.savedTvs.first().firstOrNull {
                    (tv.uuid != null && it.uuid == tv.uuid) || it.host == tv.host
                }
                val saved = SavedTv(
                    id = existing?.id ?: tv.uuid ?: UUID.randomUUID().toString(),
                    name = info?.name?.ifBlank { null } ?: tv.name,
                    model = info?.model?.ifBlank { null } ?: tv.model,
                    host = tv.host,
                    mac = info?.macAddr?.ifBlank { null } ?: existing?.mac,
                    uuid = tv.uuid ?: existing?.uuid,
                )
                credentials.saveCookie(saved.id, CredentialStore.Cookie(result.cookie, result.expiresAtEpochMs))
                runCatching { api.getRemoteControllerInfo() }.getOrNull()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { credentials.saveIrccCodes(saved.id, it) }
                store.upsert(saved, makeActive = true)
                controller.refresh()
                _state.update { it.copy(step = PairStep.Done) }
            } catch (e: BraviaException.WrongPin) {
                _state.update { it.copy(step = PairStep.EnterPin, wrongPin = true, pin = "") }
            } catch (e: Exception) {
                _state.update { it.copy(step = PairStep.Failed(e.message)) }
            }
        }
    }
}
