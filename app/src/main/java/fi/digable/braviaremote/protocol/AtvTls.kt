package fi.digable.braviaremote.protocol

import java.security.cert.X509Certificate
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/** TLS for the Android TV Remote ports: the phone's own certificate, and the TV's accepted as is. */
object AtvTls {

    /**
     * The TV's certificate is self-signed and has no host name, so no CA can vouch for it. Pairing
     * hashes the TV's key into the code the user types, and the key seen there is kept so the
     * remote channel can check it is talking to the same TV.
     */
    @Suppress("CustomX509TrustManager", "TrustAllX509TrustManager")
    private object AcceptTvCertificate : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    fun socketFactory(keyManagers: Array<KeyManager>): SSLSocketFactory =
        SSLContext.getInstance("TLS").apply { init(keyManagers, arrayOf(AcceptTvCertificate), null) }.socketFactory
}
