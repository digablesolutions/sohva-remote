package fi.digable.braviaremote.protocol

import fi.digable.braviaremote.protocol.AtvWire.bytes
import fi.digable.braviaremote.protocol.AtvWire.message
import fi.digable.braviaremote.protocol.AtvWire.string
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import java.math.BigInteger
import java.net.ServerSocket
import java.security.MessageDigest
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * A TV's pairing service (port 6467) on a local TLS socket, behaving like the KD-65XF9005:
 * it requires the client's certificate, acks each step and checks the secret against the code
 * it "shows". The hash is computed here independently of [AtvPairing.pairingSecret].
 */
class FakeAtvTv(
    /** When false the TV answers every secret with status 402, as for a wrong code. */
    private val acceptSecret: Boolean = true,
) {
    val certificate: HeldCertificate = HeldCertificate.Builder().rsa2048().commonName("atvremote/fake").build()
    val clientCertificate: HeldCertificate = HeldCertificate.Builder().rsa2048().commonName("BraviaRemote test").build()

    /** The client's key manager, as the app would build it from its own certificate. */
    val clientKeyManager = HandshakeCertificates.Builder().heldCertificate(clientCertificate).build().keyManager

    /** Message field numbers received, in order (10 request, 20 options, 30 configuration, 40 secret). */
    val received = CopyOnWriteArrayList<Int>()

    @Volatile var clientName: String? = null
    @Volatile var shownCode: String? = null
    @Volatile var paired = false

    private lateinit var server: SSLServerSocket
    val port: Int get() = server.localPort

    fun start() {
        val tls = HandshakeCertificates.Builder()
            .heldCertificate(certificate)
            .addTrustedCertificate(clientCertificate.certificate)
            .build()
        server = (tls.sslContext().serverSocketFactory.createServerSocket(0) as SSLServerSocket).apply {
            needClientAuth = true
        }
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() as SSLSocket }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { socket.use(::serve) } }
            }
        }
    }

    fun stop() = server.close()

    private fun serve(socket: SSLSocket) {
        val clientKey = socket.session.peerCertificates.first().publicKey as RSAPublicKey
        while (true) {
            val request = AtvWire.parse(AtvWire.readFrame(socket.inputStream))
            val field = request.first { it.number >= 10 }.number
            received += field
            when (field) {
                10 -> {
                    clientName = request.message(10)?.string(2)
                    reply(socket, 11) {}
                }
                20 -> reply(socket, 20) {
                    message(2) { varint(1, 3); varint(2, 6) }
                    varint(3, 1)
                }
                30 -> {
                    val nonce = Random.nextBytes(2)
                    val check = hash(clientKey, nonce)[0]
                    shownCode = byteArrayOf(check, *nonce).joinToString("") { "%02X".format(it) }
                    reply(socket, 31) {}
                }
                40 -> {
                    val secret = request.message(40)?.bytes(1)
                    val code = shownCode!!.chunked(2).map { it.toInt(16).toByte() }
                    val expected = hash(clientKey, byteArrayOf(code[1], code[2]))
                    if (acceptSecret && secret.contentEquals(expected)) {
                        paired = true
                        reply(socket, 41) { bytes(1, expected) }
                    } else {
                        val bad = AtvWire.message { varint(1, 2); varint(2, 402) }
                        AtvWire.writeFrame(socket.outputStream, bad)
                    }
                }
            }
        }
    }

    private fun reply(socket: SSLSocket, field: Int, body: AtvWire.Writer.() -> Unit) {
        AtvWire.writeFrame(socket.outputStream, AtvWire.message {
            varint(1, 2)
            varint(2, 200)
            message(field, body)
        })
    }

    private fun hash(clientKey: RSAPublicKey, nonce: ByteArray): ByteArray {
        val serverKey = certificate.keyPair.public as RSAPublicKey
        fun BigInteger.unsigned() = toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
        return MessageDigest.getInstance("SHA-256").run {
            listOf(clientKey.modulus, clientKey.publicExponent, serverKey.modulus, serverKey.publicExponent)
                .forEach { update(it.unsigned()) }
            update(nonce)
            digest()
        }
    }

    companion object {
        /** A port with nothing listening, for the unreachable case. */
        fun closedPort(): Int = ServerSocket(0).use { it.localPort }
    }
}
