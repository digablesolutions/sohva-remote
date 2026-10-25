package fi.digable.braviaremote.protocol

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class PinPairingTest {
    private val tv = FakeBraviaTv(pin = "4321")
    private lateinit var api: BraviaApi
    private lateinit var pairing: PinPairing

    @Before
    fun setUp() {
        tv.start()
        api = BraviaApi(tv.baseUrl)
        pairing = PinPairing(api, clientId = "BraviaRemote:test", nickname = "Pixel (Bravia Remote)")
    }

    @After
    fun tearDown() = tv.stop()

    @Test
    fun `requesting a PIN sends actRegister and accepts the 401`() = runTest {
        pairing.requestPin()
        val req = tv.requests.last()
        assertThat(req.path).isEqualTo("/sony/accessControl")
        val body = tv.bodies.last()
        assertThat(body).contains("\"method\":\"actRegister\"")
        assertThat(body).contains("\"clientid\":\"BraviaRemote:test\"")
        assertThat(body).contains("\"function\":\"WOL\"")
    }

    @Test
    fun `wrong PIN throws WrongPin`() = runTest {
        assertThrows(BraviaException.WrongPin::class.java) {
            runBlocking { pairing.confirmPin("0000") }
        }
    }

    @Test
    fun `right PIN returns the cookie with its expiry`() = runTest {
        val before = System.currentTimeMillis()
        val result = pairing.confirmPin("4321")
        assertThat(result.cookie).isEqualTo(tv.cookie)
        assertThat(result.expiresAtEpochMs).isAtLeast(before + 1209600L * 1000 - 1000)
        assertThat(tv.requests.last().getHeader("Authorization")).isEqualTo("Basic OjQzMjE=")

        api.auth = BraviaAuth.Cookie(result.cookie)
        assertThat(api.getPowerStatus()).isEqualTo(PowerStatus.ACTIVE)
    }

    @Test
    fun `renew with a valid cookie returns a cookie`() = runTest {
        val renewed = pairing.renew(tv.cookie)
        assertThat(renewed.cookie).isEqualTo(tv.cookie)
        assertThat(renewed.expiresAtEpochMs).isNotNull()
    }

    @Test
    fun `renew with an expired cookie is Unauthorized`() = runTest {
        assertThrows(BraviaException.Unauthorized::class.java) {
            runBlocking { pairing.renew("stale") }
        }
    }

    @Test
    fun `Set-Cookie parsing handles Max-Age and Expires`() {
        val now = 1_000_000L
        val byMaxAge = PinPairing.parseSetCookie("auth=abc; Path=/sony/; Max-Age=60", now)
        assertThat(byMaxAge?.cookie).isEqualTo("abc")
        assertThat(byMaxAge?.expiresAtEpochMs).isEqualTo(now + 60_000)

        val byExpires = PinPairing.parseSetCookie("auth=xyz; Expires=Thu, 01 Jan 2026 00:00:00 GMT; Path=/sony/", now)
        assertThat(byExpires?.cookie).isEqualTo("xyz")
        assertThat(byExpires?.expiresAtEpochMs).isEqualTo(1767225600000L)

        assertThat(PinPairing.parseSetCookie("other=1; Path=/", now)).isNull()
        assertThat(PinPairing.parseSetCookie("auth=; Path=/", now)).isNull()
    }
}
