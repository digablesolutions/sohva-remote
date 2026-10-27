package com.mothandgravel.sohva.protocol

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.math.BigInteger
import java.security.KeyFactory
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPublicKeySpec

class AtvPairingTest {
    private val tv = FakeAtvTv()
    private lateinit var pairing: AtvPairing

    @Before
    fun setUp() {
        tv.start()
        pairing = pairingFor(tv)
    }

    @After
    fun tearDown() = tv.stop()

    private fun pairingFor(tv: FakeAtvTv) = AtvPairing(
        socketFactory = AtvTls.socketFactory(arrayOf(tv.clientKeyManager)),
        clientKey = tv.clientCertificate.keyPair.public as RSAPublicKey,
        clientName = "Pixel (Bravia Remote)",
    )

    @Test
    fun `secret matches the one the real TV accepted`() {
        // Public values from pairing with the KD-65XF9005 on 2026-10-24: the TV showed FF5150 and
        // its SecretAck echoed this digest.
        val secret = AtvPairing.pairingSecret(rsaKey(REAL_CLIENT_MODULUS), rsaKey(REAL_SERVER_MODULUS), "FF5150")
        assertThat(secret?.toHex()).isEqualTo("ff7a1ef5bab504a10cd018779353fd54a18f6f45b8f628561c8f8a9e43b14b2d")
        assertThat(AtvPairing.pairingSecret(rsaKey(REAL_CLIENT_MODULUS), rsaKey(REAL_SERVER_MODULUS), " ff5150 "))
            .isEqualTo(secret)
    }

    @Test
    fun `a code whose check byte does not match gives no secret`() {
        val client = rsaKey(REAL_CLIENT_MODULUS)
        val server = rsaKey(REAL_SERVER_MODULUS)
        assertThat(AtvPairing.pairingSecret(client, server, "FE5150")).isNull()
        assertThat(AtvPairing.pairingSecret(client, server, "FF515")).isNull()
        assertThat(AtvPairing.pairingSecret(client, server, "FF51G0")).isNull()
    }

    @Test
    fun `pairing walks the steps and the TV accepts the shown code`() = runBlocking {
        val session = pairing.start("127.0.0.1", tv.port)
        assertThat(tv.received).containsExactly(10, 20, 30).inOrder()
        assertThat(tv.clientName).isEqualTo("Pixel (Bravia Remote)")
        assertThat(session.serverCertificate).isEqualTo(tv.certificate.certificate)

        session.confirm(tv.shownCode!!.lowercase())
        assertThat(tv.paired).isTrue()
    }

    @Test
    fun `a mistyped code is caught before the TV sees it and the session stays usable`() = runBlocking {
        val session = pairing.start("127.0.0.1", tv.port)
        val code = tv.shownCode!!
        val wrongCheck = "%02X".format((code.substring(0, 2).toInt(16) + 1) % 256) + code.substring(2)

        assertThrows(BraviaException.WrongPin::class.java) { runBlocking { session.confirm(wrongCheck) } }
        assertThat(tv.received).doesNotContain(40)

        session.confirm(code)
        assertThat(tv.paired).isTrue()
    }

    @Test
    fun `a code the TV rejects is WrongPin`() = runBlocking {
        val strictTv = FakeAtvTv(acceptSecret = false).apply { start() }
        try {
            val session = pairingFor(strictTv).start("127.0.0.1", strictTv.port)
            assertThrows(BraviaException.WrongPin::class.java) { runBlocking { session.confirm(strictTv.shownCode!!) } }
            assertThat(strictTv.paired).isFalse()
        } finally {
            strictTv.stop()
        }
    }

    @Test
    fun `nothing listening is Unreachable`() {
        assertThrows(BraviaException.Unreachable::class.java) {
            runBlocking { pairing.start("127.0.0.1", FakeAtvTv.closedPort()) }
        }
    }

    @Test
    fun `a TV that refuses our certificate is a handshake failure, not Unreachable`() {
        val noIdentity = AtvPairing(
            socketFactory = AtvTls.socketFactory(emptyArray()),
            clientKey = tv.clientCertificate.keyPair.public as RSAPublicKey,
            clientName = "Pixel (Bravia Remote)",
        )
        val error = assertThrows(BraviaException.Protocol::class.java) {
            runBlocking { noIdentity.start("127.0.0.1", tv.port) }
        }
        assertThat(error).hasMessageThat().startsWith("TLS handshake failed")
    }

    @Test
    fun `wire frames round-trip nested messages`() {
        val bytes = AtvWire.message {
            varint(1, 300)
            message(10) { string(1, "atvremote"); varint(2, 0) }
        }
        val out = java.io.ByteArrayOutputStream()
        AtvWire.writeFrame(out, bytes)
        val fields = AtvWire.parse(AtvWire.readFrame(out.toByteArray().inputStream()))
        with(AtvWire) {
            assertThat(fields.varint(1)).isEqualTo(300L)
            assertThat(fields.message(10)?.string(1)).isEqualTo("atvremote")
            assertThat(fields.message(10)?.varint(2)).isEqualTo(0L)
        }
    }

    private fun rsaKey(modulusHex: String): RSAPublicKey =
        KeyFactory.getInstance("RSA")
            .generatePublic(RSAPublicKeySpec(BigInteger(modulusHex, 16), BigInteger.valueOf(65537))) as RSAPublicKey

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    private companion object {
        const val REAL_CLIENT_MODULUS =
            "C2227DEB6D20094196707630B2E85418F9ACAB744FAD4A9086E26DC12B54DB76BC52E945A90EF16D11527193FA274B30" +
                "EF1779C72C9C0CFAA3263FEDBDC3ED0BF3B212DF331C5B5A482A298E1AA342613E433575FF6796322D3C8552D9C042A5" +
                "E5DC675A67ECF113299198B1325F9919B4979425B82550ED4D1B9D00F80E6FD61BF1AF17162F21167AEC59F1415FBB08" +
                "F3CB964122C987112E6B155FCE5E662015D61570898889130F3692740A5C31D3F1034BA1490C4F0070391DC45B028323" +
                "862BC5D6D75DC7446F0709F9598A64E84111634D07FB8F824A715D8A89085C714074192EC5949B18036F182CCF7501A1" +
                "2C1B818CF7CF90D5D46A0D2A808C3581"
        const val REAL_SERVER_MODULUS =
            "B0DD385455D9E515A51316E98FF7771D11CB316EE377A2493AB2C715BD9780F8F905FA02E617DF466C0D181749C91C9C" +
                "4DCF4E95F139E512EA1DA10A04386A1F4B6BAE8222DB316EBA98177FDB27853FC1A09877AFDCB4FFC574D37ADEFC2650" +
                "C6C999EEE0A6B60E72F0924115BC79277BAF1F6387200A9ABFC3E3C6C21D60AD05476C1F7038BF3E31CF75F55FF84272" +
                "8C3883624B1C1A3E5785DC9C9A4C52701E9A1650375613D7DC74DAD135552D3665CBD0B35A899E6610B4386B0AB08AFC" +
                "AD98FCC372F0BA7B0D8C91FC8092F7FA6B8AD5555E25C20D4386C838CFE346167762ED5AB5DDB4B38A0C6FA6FC820FA6" +
                "BCF374F27E377C80881797A0E4632CCD"
    }
}
