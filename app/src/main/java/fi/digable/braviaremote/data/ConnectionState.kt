package fi.digable.braviaremote.data

import fi.digable.braviaremote.protocol.VolumeInfo

/** What the remote screen shows in its status bar. */
sealed interface ConnectionState {
    /** No TV paired yet. */
    data object NoTv : ConnectionState

    data class Connecting(val tv: SavedTv) : ConnectionState

    /** The TV answers and is on. */
    data class Connected(val tv: SavedTv, val volume: VolumeInfo? = null) : ConnectionState

    /** The TV answers but is in standby (screen off). IRCC still works for power on. */
    data class Standby(val tv: SavedTv) : ConnectionState

    /** No HTTP answer: off, asleep without remote start, other network, or IP changed. */
    data class Unreachable(val tv: SavedTv) : ConnectionState

    /** The cookie expired or was revoked on the TV; the user has to pair again. */
    data class Unauthorized(val tv: SavedTv) : ConnectionState

    val tvOrNull: SavedTv?
        get() = when (this) {
            is NoTv -> null
            is Connecting -> tv
            is Connected -> tv
            is Standby -> tv
            is Unreachable -> tv
            is Unauthorized -> tv
        }
}
