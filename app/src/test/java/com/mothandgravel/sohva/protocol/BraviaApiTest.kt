package com.mothandgravel.sohva.protocol

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

class BraviaApiTest {
    private val tv = FakeBraviaTv()
    private lateinit var api: BraviaApi

    @Before
    fun setUp() {
        tv.start()
        api = BraviaApi(tv.baseUrl, auth = BraviaAuth.Cookie(tv.cookie))
    }

    @After
    fun tearDown() = tv.stop()

    @Test
    fun `power status reflects the TV`() = runTest {
        assertThat(api.getPowerStatus()).isEqualTo(PowerStatus.ACTIVE)
        tv.powerOn = false
        assertThat(api.getPowerStatus()).isEqualTo(PowerStatus.STANDBY)
    }

    @Test
    fun `setPowerStatus turns the TV on and off`() = runTest {
        api.setPowerStatus(false)
        assertThat(tv.powerOn).isFalse()
        api.setPowerStatus(true)
        assertThat(tv.powerOn).isTrue()
    }

    @Test
    fun `system information includes model and MAC`() = runTest {
        val info = api.getSystemInformation()
        assertThat(info.model).isEqualTo("KD-65XF9005")
        assertThat(info.macAddr).isEqualTo("AC-9B-0A-11-22-33")
        assertThat(info.name).isEqualTo("BRAVIA")
    }

    @Test
    fun `remote controller info is keyed by name`() = runTest {
        val table = api.getRemoteControllerInfo()
        assertThat(table["Confirm"]).isEqualTo("AAAAAQAAAAEAAABlAw==")
        assertThat(table).containsKey("Custom")
    }

    @Test
    fun `volume information parses speaker target`() = runTest {
        tv.volume = 33
        tv.muted = true
        val speaker = api.getVolumeInformation().first { it.target == "speaker" }
        assertThat(speaker.volume).isEqualTo(33)
        assertThat(speaker.mute).isTrue()
        assertThat(speaker.maxVolume).isEqualTo(100)
    }

    @Test
    fun `relative and absolute volume changes`() = runTest {
        tv.volume = 10
        api.setAudioVolume("+1")
        assertThat(tv.volume).isEqualTo(11)
        api.setAudioVolume("25")
        assertThat(tv.volume).isEqualTo(25)
        api.setAudioMute(true)
        assertThat(tv.muted).isTrue()
    }

    @Test
    fun `external inputs expose user label as display name`() = runTest {
        val inputs = api.getCurrentExternalInputsStatus()
        assertThat(inputs).hasSize(2)
        assertThat(inputs[0].displayName).isEqualTo("Apple TV")
        assertThat(inputs[0].connection).isTrue()
        assertThat(inputs[1].displayName).isEqualTo("HDMI 2")
        assertThat(api.getTvSources()).containsExactly("tv:dvbt", "tv:dvbc").inOrder()
    }

    @Test
    fun `channels are read page by page with names and numbers`() = runTest {
        tv.channelCount = 120
        val channels = api.getChannels("tv:dvbt")
        assertThat(channels).hasSize(120)
        assertThat(channels.first()).isEqualTo(TvChannel("tv:dvbt?trip=1.0&srvName=Channel 001", "Channel 001", "001"))
        assertThat(channels.last().number).isEqualTo("120")
        assertThat(tv.bodies.count { "getContentList" in it }).isEqualTo(3)
    }

    @Test
    fun `setPlayContent and setActiveApp send the uri`() = runTest {
        api.setPlayContent("extInput:hdmi?port=2")
        api.setActiveApp("com.sony.dtv.com.netflix.ninja")
        assertThat(tv.playedUris).containsExactly("extInput:hdmi?port=2")
        assertThat(tv.launchedApps).containsExactly("com.sony.dtv.com.netflix.ninja")
    }

    @Test
    fun `application list parses title uri and icon`() = runTest {
        val apps = api.getApplicationList()
        assertThat(apps.map { it.title }).containsExactly("YLE Areena", "Netflix").inOrder()
        assertThat(apps[1].icon).endsWith("netflix.png")
    }

    @Test
    fun `large responses are read completely before the caller resumes`() = runTest {
        // ~60 KB body, well past a single socket read; the real TV's app list is this size.
        tv.extraAppCount = 400
        val apps = api.getApplicationList()
        assertThat(apps).hasSize(402)
        assertThat(apps.last().uri).isEqualTo("com.example.generated.app400")
    }

    @Test
    fun `malformed result shapes become Protocol errors`() = runTest {
        tv.malformedVolume = true
        val e = assertThrows(BraviaException.Protocol::class.java) {
            kotlinx.coroutines.runBlocking { api.getVolumeInformation() }
        }
        assertThat(e.message).contains("getVolumeInformation")
    }

    @Test
    fun `playing content returns null in standby instead of throwing`() = runTest {
        assertThat(api.getPlayingContentInfo()?.title).isEqualTo("HDMI 1")
        tv.powerOn = false
        assertThat(api.getPlayingContentInfo()).isNull()
    }

    @Test
    fun `HTTP 403 maps to Unauthorized`() = runTest {
        api.auth = BraviaAuth.None
        assertThrows(BraviaException.Unauthorized::class.java) {
            kotlinx.coroutines.runBlocking { api.getPowerStatus() }
        }
    }

    @Test
    fun `standby API error is recognised`() = runTest {
        tv.powerOn = false
        val e = assertThrows(BraviaException.ApiError::class.java) {
            kotlinx.coroutines.runBlocking { api.getVolumeInformation() }
        }
        assertThat(e.code).isEqualTo(40005)
        assertThat(e.isStandby).isTrue()
    }

    @Test
    fun `unknown method maps to ApiError`() = runTest {
        val e = assertThrows(BraviaException.ApiError::class.java) {
            kotlinx.coroutines.runBlocking { api.call("system", "noSuchMethod") }
        }
        assertThat(e.code).isEqualTo(12)
        assertThat(e.isStandby).isFalse()
    }

    @Test
    fun `closed port maps to Unreachable`() = runTest {
        val port = ServerSocket(0).use { it.localPort }
        val dead = BraviaApi(
            "http://127.0.0.1:$port/",
            OkHttpClient.Builder().connectTimeout(300, TimeUnit.MILLISECONDS).build(),
        )
        assertThrows(BraviaException.Unreachable::class.java) {
            kotlinx.coroutines.runBlocking { dead.getPowerStatus() }
        }
    }

    @Test
    fun `requests carry the cookie and a JSON-RPC envelope`() = runTest {
        api.getPowerStatus()
        val req = tv.requests.last()
        assertThat(req.path).isEqualTo("/sony/system")
        assertThat(req.getHeader("Cookie")).isEqualTo("auth=${tv.cookie}")
        val body = tv.bodies.last()
        assertThat(body).contains("\"method\":\"getPowerStatus\"")
        assertThat(body).contains("\"version\":\"1.0\"")
        assertThat(body).contains("\"params\":[]")
    }

    @Test
    fun `pre-shared key goes in the X-Auth-PSK header`() = runTest {
        tv.requireAuth = false
        api.auth = BraviaAuth.PreSharedKey("0000")
        api.getPowerStatus()
        assertThat(tv.requests.last().getHeader("X-Auth-PSK")).isEqualTo("0000")
    }
}
