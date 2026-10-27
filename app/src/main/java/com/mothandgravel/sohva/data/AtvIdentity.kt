package com.mothandgravel.sohva.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.mothandgravel.sohva.protocol.AtvTls
import java.math.BigInteger
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.Calendar
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509ExtendedKeyManager
import javax.security.auth.x500.X500Principal

/**
 * The phone's identity towards the TV's Android TV Remote Service: an RSA key that never leaves
 * the Android Keystore, with the self-signed certificate the Keystore issues for it. One identity
 * per install serves every TV; the TV remembers it at pairing.
 */
@Singleton
class AtvIdentity @Inject constructor() {

    private val keyStore: KeyStore by lazy { KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) } }

    /** The certificate the TV sees, created on first use (key generation takes a moment: call off the main thread). */
    val certificate: X509Certificate
        @Synchronized get() {
            if (!keyStore.containsAlias(ALIAS)) generate()
            return keyStore.getCertificate(ALIAS) as X509Certificate
        }

    val publicKey: RSAPublicKey get() = certificate.publicKey as RSAPublicKey

    /**
     * Gives this install a certificate of its own before it pairs (call off the main thread). Up to
     * 1.5.1 every install made one with the same subject and serial number, and the TV tells paired
     * clients apart by those: pairing a second phone or tablet replaced the first one's pairing,
     * which the TV then logged as an "unknown device". A new key also ends this install's pairings
     * with other TVs, but those were made with the shared certificate too.
     */
    @Synchronized
    fun ensureOwnCertificate() {
        val current = keyStore.getCertificate(ALIAS) as? X509Certificate ?: return
        if (current.serialNumber == BigInteger.ONE && current.subjectX500Principal == SHARED_SUBJECT) {
            DebugLog.d { "Replacing the shared Android TV Remote client key" }
            keyStore.deleteEntry(ALIAS)
        }
    }

    fun socketFactory(): SSLSocketFactory {
        val chain = arrayOf(certificate)
        val key = keyStore.getKey(ALIAS, null) as PrivateKey
        return AtvTls.socketFactory(arrayOf(SingleKeyManager(key, chain)))
    }

    private fun generate() {
        DebugLog.d { "Creating the Android TV Remote client key" }
        val notAfter = Calendar.getInstance().apply { add(Calendar.YEAR, 30) }.time
        runCatching { keyStore.deleteEntry(OLD_ALIAS) }
        // Conscrypt signs the TLS handshake as a raw RSA operation on a digest it padded itself, so the
        // key must allow no digest and no padding (ENCRYPTION_PADDING_NONE), not just PKCS#1.
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(2048)
            .setDigests(
                KeyProperties.DIGEST_NONE,
                KeyProperties.DIGEST_SHA256,
                KeyProperties.DIGEST_SHA384,
                KeyProperties.DIGEST_SHA512,
            )
            .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setCertificateSubject(X500Principal("CN=SohvaRemote-${UUID.randomUUID()}"))
            .setCertificateSerialNumber(BigInteger(63, SecureRandom()).add(BigInteger.ONE))
            .setCertificateNotAfter(notAfter)
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEYSTORE).apply {
            initialize(spec)
            generateKeyPair()
        }
    }

    /**
     * Always offers the one Keystore key. A KeyManagerFactory over the AndroidKeyStore is not
     * reliable across Android versions; this is all TLS needs from a client key manager.
     */
    private class SingleKeyManager(
        private val key: PrivateKey,
        private val chain: Array<X509Certificate>,
    ) : X509ExtendedKeyManager() {
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
        override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = ALIAS
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
        override fun getCertificateChain(alias: String?) = chain
        override fun getPrivateKey(alias: String?) = key
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "atv_remote_client2"

        /** Created without raw RSA, which the TLS handshake needs; never paired with a TV. */
        const val OLD_ALIAS = "atv_remote_client"

        /** What every install's certificate said up to 1.5.1, with serial number 1. */
        val SHARED_SUBJECT = X500Principal("CN=SohvaRemote")
    }
}
