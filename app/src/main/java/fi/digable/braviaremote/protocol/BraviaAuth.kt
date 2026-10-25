package fi.digable.braviaremote.protocol

/** How requests to the TV are authenticated. */
sealed interface BraviaAuth {
    data object None : BraviaAuth
    data class Cookie(val value: String) : BraviaAuth
    data class PreSharedKey(val key: String) : BraviaAuth
}
