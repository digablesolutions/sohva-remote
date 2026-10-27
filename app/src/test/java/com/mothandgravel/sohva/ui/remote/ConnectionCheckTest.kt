package com.mothandgravel.sohva.ui.remote

import com.google.common.truth.Truth.assertThat
import com.mothandgravel.sohva.R
import com.mothandgravel.sohva.data.LocalNetwork
import com.mothandgravel.sohva.protocol.PowerStatus
import com.mothandgravel.sohva.protocol.TvCheck
import org.junit.Test

class ConnectionCheckTest {
    private val allGood = TvCheck(ipControl = true, androidTvRemote = true, power = PowerStatus.ACTIVE, controlRemotely = true, paired = true, remoteStart = true)

    @Test
    fun `a TV with everything on shows only ticks`() {
        val lines = checkLines(LocalNetwork.OK, allGood, fastKeysPaired = true)
        assertThat(lines.map { it.mark }.toSet()).containsExactly(CheckMark.OK)
        assertThat(lines.map { it.title }).containsExactly(
            R.string.check_phone, R.string.check_tv_answers, R.string.check_ip_control,
            R.string.check_paired, R.string.check_remote_start, R.string.check_fast_keys,
        ).inOrder()
    }

    @Test
    fun `a silent TV says only that, after the phone`() {
        val silent = TvCheck(ipControl = false, androidTvRemote = false, power = null, controlRemotely = null, paired = null, remoteStart = null)
        val lines = checkLines(LocalNetwork.VPN, silent, fastKeysPaired = true)
        assertThat(lines).containsExactly(
            CheckLine(R.string.check_phone, CheckMark.PROBLEM, R.string.check_phone_vpn),
            CheckLine(R.string.check_tv_answers, CheckMark.PROBLEM, R.string.check_tv_silent),
        ).inOrder()
    }

    @Test
    fun `a TV in standby is a note, not a problem`() {
        val lines = checkLines(LocalNetwork.OK, allGood.copy(power = PowerStatus.STANDBY), fastKeysPaired = true)
        assertThat(lines.first { it.title == R.string.check_tv_answers })
            .isEqualTo(CheckLine(R.string.check_tv_answers, CheckMark.NOTE, R.string.check_tv_standby))
        assertThat(lines.none { it.mark == CheckMark.PROBLEM }).isTrue()
    }

    @Test
    fun `Control remotely off is told apart from a TV that is not there, and offers this phone`() {
        val off = TvCheck(ipControl = false, androidTvRemote = true, power = null, controlRemotely = null, paired = null, remoteStart = null)
        val lines = checkLines(LocalNetwork.OK, off, fastKeysPaired = false)
        assertThat(lines.first { it.title == R.string.check_tv_answers }.mark).isEqualTo(CheckMark.OK)
        assertThat(lines.first { it.title == R.string.check_ip_control })
            .isEqualTo(CheckLine(R.string.check_ip_control, CheckMark.PROBLEM, R.string.check_ip_control_off, CheckFix.USE_PHONE_AS_REMOTE))
        assertThat(lines.map { it.title }).doesNotContain(R.string.check_paired)
    }

    @Test
    fun `problems the app can fix come with their button`() {
        val lines = checkLines(LocalNetwork.OK, allGood.copy(paired = false, remoteStart = null), fastKeysPaired = false) +
            checkLines(LocalNetwork.OK, allGood.copy(remoteStart = false), fastKeysPaired = true)
        assertThat(lines.mapNotNull { it.fix }).containsExactly(CheckFix.PAIR_AGAIN, CheckFix.SET_UP_FAST_KEYS, CheckFix.TURN_ON_REMOTE_START)
    }

    @Test
    fun `fast keys paired but silent is a problem, not set up is a note`() {
        assertThat(checkLines(LocalNetwork.OK, allGood.copy(androidTvRemote = false), fastKeysPaired = true).last().mark).isEqualTo(CheckMark.PROBLEM)
        assertThat(checkLines(LocalNetwork.OK, allGood, fastKeysPaired = false).last().mark).isEqualTo(CheckMark.NOTE)
    }
}
