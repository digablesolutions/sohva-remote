package fi.digable.braviaremote.protocol

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class IrccTest {
    private val tv = FakeBraviaTv()
    private var now = 1_000_000L
    private lateinit var api: BraviaApi

    @Before
    fun setUp() {
        tv.start()
        api = BraviaApi(tv.baseUrl, auth = BraviaAuth.Cookie(tv.cookie), clock = { now })
    }

    @After
    fun tearDown() = tv.stop()

    @Test
    fun `sends a SOAP X_SendIRCC envelope with the key code`() = runTest {
        api.sendIrcc(IrccCodeTable.DEFAULT.codeFor(RemoteKey.HOME))
        val req = tv.requests.last()
        assertThat(req.path).isEqualTo("/sony/ircc")
        assertThat(req.getHeader("SOAPACTION")).isEqualTo("\"urn:schemas-sony-com:service:IRCC:1#X_SendIRCC\"")
        assertThat(req.getHeader("Content-Type")).startsWith("text/xml")
        assertThat(tv.irccCodesReceived.last()).isEqualTo(RemoteKey.HOME.defaultCode)
    }

    @Test
    fun `the first key wakes the TV's key service before it is sent`() = runTest {
        api.sendIrcc(RemoteKey.HOME.defaultCode)
        assertThat(tv.irccCodesReceived)
            .containsExactly(BraviaApi.IRCC_WARM_UP_CODE, RemoteKey.HOME.defaultCode).inOrder()
    }

    @Test
    fun `keys close together are sent without a warm-up`() = runTest {
        api.sendIrcc(RemoteKey.UP.defaultCode)
        now += 60_000
        api.sendIrcc(RemoteKey.DOWN.defaultCode)
        assertThat(tv.irccCodesReceived)
            .containsExactly(BraviaApi.IRCC_WARM_UP_CODE, RemoteKey.UP.defaultCode, RemoteKey.DOWN.defaultCode).inOrder()
    }

    @Test
    fun `a key after a long idle wakes the key service again`() = runTest {
        api.sendIrcc(RemoteKey.UP.defaultCode)
        now += BraviaApi.IRCC_IDLE_BEFORE_WARM_UP_MS + 1
        api.sendIrcc(RemoteKey.DOWN.defaultCode)
        assertThat(tv.irccCodesReceived).containsExactly(
            BraviaApi.IRCC_WARM_UP_CODE, RemoteKey.UP.defaultCode,
            BraviaApi.IRCC_WARM_UP_CODE, RemoteKey.DOWN.defaultCode,
        ).inOrder()
    }

    @Test
    fun `warmUpIrcc on its own sends only the warm-up code`() = runTest {
        api.warmUpIrcc()
        api.sendIrcc(RemoteKey.HOME.defaultCode)
        assertThat(tv.irccCodesReceived)
            .containsExactly(BraviaApi.IRCC_WARM_UP_CODE, RemoteKey.HOME.defaultCode).inOrder()
    }

    @Test
    fun `a key sent during a warm-up waits for it and its settle time`() = runTest {
        val warmUp = launch { api.warmUpIrcc() }
        testScheduler.runCurrent() // the warm-up holds the IRCC lock now
        api.sendIrcc(RemoteKey.HOME.defaultCode)
        assertThat(testScheduler.currentTime).isAtLeast(BraviaApi.IRCC_WARM_UP_SETTLE_MS)
        assertThat(warmUp.isCompleted).isTrue()
        assertThat(tv.irccCodesReceived)
            .containsExactly(BraviaApi.IRCC_WARM_UP_CODE, RemoteKey.HOME.defaultCode).inOrder()
    }

    @Test
    fun `the warm-up code maps to no key`() {
        assertThat(RemoteKey.entries.map { it.defaultCode }).doesNotContain(BraviaApi.IRCC_WARM_UP_CODE)
        assertThat(java.util.Base64.getDecoder().decode(BraviaApi.IRCC_WARM_UP_CODE).size).isEqualTo(13)
    }

    @Test
    fun `IRCC power off and on change the fake TV state`() = runTest {
        api.sendIrcc(RemoteKey.POWER_OFF.defaultCode)
        assertThat(tv.powerOn).isFalse()
        api.sendIrcc(RemoteKey.POWER.defaultCode)
        assertThat(tv.powerOn).isTrue()
    }

    @Test
    fun `IRCC without a cookie is Unauthorized`() = runTest {
        api.auth = BraviaAuth.None
        assertThrows(BraviaException.Unauthorized::class.java) {
            runBlocking { api.sendIrcc(RemoteKey.HOME.defaultCode) }
        }
    }

    @Test
    fun `code table prefers the TV's own codes`() {
        val table = IrccCodeTable(mapOf("Confirm" to "CUSTOM==", "Custom" to "X"))
        assertThat(table.codeFor(RemoteKey.CONFIRM)).isEqualTo("CUSTOM==")
        assertThat(table.isFromTv(RemoteKey.CONFIRM)).isTrue()
        assertThat(table.codeFor(RemoteKey.HOME)).isEqualTo(RemoteKey.HOME.defaultCode)
        assertThat(table.isFromTv(RemoteKey.HOME)).isFalse()
    }

    @Test
    fun `every key has a well-formed default code`() {
        for (key in RemoteKey.entries) {
            val bytes = java.util.Base64.getDecoder().decode(key.defaultCode)
            assertThat(bytes.size).isEqualTo(13)
        }
        assertThat(RemoteKey.entries.map { it.defaultCode }.toSet().size).isEqualTo(RemoteKey.entries.size)
        assertThat(RemoteKey.digits.map { it.digit }).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 0).inOrder()
    }
}
