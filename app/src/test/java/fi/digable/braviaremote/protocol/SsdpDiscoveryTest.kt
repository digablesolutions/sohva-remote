package fi.digable.braviaremote.protocol

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SsdpDiscoveryTest {

    @Test
    fun `M-SEARCH is well formed`() {
        val msg = SsdpDiscovery.mSearch(SsdpDiscovery.SCALAR_WEB_API_ST)
        assertThat(msg).startsWith("M-SEARCH * HTTP/1.1\r\n")
        assertThat(msg).contains("HOST: 239.255.255.250:1900\r\n")
        assertThat(msg).contains("MAN: \"ssdp:discover\"\r\n")
        assertThat(msg).contains("ST: urn:schemas-sony-com:service:ScalarWebAPI:1\r\n")
        assertThat(msg).endsWith("\r\n\r\n")
    }

    @Test
    fun `parses a TV's SSDP response`() {
        val text = "HTTP/1.1 200 OK\r\n" +
            "CACHE-CONTROL: max-age=1800\r\n" +
            "EXT:\r\n" +
            "LOCATION: http://192.168.1.20:52323/dmr.xml\r\n" +
            "SERVER: Linux/3.10 UPnP/1.0 SonyBRAVIA/1.0\r\n" +
            "ST: urn:schemas-sony-com:service:ScalarWebAPI:1\r\n" +
            "USN: uuid:12345678-1234-1234-1234-123456789abc::urn:schemas-sony-com:service:ScalarWebAPI:1\r\n" +
            "\r\n"
        val parsed = SsdpDiscovery.parseResponse(text)!!
        assertThat(parsed.location).isEqualTo("http://192.168.1.20:52323/dmr.xml")
        assertThat(parsed.host).isEqualTo("192.168.1.20")
        assertThat(parsed.usnUuid).isEqualTo("12345678-1234-1234-1234-123456789abc")
        assertThat(parsed.server).contains("SonyBRAVIA")
    }

    @Test
    fun `ignores NOTIFY and responses without a location`() {
        assertThat(SsdpDiscovery.parseResponse("NOTIFY * HTTP/1.1\r\nNTS: ssdp:alive\r\n\r\n")).isNull()
        assertThat(SsdpDiscovery.parseResponse("HTTP/1.1 200 OK\r\nST: x\r\n\r\n")).isNull()
    }

    @Test
    fun `parses the UPnP device description`() {
        val d = SsdpDiscovery.parseDescription(FakeBraviaTv.DEVICE_DESCRIPTION)
        assertThat(d.friendlyName).isEqualTo("KD-65XF9005 & co")
        assertThat(d.modelName).isEqualTo("KD-65XF9005")
        assertThat(d.udn).isEqualTo("12345678-1234-1234-1234-123456789abc")
        assertThat(d.scalarBaseUrl).isEqualTo("http://192.168.1.20/sony")
    }

    @Test
    fun `a responder whose description has no ScalarWebAPI is not a TV`() {
        // A Philips Hue Bridge answers every M-SEARCH, whatever the search target.
        val hue = SsdpDiscovery.parseDescription(
            """<root><device><deviceType>urn:schemas-upnp-org:device:Basic:1</deviceType>
            <friendlyName>Hue Bridge (192.168.1.107)</friendlyName><manufacturer>Signify</manufacturer>
            <modelName>Philips hue bridge 2015</modelName><UDN>uuid:2f402f80-da50-11e1-9b23-ecb5fa19202b</UDN>
            </device></root>""",
        )
        assertThat(SsdpDiscovery.tvFrom("192.168.1.107", "2f402f80", hue)).isNull()
    }

    @Test
    fun `a responder with a ScalarWebAPI description is a TV`() {
        val d = SsdpDiscovery.parseDescription(FakeBraviaTv.DEVICE_DESCRIPTION)
        val tv = SsdpDiscovery.tvFrom("192.168.1.20", null, d)!!
        assertThat(tv.name).isEqualTo("KD-65XF9005 & co")
        assertThat(tv.uuid).isEqualTo("12345678-1234-1234-1234-123456789abc")
    }

    @Test
    fun `a responder whose description could not be read is kept`() {
        val tv = SsdpDiscovery.tvFrom("192.168.1.20", "abc", null)!!
        assertThat(tv.name).isEqualTo("Bravia at 192.168.1.20")
        assertThat(tv.uuid).isEqualTo("abc")
    }

    @Test
    fun `fetchDescription reads the XML over HTTP`() = runTest {
        val tv = FakeBraviaTv()
        tv.start()
        try {
            val d = SsdpDiscovery().fetchDescription(tv.baseUrl + "dmr.xml")
            assertThat(d.modelName).isEqualTo("KD-65XF9005")
        } finally {
            tv.stop()
        }
    }

    @Test
    fun `probe identifies a reachable TV by its system information`() = runTest {
        val tv = FakeBraviaTv(requireAuth = false)
        tv.start()
        try {
            val host = tv.baseUrl.removePrefix("http://").trimEnd('/')
            val found = SsdpDiscovery().probe(host)!!
            assertThat(found.model).isEqualTo("KD-65XF9005")
            assertThat(found.name).isEqualTo("BRAVIA")
            assertThat(found.host).isEqualTo(host)
        } finally {
            tv.stop()
        }
    }

    @Test
    fun `probe still recognises a TV that demands auth`() = runTest {
        val tv = FakeBraviaTv(requireAuth = true)
        tv.start()
        try {
            val host = tv.baseUrl.removePrefix("http://").trimEnd('/')
            val found = SsdpDiscovery().probe(host)
            assertThat(found).isNotNull()
            assertThat(found!!.name).contains("Bravia at")
        } finally {
            tv.stop()
        }
    }
}
