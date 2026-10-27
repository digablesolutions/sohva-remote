package com.mothandgravel.sohva.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Auth cookies, per-TV IRCC code tables and the TVs' Android TV Remote certificates, encrypted at
 * rest with an Android Keystore key. Also owns the app's stable pairing client id.
 */
@Singleton
class CredentialStore @Inject constructor(@ApplicationContext context: Context) {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "bravia_credentials",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /**
     * Opens the store ahead of its first use. That takes ~140 ms of Keystore work, so the app calls
     * this off the main thread at launch instead of paying it inside the first connect.
     */
    fun open() {
        prefs.contains(KEY_CLIENT_ID)
    }

    data class Cookie(val value: String, val expiresAtEpochMs: Long?)

    /** The id the app registers with on every TV. Created once per install. */
    val clientId: String
        get() = prefs.getString(KEY_CLIENT_ID, null) ?: java.util.UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_CLIENT_ID, it).apply()
        }

    fun cookie(tvId: String): Cookie? {
        val value = prefs.getString("cookie.$tvId", null) ?: return null
        val expires = prefs.getLong("cookie_exp.$tvId", -1L).takeIf { it > 0 }
        return Cookie(value, expires)
    }

    fun saveCookie(tvId: String, cookie: Cookie) {
        prefs.edit()
            .putString("cookie.$tvId", cookie.value)
            .putLong("cookie_exp.$tvId", cookie.expiresAtEpochMs ?: -1L)
            .apply()
    }

    fun irccCodes(tvId: String): Map<String, String> =
        prefs.getString("ircc.$tvId", null)
            ?.let { runCatching { json.decodeFromString(mapSerializer, it) }.getOrNull() }
            ?: emptyMap()

    fun saveIrccCodes(tvId: String, codes: Map<String, String>) {
        prefs.edit().putString("ircc.$tvId", json.encodeToString(mapSerializer, codes)).apply()
    }

    /** The TV's certificate (DER) from Android TV Remote pairing; present means fast keys are paired. */
    fun atvCertificate(tvId: String): ByteArray? =
        prefs.getString("atv_cert.$tvId", null)?.let { Base64.decode(it, Base64.NO_WRAP) }

    fun saveAtvCertificate(tvId: String, der: ByteArray) {
        prefs.edit().putString("atv_cert.$tvId", Base64.encodeToString(der, Base64.NO_WRAP)).apply()
    }

    /**
     * Moves fast keys paired before the TV was saved (see [pendingAtvKey]) to the saved TV, so a TV
     * set up without its remote has fast keys from the start.
     */
    fun adoptPendingAtvCertificate(host: String, tvId: String) {
        val pending = pendingAtvKey(host)
        val der = atvCertificate(pending) ?: return
        saveAtvCertificate(tvId, der)
        prefs.edit().remove("atv_cert.$pending").apply()
    }

    fun clear(tvId: String) {
        prefs.edit()
            .remove("cookie.$tvId").remove("cookie_exp.$tvId").remove("ircc.$tvId").remove("atv_cert.$tvId")
            .apply()
    }

    companion object {
        /** Where fast keys paired with a TV not saved yet are kept, by the TV's address. */
        fun pendingAtvKey(host: String) = "pending:$host"

        private const val KEY_CLIENT_ID = "client_id"
        private val json = Json { ignoreUnknownKeys = true }
        private val mapSerializer = MapSerializer(String.serializer(), String.serializer())
    }
}
