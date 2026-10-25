package fi.digable.braviaremote.protocol

import kotlinx.serialization.Serializable

/** A TV found on the network (SSDP) or entered by hand. Not yet paired. */
@Serializable
data class DiscoveredTv(
    val host: String,
    val name: String,
    val model: String,
    /** The UPnP UDN without the `uuid:` prefix, when known. */
    val uuid: String? = null,
)

enum class PowerStatus { ACTIVE, STANDBY, UNKNOWN }

@Serializable
data class SystemInfo(
    val name: String,
    val model: String,
    val product: String = "",
    val generation: String = "",
    val serial: String = "",
    /** Wired/Wi-Fi MAC in `AA-BB-CC-DD-EE-FF` form, as the TV reports it. */
    val macAddr: String = "",
)

data class VolumeInfo(
    val target: String,
    val volume: Int,
    val minVolume: Int,
    val maxVolume: Int,
    val mute: Boolean,
)

@Serializable
data class ExternalInput(
    /** e.g. `extInput:hdmi?port=1` */
    val uri: String,
    val title: String,
    val label: String = "",
    val icon: String = "",
    val connection: Boolean = false,
) {
    /** The user-facing name: the label the user set on the TV if any, else the title. */
    val displayName: String get() = label.ifBlank { title }
}

@Serializable
data class TvApp(
    val uri: String,
    val title: String,
    val icon: String = "",
) {
    /**
     * Whether this is the Android app [packageName]. The TV builds app URIs as
     * `com.sony.dtv.<package>.<activity>`; the dot after the package keeps YouTube
     * (`com.google.android.youtube.tv`) apart from YouTube Music (`…youtube.tvmusic`).
     */
    fun isPackage(packageName: String): Boolean = uri.startsWith("com.sony.dtv.$packageName.")
}

/** A broadcast channel of a tuner source; [uri] plays it with `setPlayContent`. */
data class TvChannel(
    val uri: String,
    val title: String,
    /** The number shown on the TV, e.g. "001". */
    val number: String,
)

data class PlayingContent(
    val uri: String,
    val title: String,
    val source: String,
)

/** Result of PIN pairing: the cookie the TV handed out and when it stops working. */
data class PairingResult(
    val cookie: String,
    val expiresAtEpochMs: Long?,
)
