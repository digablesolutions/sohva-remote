package com.mothandgravel.sohva.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.util.Base64

/**
 * A fake Sony Bravia for integration tests: answers the JSON-RPC services, IRCC-IP and PIN
 * pairing the way a real TV does, including the HTTP 401 that means "enter the PIN".
 */
class FakeBraviaTv(
    val pin: String = "1234",
    /** When true every service call needs the auth cookie (TV auth mode "Normal and PSK"). */
    var requireAuth: Boolean = true,
) : Dispatcher() {
    val server = MockWebServer().apply { dispatcher = this@FakeBraviaTv }
    val baseUrl: String get() = server.url("/").toString()

    var powerOn = true
    var volume = 20
    var muted = false
    val irccCodesReceived = mutableListOf<String>()
    val playedUris = mutableListOf<String>()
    val launchedApps = mutableListOf<String>()
    val cookie = "deadbeef1234"
    /** Extra generated apps, to produce responses far larger than one socket read. */
    var extraAppCount = 0
    /** Channels on tv:dvbt, numbered from 001. */
    var channelCount = 3
    /** Answer getVolumeInformation with a shape the app does not expect. */
    var malformedVolume = false
    var cookieMaxAge = 1209600L
    /** Settings › Network › Remote device settings › Control remotely. */
    var controlRemotely = true
    /** Settings › Network › Home network setup › IP control › Remote start. */
    var remoteStart = false
    val requests = mutableListOf<RecordedRequest>()
    /** Request bodies in arrival order; the dispatcher consumes [RecordedRequest.body] itself. */
    val bodies = mutableListOf<String>()

    fun start() = server.start()
    fun stop() = server.shutdown()

    private fun RecordedRequest.hasValidCookie() = getHeader("Cookie")?.contains("auth=$cookie") == true

    override fun dispatch(request: RecordedRequest): MockResponse {
        requests += request
        val body = request.body.readUtf8()
        bodies += body
        val path = request.path ?: return MockResponse().setResponseCode(404)
        return when {
            path.startsWith("/sony/ircc") -> ircc(request, body)
            path.startsWith("/sony/accessControl") -> accessControl(request, body)
            path.startsWith("/sony/") -> rpc(path.removePrefix("/sony/"), request, body)
            path == "/dmr.xml" -> MockResponse().setBody(DEVICE_DESCRIPTION).setHeader("Content-Type", "text/xml")
            else -> MockResponse().setResponseCode(404)
        }
    }

    private fun ircc(request: RecordedRequest, body: String): MockResponse {
        if (requireAuth && !request.hasValidCookie()) return MockResponse().setResponseCode(403)
        val code = Regex("<IRCCCode>([^<]+)</IRCCCode>").find(body)?.groupValues?.get(1)
            ?: return MockResponse().setResponseCode(400)
        irccCodesReceived += code
        // A real Bravia answers a code that maps to no key with a SOAP fault (HTTP 500).
        if (code == BraviaApi.IRCC_WARM_UP_CODE) return MockResponse().setResponseCode(500)
        if (code == RemoteKey.POWER_OFF.defaultCode) powerOn = false
        if (code == RemoteKey.POWER.defaultCode || code == RemoteKey.WAKE_UP.defaultCode) powerOn = true
        return MockResponse().setResponseCode(200).setBody(IRCC_OK)
    }

    private fun accessControl(request: RecordedRequest, body: String): MockResponse {
        val req = Json.parseToJsonElement(body).jsonObject
        val id = req["id"]
        if (req["method"]?.jsonPrimitive?.contentOrNull != "actRegister") return error(id, 12, "No Such Method")
        val authorized = request.hasValidCookie() || run {
            val header = request.getHeader("Authorization") ?: return@run false
            val decoded = String(Base64.getDecoder().decode(header.removePrefix("Basic ").trim()))
            decoded == ":$pin"
        }
        if (!authorized) {
            return MockResponse().setResponseCode(401).setHeader("WWW-Authenticate", "Basic realm=\"\"")
        }
        return MockResponse().setResponseCode(200)
            .setHeader("Set-Cookie", "auth=$cookie; Path=/sony/; Max-Age=$cookieMaxAge")
            .setBody("""{"result":[],"id":$id}""")
    }

    private fun rpc(service: String, request: RecordedRequest, body: String): MockResponse {
        val req = Json.parseToJsonElement(body).jsonObject
        val id = req["id"]
        val method = req["method"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val params = req["params"]?.jsonArray ?: JsonArray(emptyList())
        if (requireAuth && method !in METHODS_WITHOUT_AUTH && !request.hasValidCookie()) return MockResponse().setResponseCode(403)
        if (!powerOn && method !in METHODS_ALLOWED_IN_STANDBY) return error(id, 40005, "Display Is Turned off")
        return when ("$service.$method") {
            "system.getInterfaceInformation" -> ok(
                id,
                """[{"productCategory":"tv","productName":"BRAVIA","modelName":"KD-65XF9005","serverName":"","interfaceVersion":"5.4.0"}]""",
            )
            "system.getRemoteDeviceSettings" -> ok(id, """[[{"target":"accessPermission","currentValue":"${if (controlRemotely) "on" else "off"}"}]]""")
            "system.getWolMode" -> ok(id, """[{"enabled":$remoteStart}]""")
            "system.setWolMode" -> {
                remoteStart = params.firstObj()["enabled"]?.jsonPrimitive?.contentOrNull == "true"
                ok(id, "[]")
            }
            "system.getPowerStatus" -> ok(id, """[{"status":"${if (powerOn) "active" else "standby"}"}]""")
            "system.setPowerStatus" -> {
                powerOn = params.firstObj()["status"]?.jsonPrimitive?.contentOrNull == "true"
                ok(id, "[]")
            }
            "system.getSystemInformation" -> ok(
                id,
                """[{"product":"TV","region":"XEU","language":"eng","model":"KD-65XF9005","serial":"1234567","macAddr":"AC-9B-0A-11-22-33","name":"BRAVIA","generation":"4.0.5","area":"FIN","cid":"x"}]""",
            )
            "system.getRemoteControllerInfo" -> ok(
                id,
                """[{"bundled":true,"type":"RM-J1100"},[{"name":"Confirm","value":"AAAAAQAAAAEAAABlAw=="},{"name":"Home","value":"AAAAAQAAAAEAAABgAw=="},{"name":"Custom","value":"AAAAAQAAAAEAAAD/Aw=="}]]""",
            )
            "audio.getVolumeInformation" -> if (malformedVolume) ok(id, """[["not-an-object"]]""") else ok(
                id,
                """[[{"target":"speaker","volume":$volume,"mute":$muted,"maxVolume":100,"minVolume":0},{"target":"headphone","volume":10,"mute":false,"maxVolume":100,"minVolume":0}]]""",
            )
            "audio.setAudioVolume" -> {
                val v = params.firstObj()["volume"]?.jsonPrimitive?.contentOrNull.orEmpty()
                volume = when {
                    v.startsWith("+") -> volume + v.drop(1).toInt()
                    v.startsWith("-") -> volume - v.drop(1).toInt()
                    else -> v.toInt()
                }
                ok(id, "[0]")
            }
            "audio.setAudioMute" -> {
                muted = params.firstObj()["status"]?.jsonPrimitive?.contentOrNull == "true"
                ok(id, "[0]")
            }
            "avContent.getCurrentExternalInputsStatus" -> ok(
                id,
                """[[{"uri":"extInput:hdmi?port=1","title":"HDMI 1","connection":true,"label":"Apple TV","icon":"meta:hdmi","status":""},{"uri":"extInput:hdmi?port=2","title":"HDMI 2","connection":false,"label":"","icon":"meta:hdmi","status":""}]]""",
            )
            "avContent.getSourceList" -> ok(id, """[[{"source":"tv:dvbt"},{"source":"tv:dvbc"}]]""")
            "avContent.getContentList" -> {
                val p = params.firstObj()
                val start = p["stIdx"]?.jsonPrimitive?.intOrNull ?: 0
                val count = p["cnt"]?.jsonPrimitive?.intOrNull ?: 50
                val page = (start until minOf(start + count, channelCount)).joinToString(",") { i ->
                    val n = "%03d".format(i + 1)
                    "{\"uri\":\"tv:dvbt?trip=1.$i&srvName=Channel $n\",\"title\":\"Channel $n\",\"index\":$i,\"dispNum\":\"$n\"}"
                }
                ok(id, "[[$page]]")
            }
            "avContent.setPlayContent" -> {
                playedUris += params.firstObj()["uri"]?.jsonPrimitive?.contentOrNull.orEmpty()
                ok(id, "[]")
            }
            "avContent.getPlayingContentInfo" -> ok(id, """[{"uri":"extInput:hdmi?port=1","title":"HDMI 1","source":"extInput:hdmi"}]""")
            "appControl.getApplicationList" -> {
                val extra = (1..extraAppCount).joinToString("") { i ->
                    """,{"title":"Generated app $i with a long descriptive name","uri":"com.example.generated.app$i","icon":"http://127.0.0.1/icons/generated-application-$i-xxhdpi.png"}"""
                }
                ok(
                    id,
                    """[[{"title":"YLE Areena","uri":"com.sony.dtv.fi.yle.areena","icon":"http://127.0.0.1/areena.png"},{"title":"Netflix","uri":"com.sony.dtv.com.netflix.ninja","icon":"http://127.0.0.1/netflix.png"}$extra]]""",
                )
            }
            "appControl.setActiveApp" -> {
                launchedApps += params.firstObj()["uri"]?.jsonPrimitive?.contentOrNull.orEmpty()
                ok(id, "[]")
            }
            else -> error(id, 12, "No Such Method")
        }
    }

    private fun JsonArray.firstObj(): JsonObject = (firstOrNull() as? JsonObject) ?: JsonObject(emptyMap())

    private fun ok(id: Any?, result: String) =
        MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
            .setBody("""{"result":$result,"id":$id}""")

    private fun error(id: Any?, code: Int, message: String) =
        MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
            .setBody("""{"error":[$code,"$message"],"id":$id}""")

    companion object {
        private val METHODS_ALLOWED_IN_STANDBY = setOf(
            "getPowerStatus", "setPowerStatus", "getSystemInformation", "getRemoteControllerInfo",
            "getInterfaceInformation", "getRemoteDeviceSettings", "getWolMode", "setWolMode",
        )
        /** The calls a real TV (KD-65XF9005) answers without the auth cookie. */
        private val METHODS_WITHOUT_AUTH = setOf("getPowerStatus", "getInterfaceInformation", "getRemoteDeviceSettings")
        private const val IRCC_OK = """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:X_SendIRCCResponse xmlns:u="urn:schemas-sony-com:service:IRCC:1"></u:X_SendIRCCResponse></s:Body></s:Envelope>"""
        const val DEVICE_DESCRIPTION = """<?xml version="1.0"?>
<root xmlns="urn:schemas-upnp-org:device-1-0" xmlns:av="urn:schemas-sony-com:av">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <device>
    <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
    <friendlyName>KD-65XF9005 &amp; co</friendlyName>
    <manufacturer>Sony Corporation</manufacturer>
    <modelName>KD-65XF9005</modelName>
    <UDN>uuid:12345678-1234-1234-1234-123456789abc</UDN>
    <av:X_ScalarWebAPI_DeviceInfo>
      <av:X_ScalarWebAPI_Version>1.0</av:X_ScalarWebAPI_Version>
      <av:X_ScalarWebAPI_BaseURL>http://192.168.1.20/sony</av:X_ScalarWebAPI_BaseURL>
    </av:X_ScalarWebAPI_DeviceInfo>
  </device>
</root>"""
    }
}
