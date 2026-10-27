package com.mothandgravel.sohva.data

import android.content.Context
import android.net.wifi.WifiManager
import dagger.hilt.android.qualifiers.ApplicationContext
import com.mothandgravel.sohva.protocol.DiscoveredTv
import com.mothandgravel.sohva.protocol.SsdpDiscovery
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import javax.inject.Inject
import javax.inject.Singleton

/** SSDP discovery with the Wi-Fi multicast lock Android needs to receive multicast replies. */
@Singleton
class AndroidDiscovery @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ssdp: SsdpDiscovery,
) {
    private val wifi: WifiManager? get() = context.applicationContext.getSystemService(WifiManager::class.java)

    val isWifiEnabled: Boolean get() = wifi?.isWifiEnabled ?: true

    fun discover(timeoutMs: Long = 4000): Flow<DiscoveredTv> = flow {
        val lock = wifi?.createMulticastLock("bravia-ssdp")?.apply {
            setReferenceCounted(false)
            acquire()
        }
        try {
            ssdp.discover(timeoutMs).collect { emit(it) }
        } finally {
            runCatching { lock?.release() }
        }
    }

    suspend fun probe(host: String): DiscoveredTv? = ssdp.probe(host)

    /** Looks for a TV by UUID after its IP changed. */
    suspend fun findByUuid(uuid: String, timeoutMs: Long = 4000): DiscoveredTv? {
        var found: DiscoveredTv? = null
        discover(timeoutMs).onCompletion { }.collect { tv ->
            if (found == null && tv.uuid == uuid) found = tv
        }
        return found
    }
}
