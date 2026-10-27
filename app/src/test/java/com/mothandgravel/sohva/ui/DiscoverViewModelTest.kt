package com.mothandgravel.sohva.ui

import com.google.common.truth.Truth.assertThat
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
}
