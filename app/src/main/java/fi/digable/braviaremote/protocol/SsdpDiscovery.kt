package fi.digable.braviaremote.protocol

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.URI

/**
 * Finds Bravia TVs with an SSDP M-SEARCH for Sony's ScalarWebAPI service, then reads each
 * TV's UPnP device description for its friendly name, model and UUID.
 *
 * Pure JVM; the Android side wraps it with a multicast lock (see `AndroidDiscovery`).
 */
class SsdpDiscovery(
    private val httpClient: OkHttpClient = BraviaApi.defaultClient(),
    private val searchTarget: String = SCALAR_WEB_API_ST,
) {
    /** Emits each TV once as it answers, finishing after [timeoutMs]. */
    fun discover(timeoutMs: Long = 4000): Flow<DiscoveredTv> = flow {
        val seen = HashSet<String>()
        val deadline = System.currentTimeMillis() + timeoutMs
        DatagramSocket(null).use { socket ->
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(0))
            socket.broadcast = true
            socket.soTimeout = 500
            val group = InetAddress.getByName(SSDP_ADDRESS)
            val payload = mSearch(searchTarget).toByteArray(Charsets.US_ASCII)
            // Send the search a few times; UDP on Wi-Fi drops packets.
            repeat(2) { socket.send(DatagramPacket(payload, payload.size, group, SSDP_PORT)) }

            val buffer = ByteArray(2048)
            var resent = false
            while (currentCoroutineContext().isActive && System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    if (!resent && System.currentTimeMillis() > deadline - timeoutMs / 2) {
                        socket.send(DatagramPacket(payload, payload.size, group, SSDP_PORT))
                        resent = true
                    }
                    continue
                }
                val text = String(packet.data, 0, packet.length, Charsets.ISO_8859_1)
                val response = parseResponse(text) ?: continue
                val host = packet.address.hostAddress ?: continue
                val key = response.usnUuid ?: host
                if (!seen.add(key)) continue
                val description = runCatching { fetchDescription(response.location) }.getOrNull()
                tvFrom(host, response.usnUuid, description)?.let { emit(it) }
            }
        }
    }.flowOn(Dispatchers.IO)

    /** Reads the device description XML a TV points to in its SSDP `LOCATION` header. */
    suspend fun fetchDescription(location: String): DeviceDescription = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(location).get().build()
        val response = httpClient.newCall(request).awaitBuffered()
        if (response.code !in 200..299) throw BraviaException.Protocol("Device description HTTP ${response.code}")
        parseDescription(response.body)
    }

    /** Probes one host by hand (manual IP entry). Returns null when it does not answer like a Bravia. */
    suspend fun probe(host: String): DiscoveredTv? {
        val api = BraviaApi("http://$host/", httpClient)
        return try {
            val info = api.getSystemInformation()
            DiscoveredTv(host = host, name = info.name.ifBlank { info.model }, model = info.model)
        } catch (e: BraviaException.Unauthorized) {
            // Reachable but locked down: still a Bravia.
            DiscoveredTv(host = host, name = "Bravia at $host", model = "")
        } catch (e: BraviaException.ApiError) {
            DiscoveredTv(host = host, name = "Bravia at $host", model = "")
        } catch (e: Exception) {
            null
        }
    }

    data class SsdpResponse(val location: String, val usn: String?, val server: String?) {
        /** `uuid:XXXX::urn:…` -> `XXXX` */
        val usnUuid: String?
            get() = usn?.substringAfter("uuid:", "")?.substringBefore("::")?.ifBlank { null }

        val host: String get() = URI(location).host
    }

    data class DeviceDescription(
        val friendlyName: String,
        val modelName: String,
        val udn: String?,
        val scalarBaseUrl: String?,
    )

    companion object {
        const val SSDP_ADDRESS = "239.255.255.250"
        const val SSDP_PORT = 1900
        const val SCALAR_WEB_API_ST = "urn:schemas-sony-com:service:ScalarWebAPI:1"

        fun mSearch(st: String, mx: Int = 2): String =
            "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: $SSDP_ADDRESS:$SSDP_PORT\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: $mx\r\n" +
                "ST: $st\r\n" +
                "USER-AGENT: SohvaRemote/1.0 UPnP/1.1\r\n" +
                "\r\n"

        /**
         * Turns an SSDP responder into a TV, or null when its description shows it is not one:
         * some devices (a Philips Hue Bridge, for one) answer every M-SEARCH whatever its target.
         * A responder whose description could not be read is kept, so a slow TV still shows up.
         */
        fun tvFrom(host: String, usnUuid: String?, description: DeviceDescription?): DiscoveredTv? {
            if (description != null && description.scalarBaseUrl.isNullOrBlank()) return null
            return DiscoveredTv(
                host = host,
                name = description?.friendlyName?.ifBlank { null } ?: "Bravia at $host",
                model = description?.modelName.orEmpty(),
                uuid = description?.udn ?: usnUuid,
            )
        }

        /** Parses an SSDP HTTP-like response into its LOCATION/USN/SERVER headers. */
        fun parseResponse(text: String): SsdpResponse? {
            val lines = text.split("\r\n", "\n")
            if (lines.isEmpty() || !lines[0].startsWith("HTTP/1.1 200", ignoreCase = true)) return null
            val headers = HashMap<String, String>()
            for (line in lines.drop(1)) {
                val idx = line.indexOf(':')
                if (idx <= 0) continue
                headers[line.substring(0, idx).trim().uppercase()] = line.substring(idx + 1).trim()
            }
            val location = headers["LOCATION"] ?: return null
            if (!location.startsWith("http", ignoreCase = true)) return null
            return SsdpResponse(location, headers["USN"], headers["SERVER"])
        }

        private fun tag(xml: String, name: String): String? {
            // Matches <name>…</name> and <prefix:name>…</prefix:name>, first occurrence.
            val regex = Regex("<(?:[A-Za-z0-9_]+:)?$name(?:\\s[^>]*)?>([^<]*)</(?:[A-Za-z0-9_]+:)?$name>")
            return regex.find(xml)?.groupValues?.get(1)?.trim()?.let(::unescapeXml)
        }

        fun parseDescription(xml: String): DeviceDescription = DeviceDescription(
            friendlyName = tag(xml, "friendlyName").orEmpty(),
            modelName = tag(xml, "modelName").orEmpty(),
            udn = tag(xml, "UDN")?.removePrefix("uuid:")?.ifBlank { null },
            scalarBaseUrl = tag(xml, "X_ScalarWebAPI_BaseURL"),
        )

        private fun unescapeXml(s: String): String = s
            .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&apos;", "'").replace("&#39;", "'").replace("&amp;", "&")
    }
}
