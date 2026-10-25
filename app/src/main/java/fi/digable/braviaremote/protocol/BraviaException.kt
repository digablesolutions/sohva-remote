package fi.digable.braviaremote.protocol

import java.io.IOException

/** Failures the UI distinguishes between. Everything the protocol layer throws is one of these. */
sealed class BraviaException(message: String, cause: Throwable? = null) : IOException(message, cause) {

    /** Connection refused or timed out: the TV is off, on another network, or the IP moved. */
    class Unreachable(host: String, cause: Throwable? = null) :
        BraviaException("Cannot reach $host", cause)

    /** HTTP 401/403 or JSON-RPC 403: the cookie or PSK is missing, expired or wrong. */
    class Unauthorized(detail: String = "Not authorized") : BraviaException(detail)

    /** The TV answered but refused the call. [code] is the Sony error code. */
    class ApiError(val code: Int, detail: String) : BraviaException("TV error $code: $detail") {
        /** Error 7 ("Illegal State") and 40005 ("Display Is Turned off") both mean the TV is in standby. */
        val isStandby: Boolean get() = code == 7 || code == 40005
    }

    /** Pairing with a PIN that the TV rejected. */
    class WrongPin : BraviaException("Wrong PIN")

    /** The TV answered something we could not parse. */
    class Protocol(detail: String, cause: Throwable? = null) : BraviaException(detail, cause)
}
