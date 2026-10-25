package fi.digable.braviaremote.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LocalNetworkTest {
    @Test
    fun `the likely cause is named`() {
        assertThat(LocalNetwork.classify(connected = false, wifi = false, ethernet = false, vpn = false)).isEqualTo(LocalNetwork.NO_NETWORK)
        assertThat(LocalNetwork.classify(connected = true, wifi = false, ethernet = false, vpn = false)).isEqualTo(LocalNetwork.MOBILE_ONLY)
        assertThat(LocalNetwork.classify(connected = true, wifi = true, ethernet = false, vpn = true)).isEqualTo(LocalNetwork.VPN)
    }

    @Test
    fun `wifi or a wired network without a vpn is fine, so the tv is the likely cause`() {
        assertThat(LocalNetwork.classify(connected = true, wifi = true, ethernet = false, vpn = false)).isEqualTo(LocalNetwork.OK)
        assertThat(LocalNetwork.classify(connected = true, wifi = false, ethernet = true, vpn = false)).isEqualTo(LocalNetwork.OK)
    }
}
