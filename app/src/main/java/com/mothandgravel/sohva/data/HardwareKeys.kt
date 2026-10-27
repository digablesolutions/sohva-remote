package com.mothandgravel.sohva.data

import com.mothandgravel.sohva.protocol.RemoteKey
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Routes the phone's hardware volume buttons from the Activity to whoever is listening. */
@Singleton
class HardwareKeys @Inject constructor() {
    /** One hardware key event; [repeat] when Android generated it because the key is held. */
    data class Press(val key: RemoteKey, val repeat: Boolean)

    private val _presses = MutableSharedFlow<Press>(extraBufferCapacity = 8)
    val presses: SharedFlow<Press> = _presses.asSharedFlow()

    val hasListener: Boolean get() = _presses.subscriptionCount.value > 0

    /** Returns true when a listener is attached, so the Activity can consume the event. */
    fun offer(key: RemoteKey, repeat: Boolean): Boolean = hasListener && _presses.tryEmit(Press(key, repeat))
}
