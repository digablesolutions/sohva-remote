package com.mothandgravel.sohva.protocol

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

class TvCheckTest {
    private val tv = FakeBraviaTv()
    private var fastKeysPort = true
    private val checker = TvChecker(portOpen = { _, _ -> fastKeysPort })
    private lateinit var host: String

    @Before
    fun setUp() {
        tv.start()
        host = "${tv.server.hostName}:${tv.server.port}"
    }

    @After
    fun tearDown() = tv.stop()

    @Test
    fun `a paired TV reports every step`() = runTest {
        val check = checker.check(host, BraviaAuth.Cookie(tv.cookie))
        assertThat(check).isEqualTo(
            TvCheck(ipControl = true, androidTvRemote = true, power = PowerStatus.ACTIVE, controlRemotely = true, paired = true, remoteStart = false),
        )
    }

    @Test
    fun `without a pairing the open steps still answer`() = runTest {
        tv.controlRemotely = false
        val check = checker.check(host, auth = null)
        assertThat(check.ipControl).isTrue()
        assertThat(check.controlRemotely).isFalse()
        assertThat(check.power).isEqualTo(PowerStatus.ACTIVE)
        assertThat(check.paired).isNull()
        assertThat(check.remoteStart).isNull()
    }

    @Test
    fun `an expired cookie reads as not paired`() = runTest {
        val check = checker.check(host, BraviaAuth.Cookie("expired"))
        assertThat(check.paired).isFalse()
        assertThat(check.remoteStart).isNull()
    }

    @Test
    fun `Remote start is read in standby too`() = runTest {
        tv.powerOn = false
        tv.remoteStart = true
        val check = checker.check(host, BraviaAuth.Cookie(tv.cookie))
        assertThat(check.power).isEqualTo(PowerStatus.STANDBY)
        assertThat(check.remoteStart).isTrue()
    }

    @Test
    fun `a TV with IP control off can still answer on the fast key port`() = runTest {
        tv.stop()
        val check = checker.check(host, auth = null)
        assertThat(check.ipControl).isFalse()
        assertThat(check.androidTvRemote).isTrue()
        assertThat(check.answers).isTrue()
        fastKeysPort = false
        assertThat(checker.check(host, auth = null).answers).isFalse()
    }

    @Test
    fun `Remote start can be switched on`() = runTest {
        val api = BraviaApi(tv.baseUrl, auth = BraviaAuth.Cookie(tv.cookie))
        api.setRemoteStart(true)
        assertThat(tv.remoteStart).isTrue()
        assertThat(api.getRemoteStart()).isTrue()
    }
}
