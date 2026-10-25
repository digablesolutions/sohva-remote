package fi.digable.braviaremote.data

import kotlinx.serialization.Serializable

/** A paired TV as persisted on the phone. Secrets live in [CredentialStore], not here. */
@Serializable
data class SavedTv(
    /** Stable app-side id (the UPnP UUID when known, else a random one). */
    val id: String,
    val name: String,
    val model: String,
    val host: String,
    val mac: String? = null,
    val uuid: String? = null,
)
