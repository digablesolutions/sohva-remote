package com.mothandgravel.sohva.data

import android.content.Context
import android.net.wifi.WifiManager
import dagger.hilt.android.qualifiers.ApplicationContext
import com.mothandgravel.sohva.protocol.DiscoveredTv
import com.mothandgravel.sohva.protocol.SsdpDiscovery
import com.mothandgravel.sohva.protocol.TvChecker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SSDP discovery (the TV's web API) plus mDNS (its Android TV Remote service). SSDP needs the Wi-Fi
 * multicast lock to receive replies; NsdManager does not. A TV found only by mDNS has its web API off
 * ([DiscoveredTv.webApi] false).
 */
@Singleton
class AndroidDiscovery @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ssdp: SsdpDiscovery,
) {
    private val nsd = AndroidTvRemoteNsd(context)

    private val wifi: WifiManager? get() = context.applicationContext.getSystemService(WifiManager::class.java)

    val isWifiEnabled: Boolean get() = wifi?.isWifiEnabled ?: true

    fun discover(timeoutMs: Long = 4000): Flow<DiscoveredTv> = channelFlow {
        val lock = wifi?.createMulticastLock("bravia-ssdp")?.apply {
            setReferenceCounted(false)
            acquire()
        }
        try {
            launch {
                withTimeoutOrNull(timeoutMs) {
                    // Every Android TV and Chromecast advertises this service: keep the Sony ones.
                    nsd.discover().collect { found -> ssdp.sonyCastDevice(found.host)?.let { send(it.copy(webApi = false)) } }
                }
            }
            ssdp.discover(timeoutMs).collect { send(it) }
        } finally {
            runCatching { lock?.release() }
        }
    }

    suspend fun probe(host: String): DiscoveredTv? = ssdp.probe(host)

    suspend fun fastKeysAnswer(host: String): Boolean = TvChecker().fastKeysAnswer(host)

    /** Looks for a TV by UUID after its IP changed. */
    suspend fun findByUuid(uuid: String, timeoutMs: Long = 4000): DiscoveredTv? {
        var found: DiscoveredTv? = null
        discover(timeoutMs).onCompletion { }.collect { tv ->
            if (found == null && tv.uuid == uuid) found = tv
        }
        return found
    }
}
