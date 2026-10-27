package com.mothandgravel.sohva.ui.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import com.mothandgravel.sohva.data.AndroidDiscovery
import com.mothandgravel.sohva.data.DebugLog
import com.mothandgravel.sohva.protocol.DiscoveredTv
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DiscoverUiState(
    val searching: Boolean = false,
    val wifiOff: Boolean = false,
    val found: List<DiscoveredTv> = emptyList(),
    val manualIp: String = "",
    val manualError: Boolean = false,
    val probing: Boolean = false,
    /** Set when a manual probe succeeds; the screen navigates and clears it. */
    val probed: DiscoveredTv? = null,
)

@HiltViewModel
class DiscoverViewModel @Inject constructor(
    private val discovery: AndroidDiscovery,
) : ViewModel() {
    private val _state = MutableStateFlow(DiscoverUiState())
    val state: StateFlow<DiscoverUiState> = _state.asStateFlow()
    private var searchJob: Job? = null

    init {
        search()
    }

    fun search() {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _state.update { it.copy(searching = true, found = emptyList(), wifiOff = !discovery.isWifiEnabled) }
            try {
                discovery.discover(timeoutMs = 5000).collect { tv ->
                    _state.update { s -> s.copy(found = merge(s.found, tv)) }
                }
            } catch (e: Exception) {
                // No network or socket failure: the manual entry path is still offered.
                DebugLog.w(e) { "Discovery failed" }
            } finally {
                _state.update { it.copy(searching = false) }
            }
        }
    }

    fun onManualIpChange(value: String) {
        _state.update { it.copy(manualIp = value, manualError = false) }
    }

    fun addManual() {
        val ip = _state.value.manualIp.trim()
        if (!isIpv4(ip)) {
            _state.update { it.copy(manualError = true) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(probing = true) }
            val tv = discovery.probe(ip) ?: DiscoveredTv(host = ip, name = "Bravia at $ip", model = "", webApi = false)
            _state.update { it.copy(probing = false, probed = tv) }
        }
    }

    fun consumeProbed() {
        _state.update { it.copy(probed = null) }
    }

    companion object {
        /**
         * Adds a TV found by one search to the others' finds. The same TV can answer both: its SSDP
         * answer has the model and UUID, and means IP control is on, so it replaces an mDNS one.
         */
        fun merge(found: List<DiscoveredTv>, tv: DiscoveredTv): List<DiscoveredTv> {
            val i = found.indexOfFirst { it.host == tv.host }
            return when {
                i < 0 -> found + tv
                tv.webApi && !found[i].webApi -> found.toMutableList().apply { set(i, tv) }
                else -> found
            }
        }

        fun isIpv4(s: String): Boolean {
            val parts = s.split('.')
            if (parts.size != 4) return false
            return parts.all { p -> p.isNotEmpty() && p.length <= 3 && p.all(Char::isDigit) && p.toInt() in 0..255 }
        }
    }
}
