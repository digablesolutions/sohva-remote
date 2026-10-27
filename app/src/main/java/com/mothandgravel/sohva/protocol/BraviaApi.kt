package com.mothandgravel.sohva.protocol

import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Client for one Sony Bravia TV's local IP control: the JSON-RPC "Scalar Web API" under
 * `http://<tv>/sony/<service>` and IRCC-IP key presses under `http://<tv>/sony/ircc`.
 *
 * No Android dependencies, so it is unit-tested against a fake TV (MockWebServer).
 *
 * @param baseUrl the TV's base URL, e.g. `http://192.168.1.20/` (a port is allowed for tests).
 * @param clock wall-clock milliseconds; tests pass a fake one.
 */
class BraviaApi(
    baseUrl: String,
    private val client: OkHttpClient = defaultClient(),
    @Volatile var auth: BraviaAuth = BraviaAuth.None,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val base: HttpUrl = baseUrl.toHttpUrl()
    val host: String get() = base.host
    private val nextId = AtomicInteger(1)

    /** When this client last reached the TV's IRCC service; null until the first key. */
    @Volatile private var lastIrccAtMs: Long? = null

    /**
     * Held for a whole warm-up, settle time included, and for each key: a key never goes out while
     * a warm-up is in flight, so a warm-up can run outside the caller's key queue.
     */
    private val irccLock = Mutex()

    // ---- system ----

    suspend fun getPowerStatus(): PowerStatus = mapped("getPowerStatus") {
        val result = call("system", "getPowerStatus")
        return@mapped when (result.firstObject()["status"]?.jsonPrimitive?.contentOrNull) {
            "active" -> PowerStatus.ACTIVE
            "standby" -> PowerStatus.STANDBY
            else -> PowerStatus.UNKNOWN
        }
    }


    suspend fun setPowerStatus(on: Boolean) {
        call("system", "setPowerStatus", buildJsonArray { add(buildJsonObject { put("status", on) }) })
    }

    suspend fun getSystemInformation(): SystemInfo = mapped("getSystemInformation") {
        val o = call("system", "getSystemInformation").firstObject()
        return@mapped SystemInfo(
            name = o.str("name"),
            model = o.str("model"),
            product = o.str("product"),
            generation = o.str("generation"),
            serial = o.str("serial"),
            macAddr = o.str("macAddr"),
        )
    }


    /** The TV's product and model. Answers without pairing, so it tells "a Bravia's web API is here". */
    suspend fun getInterfaceInformation(): String = mapped("getInterfaceInformation") {
        call("system", "getInterfaceInformation").firstObject().str("modelName")
    }

    /**
     * Settings › Network › Remote device settings › Control remotely. Answers without pairing; null
     * when the TV does not report it.
     */
    suspend fun getControlRemotely(): Boolean? = mapped("getRemoteDeviceSettings") {
        val list = call("system", "getRemoteDeviceSettings", buildJsonArray { add(buildJsonObject { put("target", "") }) })
            .getOrNull(0)?.jsonArray ?: return@mapped null
        val setting = list.firstOrNull { (it as? JsonObject)?.str("target") == "accessPermission" } as? JsonObject
        return@mapped setting?.str("currentValue")?.let { it == "on" }
    }

    /** "Remote start": whether the TV can be switched on over the network from standby. Needs pairing. */
    suspend fun getRemoteStart(): Boolean = mapped("getWolMode") {
        call("system", "getWolMode").firstObject()["enabled"]?.jsonPrimitive?.booleanOrNull
            ?: throw NoSuchElementException("enabled")
    }

    suspend fun setRemoteStart(on: Boolean) {
        call("system", "setWolMode", buildJsonArray { add(buildJsonObject { put("enabled", on) }) })
    }

    /** The TV's own IRCC key table, keyed by key name. */
    suspend fun getRemoteControllerInfo(): Map<String, String> = mapped("getRemoteControllerInfo") {
        val result = call("system", "getRemoteControllerInfo")
        val list = result.getOrNull(1)?.jsonArray ?: return@mapped emptyMap()
        return@mapped list.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val name = o["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val value = o["value"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            name to value
        }.toMap()
    }


    // ---- audio ----

    suspend fun getVolumeInformation(): List<VolumeInfo> = mapped("getVolumeInformation") {
        val result = call("audio", "getVolumeInformation")
        val list = result.firstOrNull()?.jsonArray ?: return@mapped emptyList()
        return@mapped list.map { e ->
            val o = e.jsonObject
            VolumeInfo(
                target = o.str("target"),
                volume = o["volume"]?.jsonPrimitive?.intOrNull ?: 0,
                minVolume = o["minVolume"]?.jsonPrimitive?.intOrNull ?: 0,
                maxVolume = o["maxVolume"]?.jsonPrimitive?.intOrNull ?: 100,
                mute = o["mute"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
        }
    }


    /** [volume] is an absolute level, or a relative step as a signed string such as `"+1"` / `"-1"`. */
    suspend fun setAudioVolume(volume: String, target: String = "speaker") {
        call(
            "audio", "setAudioVolume",
            buildJsonArray { add(buildJsonObject { put("target", target); put("volume", volume) }) },
            version = "1.0",
        )
    }

    suspend fun setAudioMute(mute: Boolean) {
        call("audio", "setAudioMute", buildJsonArray { add(buildJsonObject { put("status", mute) }) })
    }

    // ---- avContent ----

    suspend fun getCurrentExternalInputsStatus(): List<ExternalInput> = mapped("getCurrentExternalInputsStatus") {
        val result = call("avContent", "getCurrentExternalInputsStatus")
        val list = result.firstOrNull()?.jsonArray ?: return@mapped emptyList()
        return@mapped list.map { e ->
            val o = e.jsonObject
            ExternalInput(
                uri = o.str("uri"),
                title = o.str("title"),
                label = o.str("label"),
                icon = o.str("icon"),
                connection = o["connection"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
        }
    }


    /** Tuner sources such as `tv:dvbt`, `tv:dvbc`, `tv:dvbs`. */
    suspend fun getTvSources(): List<String> = mapped("getSourceList") {
        val result = call("avContent", "getSourceList", buildJsonArray { add(buildJsonObject { put("scheme", "tv") }) })
        val list = result.firstOrNull()?.jsonArray ?: return@mapped emptyList()
        return@mapped list.mapNotNull { (it as? JsonObject)?.get("source")?.jsonPrimitive?.contentOrNull }
    }


    /**
     * The channels of a tuner [source] such as `tv:dvbt`, in the TV's order. Read in pages; the
     * list carries names and numbers only, no programme information.
     */
    suspend fun getChannels(source: String): List<TvChannel> = mapped("getContentList") {
        val channels = mutableListOf<TvChannel>()
        while (true) {
            val params = buildJsonArray {
                add(buildJsonObject {
                    put("source", source)
                    put("stIdx", channels.size)
                    put("cnt", CHANNEL_PAGE)
                })
            }
            val page = call("avContent", "getContentList", params).firstOrNull()?.jsonArray ?: break
            page.mapTo(channels) { e ->
                val o = e.jsonObject
                TvChannel(uri = o.str("uri"), title = o.str("title"), number = o.str("dispNum"))
            }
            if (page.size < CHANNEL_PAGE || channels.size >= MAX_CHANNELS) break
        }
        return@mapped channels
    }

    suspend fun setPlayContent(uri: String) {
        call("avContent", "setPlayContent", buildJsonArray { add(buildJsonObject { put("uri", uri) }) })
    }

    suspend fun getPlayingContentInfo(): PlayingContent? = mapped("getPlayingContentInfo") {
        val result = try {
            call("avContent", "getPlayingContentInfo")
        } catch (e: BraviaException.ApiError) {
            if (e.isStandby) return@mapped null else throw e
        }
        val o = result.firstOrNull() as? JsonObject ?: return@mapped null
        return@mapped PlayingContent(
            uri = o.str("uri"),
            title = o.str("title"),
            source = o.str("source"),
            programTitle = o.str("programTitle").ifBlank { null },
            programStart = o.str("startDateTime").ifBlank { null },
            programSeconds = o.str("durationSec").toIntOrNull(),
            raw = o.toString(),
        )
    }


    // ---- appControl ----

    suspend fun getApplicationList(): List<TvApp> = mapped("getApplicationList") {
        val result = call("appControl", "getApplicationList")
        val list = result.firstOrNull()?.jsonArray ?: return@mapped emptyList()
        return@mapped list.map { e ->
            val o = e.jsonObject
            TvApp(uri = o.str("uri"), title = o.str("title"), icon = o.str("icon"))
        }
    }


    suspend fun setActiveApp(uri: String) {
        call("appControl", "setActiveApp", buildJsonArray { add(buildJsonObject { put("uri", uri) }) })
    }

    // ---- IRCC ----

    /** Sends one remote key press. [code] is the base64 IRCC code. */
    suspend fun sendIrcc(code: String) = irccLock.withLock {
        warmUpLocked()
        postIrcc(code)
        lastIrccAtMs = clock()
    }

    /**
     * Wakes the TV's key service if no key has reached it for a while; otherwise does nothing.
     *
     * After some idle minutes the TV unbinds the service that injects IRCC keys. The next key
     * restarts it and is lost: it is written before Android has registered the service's new
     * input device. A well-formed code that maps to no key restarts the service without pressing
     * anything; the TV answers it with an error, which is expected and ignored.
     *
     * A key sent meanwhile with [sendIrcc] waits until the warm-up and its settle time are over.
     */
    suspend fun warmUpIrcc() = irccLock.withLock { warmUpLocked() }

    private suspend fun warmUpLocked() {
        val last = lastIrccAtMs
        if (last != null && clock() - last <= IRCC_IDLE_BEFORE_WARM_UP_MS) return
        try {
            postIrcc(IRCC_WARM_UP_CODE)
        } catch (e: BraviaException.Protocol) {
            // Expected: no key has this code.
        }
        lastIrccAtMs = clock()
        delay(IRCC_WARM_UP_SETTLE_MS)
    }

    private suspend fun postIrcc(code: String) {
        val body = irccEnvelope(code)
        val request = Request.Builder()
            .url(base.newBuilder().encodedPath("/sony/ircc").build())
            .header("Content-Type", "text/xml; charset=UTF-8")
            .header("SOAPACTION", "\"urn:schemas-sony-com:service:IRCC:1#X_SendIRCC\"")
            .applyAuth()
            .post(body.toRequestBody(XML))
            .build()
        val response = execute(request)
        when (response.code) {
            in 200..299 -> Unit
            401, 403 -> throw BraviaException.Unauthorized("IRCC rejected (${response.code})")
            else -> throw BraviaException.Protocol("IRCC HTTP ${response.code}")
        }
    }

    // ---- JSON-RPC core ----

    /** Runs a result mapping; a shape we did not expect becomes a [BraviaException.Protocol]. */
    private inline fun <T> mapped(what: String, block: () -> T): T = try {
        block()
    } catch (e: BraviaException) {
        throw e
    } catch (e: IllegalArgumentException) {
        throw BraviaException.Protocol("Unexpected $what response", e)
    } catch (e: IllegalStateException) {
        throw BraviaException.Protocol("Unexpected $what response", e)
    } catch (e: NoSuchElementException) {
        throw BraviaException.Protocol("Unexpected $what response", e)
    }

    /**
     * Performs one JSON-RPC call and returns its `result` array.
     * Throws [BraviaException] for transport, HTTP and API-level failures.
     */
    suspend fun call(
        service: String,
        method: String,
        params: JsonArray = EMPTY_PARAMS,
        version: String = "1.0",
    ): JsonArray {
        val payload = buildJsonObject {
            put("method", method)
            put("id", nextId.getAndIncrement())
            put("params", params)
            put("version", version)
        }
        val request = Request.Builder()
            .url(base.newBuilder().encodedPath("/sony/$service").build())
            .applyAuth()
            .post(json.encodeToString(JsonObject.serializer(), payload).toRequestBody(JSON))
            .build()
        return parseRpcResponse(execute(request))
    }

    /** Raw access for pairing, which needs the response headers. */
    internal suspend fun rawCall(request: Request): HttpResult = execute(request)

    internal fun rpcRequest(service: String, method: String, params: JsonArray, version: String = "1.0"): Request.Builder {
        val payload = buildJsonObject {
            put("method", method)
            put("id", nextId.getAndIncrement())
            put("params", params)
            put("version", version)
        }
        return Request.Builder()
            .url(base.newBuilder().encodedPath("/sony/$service").build())
            .post(json.encodeToString(JsonObject.serializer(), payload).toRequestBody(JSON))
    }

    internal fun parseRpcResponse(response: HttpResult): JsonArray {
        val bodyText = response.body
        when (response.code) {
            401, 403 -> throw BraviaException.Unauthorized("HTTP ${response.code}")
            in 200..299 -> Unit
            else -> throw BraviaException.Protocol("HTTP ${response.code}: ${bodyText.take(200)}")
        }
        val obj = try {
            json.parseToJsonElement(bodyText).jsonObject
        } catch (e: Exception) {
            throw BraviaException.Protocol("Bad JSON from TV", e)
        }
        obj["error"]?.let { err ->
            val arr = err as? JsonArray
            val code = arr?.getOrNull(0)?.jsonPrimitive?.intOrNull ?: -1
            val message = arr?.getOrNull(1)?.jsonPrimitive?.contentOrNull ?: err.toString()
            if (code == 403) throw BraviaException.Unauthorized(message)
            throw BraviaException.ApiError(code, message)
        }
        return obj["result"]?.jsonArray ?: obj["results"]?.jsonArray ?: JsonArray(emptyList())
    }

    private fun Request.Builder.applyAuth(): Request.Builder = when (val a = auth) {
        is BraviaAuth.None -> this
        is BraviaAuth.Cookie -> header("Cookie", "auth=${a.value}")
        is BraviaAuth.PreSharedKey -> header("X-Auth-PSK", a.key)
    }

    /**
     * Runs the request and returns it fully read. The body is consumed on OkHttp's thread inside
     * the callback: callers often run on the main thread, where touching the socket after the
     * coroutine resumes would raise NetworkOnMainThreadException.
     */
    private suspend fun execute(request: Request): HttpResult {
        val call = client.newCall(request)
        return try {
            call.awaitBuffered()
        } catch (e: BraviaException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw BraviaException.Unreachable(host, e)
        } catch (e: ConnectException) {
            throw BraviaException.Unreachable(host, e)
        } catch (e: UnknownHostException) {
            throw BraviaException.Unreachable(host, e)
        } catch (e: IOException) {
            throw BraviaException.Unreachable(host, e)
        }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val XML = "text/xml; charset=utf-8".toMediaType()
        internal val EMPTY_PARAMS = JsonArray(emptyList())
        internal val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** SIRCS category 1, data code 0x7F: well formed, but mapped to no key on a Bravia. */
        const val IRCC_WARM_UP_CODE = "AAAAAQAAAAEAAAB/Aw=="

        /**
         * Idle time after which the next key is preceded by a warm-up. The TV kept its key
         * service for at least 8.5 minutes and had dropped it after 21; stay well under that.
         */
        const val IRCC_IDLE_BEFORE_WARM_UP_MS = 5L * 60 * 1000

        /** Time for Android to register the restarted key service's input device (~150 ms seen). */
        const val IRCC_WARM_UP_SETTLE_MS = 300L

        /** Short timeouts: a key press should fail fast rather than queue up. */
        /** Channels per getContentList call, and a ceiling against a TV that never stops paging. */
        private const val CHANNEL_PAGE = 50
        private const val MAX_CHANNELS = 2000

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(1500, TimeUnit.MILLISECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .writeTimeout(2, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()

        internal fun irccEnvelope(code: String): String =
            """<?xml version="1.0"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body>
<u:X_SendIRCC xmlns:u="urn:schemas-sony-com:service:IRCC:1">
<IRCCCode>$code</IRCCCode>
</u:X_SendIRCC>
</s:Body>
</s:Envelope>"""
    }
}

private fun JsonArray.firstObject(): JsonObject =
    firstOrNull() as? JsonObject ?: throw BraviaException.Protocol("Expected an object in result")

private fun JsonObject.str(key: String): String = when (val v = this[key]) {
    is JsonPrimitive -> v.contentOrNull.orEmpty()
    null -> ""
    else -> v.toString()
}

/** An HTTP response read to completion: status, headers and body as text. */
internal class HttpResult(val code: Int, val headers: Headers, val body: String) {
    fun headers(name: String): List<String> = headers.values(name)
}

/**
 * Suspends until the call completes and the whole body has been read on OkHttp's thread.
 * Cancels the HTTP call when the coroutine is cancelled.
 */
internal suspend fun Call.awaitBuffered(): HttpResult = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isCancelled) return
            cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            val result = try {
                response.use { HttpResult(it.code, it.headers, it.body?.string().orEmpty()) }
            } catch (e: IOException) {
                if (!cont.isCancelled) cont.resumeWithException(e)
                return
            }
            cont.resume(result)
        }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}
