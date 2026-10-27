package com.mothandgravel.sohva.voice

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Debug builds only: text handed to the app as if spoken, so voice commands can be tried over adb
 * (`am start -n com.mothandgravel.sohva/.MainActivity --es voice "kanava 3"`).
 */
@Singleton
class VoiceInbox @Inject constructor() {
    private val heard = Channel<String>(Channel.CONFLATED)
    val texts: Flow<String> = heard.receiveAsFlow()

    fun offer(text: String) {
        heard.trySend(text)
    }
}
