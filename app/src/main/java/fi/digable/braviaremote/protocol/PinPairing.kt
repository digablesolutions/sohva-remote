package fi.digable.braviaremote.protocol

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Credentials
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * PIN pairing via `accessControl.actRegister`.
 *
 * Flow: [requestPin] asks the TV to show a 4-digit code (the TV answers HTTP 401, which is the
 * expected "now enter the PIN" signal). [confirmPin] repeats the call with the PIN as HTTP Basic
 * auth; the TV answers 200 and a `Set-Cookie: auth=…` cookie that is valid for about two weeks.
 * [renew] repeats the call with the current cookie and gets a fresh one, so a regularly used TV
 * never needs re-pairing.
 */
class PinPairing(
    private val api: BraviaApi,
    private val clientId: String,
    private val nickname: String,
) {
    private fun registerParams() = buildJsonArray {
        add(buildJsonObject {
            put("clientid", clientId)
            put("nickname", nickname)
            put("level", "private")
        })
        add(buildJsonArray {
            add(buildJsonObject { put("value", "yes"); put("function", "WOL") })
        })
    }

    /** Asks the TV to show the PIN. Returns normally when the TV accepted the request. */
    suspend fun requestPin() {
        val request = api.rpcRequest("accessControl", "actRegister", registerParams()).build()
        val response = api.rawCall(request)
        when (response.code) {
            401 -> Unit // TV is now showing the PIN
            200 -> {
                // Some TVs with auth set to "None" accept straight away and hand out a cookie.
                runCatching { api.parseRpcResponse(response) }
            }
            else -> throw BraviaException.Protocol("actRegister HTTP ${response.code}")
        }
    }

    /** Sends the PIN the TV displayed and returns the auth cookie. */
    suspend fun confirmPin(pin: String): PairingResult {
        val request = api.rpcRequest("accessControl", "actRegister", registerParams())
            .header("Authorization", Credentials.basic("", pin.trim()))
            .build()
        val response = api.rawCall(request)
        return when (response.code) {
            401 -> throw BraviaException.WrongPin()
            200 -> {
                api.parseRpcResponse(response)
                extractCookie(response) ?: throw BraviaException.Protocol("TV did not return an auth cookie")
            }
            else -> throw BraviaException.Protocol("actRegister HTTP ${response.code}")
        }
    }

    /** Refreshes an existing cookie. Throws [BraviaException.Unauthorized] when it has expired. */
    suspend fun renew(cookie: String): PairingResult {
        val request = api.rpcRequest("accessControl", "actRegister", registerParams())
            .header("Cookie", "auth=$cookie")
            .build()
        val response = api.rawCall(request)
        return when (response.code) {
            401, 403 -> throw BraviaException.Unauthorized("Cookie expired")
            200 -> {
                api.parseRpcResponse(response)
                extractCookie(response) ?: PairingResult(cookie, null)
            }
            else -> throw BraviaException.Protocol("actRegister HTTP ${response.code}")
        }
    }

    companion object {
        private val cookieDateFormat
            get() = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("GMT")
            }

        /** Parses `Set-Cookie: auth=XYZ; Path=/sony/; Max-Age=1209600; Expires=…`. */
        internal fun extractCookie(response: HttpResult, nowMs: Long = System.currentTimeMillis()): PairingResult? {
            for (header in response.headers("Set-Cookie")) {
                val parsed = parseSetCookie(header, nowMs) ?: continue
                return parsed
            }
            return null
        }

        internal fun parseSetCookie(header: String, nowMs: Long): PairingResult? {
            val parts = header.split(';').map { it.trim() }
            val auth = parts.firstOrNull { it.startsWith("auth=") } ?: return null
            val value = auth.removePrefix("auth=")
            if (value.isBlank()) return null
            var expires: Long? = null
            for (p in parts.drop(1)) {
                val (k, v) = p.split('=', limit = 2).let { it[0].lowercase() to it.getOrElse(1) { "" } }
                when (k) {
                    "max-age" -> v.toLongOrNull()?.let { expires = nowMs + it * 1000 }
                    "expires" -> if (expires == null) {
                        expires = runCatching { cookieDateFormat.parse(v)?.time }.getOrNull()
                    }
                }
            }
            return PairingResult(value, expires)
        }
    }
}
