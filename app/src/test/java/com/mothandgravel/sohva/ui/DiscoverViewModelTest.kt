package com.mothandgravel.sohva.ui

import com.google.common.truth.Truth.assertThat
import com.mothandgravel.sohva.protocol.DiscoveredTv
import com.mothandgravel.sohva.ui.discover.DiscoverViewModel
import org.junit.Test

class DiscoverViewModelTest {
    @Test
    fun `IPv4 validation`() {
        assertThat(DiscoverViewModel.isIpv4("192.168.1.20")).isTrue()
        assertThat(DiscoverViewModel.isIpv4("10.0.0.1")).isTrue()
        assertThat(DiscoverViewModel.isIpv4("256.1.1.1")).isFalse()
        assertThat(DiscoverViewModel.isIpv4("192.168.1")).isFalse()
        assertThat(DiscoverViewModel.isIpv4("192.168.1.")).isFalse()
        assertThat(DiscoverViewModel.isIpv4("tv.local")).isFalse()
        assertThat(DiscoverViewModel.isIpv4("")).isFalse()
    }

    @Test
    fun `a TV found by both searches keeps its SSDP details`() {
        val mdns = DiscoveredTv(host = "192.168.1.20", name = "BRAVIA 4K", model = "", webApi = false)
        val ssdp = DiscoveredTv(host = "192.168.1.20", name = "KD-65XF9005", model = "KD-65XF9005", uuid = "u")
        assertThat(DiscoverViewModel.merge(listOf(mdns), ssdp)).containsExactly(ssdp)
        assertThat(DiscoverViewModel.merge(listOf(ssdp), mdns)).containsExactly(ssdp)
        val other = mdns.copy(host = "192.168.1.21")
        assertThat(DiscoverViewModel.merge(listOf(ssdp), other)).containsExactly(ssdp, other).inOrder()
    }
}
