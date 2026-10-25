package fi.digable.braviaremote.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Why the phone may not reach a TV that does not answer, so the message can name the likely cause
 * instead of always blaming the Wi-Fi (a competitor complaint: "not on the same Wi-Fi" for a wired
 * TV, or a VPN that silently blocked the LAN).
 */
enum class LocalNetwork {
    /** On Wi-Fi or Ethernet without a VPN: the TV itself is the likely cause (off at the wall, other network). */
    OK,
    NO_NETWORK,
    MOBILE_ONLY,
    VPN;

    companion object {
        fun classify(connected: Boolean, wifi: Boolean, ethernet: Boolean, vpn: Boolean): LocalNetwork = when {
            !connected -> NO_NETWORK
            vpn -> VPN
            !wifi && !ethernet -> MOBILE_ONLY
            else -> OK
        }

        fun current(context: Context): LocalNetwork {
            val manager = context.getSystemService(ConnectivityManager::class.java) ?: return OK
            val caps = manager.activeNetwork?.let(manager::getNetworkCapabilities)
            return classify(
                connected = caps != null,
                wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true,
                ethernet = caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true,
                vpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true,
            )
        }
    }
}
