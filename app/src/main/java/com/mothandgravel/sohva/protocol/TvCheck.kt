package com.mothandgravel.sohva.protocol

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.net.Socket

/**
 * What a connection check found out about one TV. Each fact is null when it could not be asked,
 * e.g. nothing past "answers" when the TV does not answer at all.
 */
data class TvCheck(
    /** The TV's web API answered (Settings › Network › Home network setup › IP control). */
    val ipControl: Boolean,
    /** The Android TV Remote service answered (fast keys), which works even with IP control off. */
    val androidTvRemote: Boolean,
    val power: PowerStatus?,
    /** Settings › Network › Remote device settings › Control remotely. */
    val controlRemotely: Boolean?,
    /** The saved pairing still works; null when there is none to try. */
    val paired: Boolean?,
    /** Remote start: the TV can be switched on from standby. Only readable once paired. */
    val remoteStart: Boolean?,
) {
    /** Something answers at that address: the TV is on the network, though maybe not listening to us. */
    val answers: Boolean get() = ipControl || androidTvRemote
}

/**
 * Asks a TV, step by step, what stands between it and the app. Everything here is a read; nothing
 * changes on the TV. The steps that need no pairing come first, so a TV that refuses the app can
 * still say why.
 */
class TvChecker(
    private val httpClient: OkHttpClient = BraviaApi.defaultClient(),
    private val portOpen: suspend (host: String, port: Int) -> Boolean = ::tcpPortOpen,
) {
    suspend fun check(host: String, auth: BraviaAuth?): TvCheck = coroutineScope {
        val remote = async { portOpen(host, AtvRemote.PORT) }
        val api = BraviaApi("http://$host/", httpClient)
        val ipControl = answers { api.getInterfaceInformation() }
        if (!ipControl) {
            return@coroutineScope TvCheck(ipControl = false, androidTvRemote = remote.await(), power = null, controlRemotely = null, paired = null, remoteStart = null)
        }
        val power = runCatching { api.getPowerStatus() }.getOrNull()
        val controlRemotely = runCatching { api.getControlRemotely() }.getOrNull()
        var paired: Boolean? = null
        var remoteStart: Boolean? = null
        if (auth != null) {
            api.auth = auth
            try {
                remoteStart = api.getRemoteStart()
                paired = true
            } catch (_: BraviaException.Unauthorized) {
                paired = false
            } catch (_: BraviaException) {
                // Paired, but this TV does not report Remote start (or not in standby): ask a call it has.
                paired = runCatching { api.getSystemInformation() }.exceptionOrNull() !is BraviaException.Unauthorized
            }
        }
        TvCheck(ipControl = true, androidTvRemote = remote.await(), power = power, controlRemotely = controlRemotely, paired = paired, remoteStart = remoteStart)
    }

    /** True when the TV's web API answered at all, even with an error: only silence means no. */
    private suspend fun answers(block: suspend () -> Unit): Boolean = try {
        block()
        true
    } catch (_: BraviaException.Unreachable) {
        false
    } catch (_: BraviaException.Protocol) {
        // Something answered HTTP, but not a Bravia's web API (IP control off, or another device).
        false
    } catch (_: BraviaException) {
        true
    }
}

private suspend fun tcpPortOpen(host: String, port: Int): Boolean = withContext(Dispatchers.IO) {
    try {
        Socket().use { it.connect(InetSocketAddress(host, port), PORT_TIMEOUT_MS) }
        true
    } catch (_: Exception) {
        false
    }
}

private const val PORT_TIMEOUT_MS = 1500
