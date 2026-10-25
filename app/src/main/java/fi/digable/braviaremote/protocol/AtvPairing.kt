package fi.digable.braviaremote.protocol

import fi.digable.braviaremote.protocol.AtvWire.bytes
import fi.digable.braviaremote.protocol.AtvWire.varint
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import java.math.BigInteger
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Pairing with the TV's Android TV Remote Service ("polo" protocol on port 6467), which the
 * fast key channel on port 6466 needs.
 *
 * Both sides identify themselves by TLS certificate. [start] connects, agrees on a 6-character
 * hex code and makes the TV show it; [Session.confirm] answers with a SHA-256 hash of both public
 * keys and the code. The TV then remembers the phone's certificate.
 *
 * [socketFactory] must present the phone's client certificate (whose key is [clientKey]) and
 * accept the TV's self-signed one; the TV's certificate is returned so it can be checked later.
 */
class AtvPairing(
    private val socketFactory: SSLSocketFactory,
    private val clientKey: RSAPublicKey,
    private val clientName: String,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val timeoutMs: Int = 10_000,
) {
    /** Connects and makes the TV show its pairing code. Close the session when it is not confirmed. */
    suspend fun start(host: String, port: Int = PORT): Session = withContext(io) {
        val socket = socketFactory.createSocket() as SSLSocket
        // What the TV negotiates anyway. TLS 1.2 also signs with PKCS#1, which every Keystore RSA key
        // supports, and reports a refused client certificate during the handshake, not on first read.
        socket.enabledProtocols = arrayOf("TLSv1.2")
        try {
            socket.soTimeout = timeoutMs
            socket.connect(InetSocketAddress(host, port), timeoutMs)
        } catch (e: IOException) {
            socket.closeQuietly()
            throw BraviaException.Unreachable(host, e)
        }
        try {
            // A refused client certificate fails here: that is not "TV unreachable".
            try {
                socket.startHandshake()
            } catch (e: IOException) {
                throw BraviaException.Protocol("TLS handshake failed: ${e.message}", e)
            }
            val serverCert = socket.session.peerCertificates.firstOrNull() as? X509Certificate
                ?: throw BraviaException.Protocol("TV sent no certificate")
            val session = Session(socket, serverCert)
            session.exchange(PAIRING_REQUEST, PAIRING_REQUEST_ACK) {
                string(1, SERVICE_NAME)
                string(2, clientName)
            }
            session.exchange(OPTIONS, OPTIONS) {
                message(1) { varint(1, ENCODING_HEXADECIMAL); varint(2, CODE_LENGTH) }
                varint(3, ROLE_INPUT)
            }
            session.exchange(CONFIGURATION, CONFIGURATION_ACK) {
                message(1) { varint(1, ENCODING_HEXADECIMAL); varint(2, CODE_LENGTH) }
                varint(2, ROLE_INPUT)
            }
            session
        } catch (e: Exception) {
            socket.closeQuietly()
            throw e.asBraviaException()
        }
    }

    /** An open pairing connection while the TV is showing its code. */
    inner class Session internal constructor(
        private val socket: SSLSocket,
        /** The TV's certificate, to keep for checking the TV on the remote channel. */
        val serverCertificate: X509Certificate,
    ) : Closeable {

        /**
         * Sends the code shown on the TV. Throws [BraviaException.WrongPin] for a mistyped code:
         * when the first two characters do not match the hash this session is still usable and
         * nothing reached the TV; when the TV itself rejects the code the session is closed.
         */
        suspend fun confirm(code: String) = withContext(io) {
            val serverKey = serverCertificate.publicKey as? RSAPublicKey
                ?: throw BraviaException.Protocol("TV certificate is not RSA")
            val secret = pairingSecret(clientKey, serverKey, code) ?: throw BraviaException.WrongPin()
            try {
                exchange(SECRET, SECRET_ACK) { bytes(1, secret) }
            } catch (e: Exception) {
                socket.closeQuietly()
                throw if (e is BraviaException.ApiError) BraviaException.WrongPin() else e.asBraviaException()
            }
            socket.closeQuietly()
        }

        /** False once the code was accepted or the TV refused it. */
        val isOpen: Boolean get() = !socket.isClosed

        override fun close() {
            socket.closeQuietly()
        }

        internal fun exchange(field: Int, expected: Int, body: AtvWire.Writer.() -> Unit) {
            val request = AtvWire.message {
                varint(1, PROTOCOL_VERSION)
                varint(2, STATUS_OK)
                message(field, body)
            }
            AtvWire.writeFrame(socket.outputStream, request)
            val reply = AtvWire.parse(AtvWire.readFrame(socket.inputStream))
            val status = reply.varint(2)?.toInt()
            if (status != STATUS_OK) throw BraviaException.ApiError(status ?: -1, "Pairing step $field refused")
            if (reply.bytes(expected) == null) throw BraviaException.Protocol("Expected pairing message $expected")
        }
    }

    companion object {
        const val PORT = 6467
        const val CODE_LENGTH = 6

        private const val SERVICE_NAME = "atvremote"
        private const val PROTOCOL_VERSION = 2
        private const val STATUS_OK = 200
        private const val ENCODING_HEXADECIMAL = 3
        private const val ROLE_INPUT = 1

        private const val PAIRING_REQUEST = 10
        private const val PAIRING_REQUEST_ACK = 11
        private const val OPTIONS = 20
        private const val CONFIGURATION = 30
        private const val CONFIGURATION_ACK = 31
        private const val SECRET = 40
        private const val SECRET_ACK = 41

        /**
         * SHA-256 over the client's modulus and exponent, the server's modulus and exponent (each
         * as unsigned big-endian bytes) and the last four code characters as two bytes. The first
         * two code characters are the hash's first byte, so a mistyped code is caught here and
         * null is returned.
         */
        fun pairingSecret(clientKey: RSAPublicKey, serverKey: RSAPublicKey, code: String): ByteArray? {
            val normalized = code.trim().uppercase()
            if (normalized.length != CODE_LENGTH || normalized.any { Character.digit(it, 16) < 0 }) return null
            val codeBytes = normalized.chunked(2).map { it.toInt(16).toByte() }
            val digest = MessageDigest.getInstance("SHA-256").run {
                update(clientKey.modulus.unsignedBytes())
                update(clientKey.publicExponent.unsignedBytes())
                update(serverKey.modulus.unsignedBytes())
                update(serverKey.publicExponent.unsignedBytes())
                update(byteArrayOf(codeBytes[1], codeBytes[2]))
                digest()
            }
            return digest.takeIf { it[0] == codeBytes[0] }
        }

        /** [BigInteger.toByteArray] adds a 0x00 sign byte when the top bit is set; the hash has none. */
        private fun BigInteger.unsignedBytes(): ByteArray {
            val bytes = toByteArray()
            return if (bytes.size > 1 && bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
        }

        private fun SSLSocket.closeQuietly() = runCatching { close() }

        private fun Exception.asBraviaException(): BraviaException = when (this) {
            is BraviaException -> this
            else -> BraviaException.Protocol(message ?: javaClass.simpleName, this)
        }
    }
}
