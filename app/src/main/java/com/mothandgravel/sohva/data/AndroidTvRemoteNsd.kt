package com.mothandgravel.sohva.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import com.mothandgravel.sohva.protocol.DiscoveredTv
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.concurrent.Executors

/**
 * Finds TVs by their Android TV Remote service (mDNS `_androidtvremote2._tcp`), which every
 * Android TV advertises with no setting to switch on. It finds a Bravia whose IP control is off,
 * where SSDP for the web API stays silent: the TV a user without the physical remote can't fix.
 */
class AndroidTvRemoteNsd(context: Context) {
    private val nsd = context.applicationContext.getSystemService(NsdManager::class.java)

    fun discover(): Flow<DiscoveredTv> = callbackFlow {
        val manager = nsd ?: run { close(); return@callbackFlow }
        val resolving = Executors.newSingleThreadExecutor()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(service: NsdServiceInfo) {
                resolve(manager, service, resolving) { resolved ->
                    val host = resolved.hostAddressCompat() ?: return@resolve
                    trySend(DiscoveredTv(host = host, name = resolved.serviceName, model = ""))
                }
            }
            override fun onServiceLost(service: NsdServiceInfo) = Unit
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                DebugLog.w { "mDNS discovery failed to start: $errorCode" }
                close()
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        awaitClose {
            runCatching { manager.stopServiceDiscovery(listener) }
            resolving.shutdown()
        }
    }

    /**
     * Before Android 14 one NsdManager resolves one service at a time and fails the rest with
     * FAILURE_ALREADY_ACTIVE, so resolves wait for each other on one thread.
     */
    @Suppress("DEPRECATION")
    private fun resolve(manager: NsdManager, service: NsdServiceInfo, queue: java.util.concurrent.ExecutorService, onResolved: (NsdServiceInfo) -> Unit) {
        queue.execute {
            val done = java.util.concurrent.CountDownLatch(1)
            manager.resolveService(service, object : NsdManager.ResolveListener {
                override fun onServiceResolved(info: NsdServiceInfo) {
                    onResolved(info)
                    done.countDown()
                }
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    DebugLog.d { "mDNS resolve failed: $errorCode" }
                    done.countDown()
                }
            })
            done.await(RESOLVE_TIMEOUT_S, java.util.concurrent.TimeUnit.SECONDS)
        }
    }

    @Suppress("DEPRECATION")
    private fun NsdServiceInfo.hostAddressCompat(): String? {
        val address = if (Build.VERSION.SDK_INT >= 34) hostAddresses.firstOrNull { it is java.net.Inet4Address } else host
        return (address as? java.net.Inet4Address)?.hostAddress
    }

    companion object {
        const val SERVICE_TYPE = "_androidtvremote2._tcp"
        private const val RESOLVE_TIMEOUT_S = 3L
    }
}
