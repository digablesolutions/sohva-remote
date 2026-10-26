package fi.digable.braviaremote.data

import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import dagger.hilt.android.AndroidEntryPoint
import fi.digable.braviaremote.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * Mutes the TV while the phone rings or a call is on, and unmutes it when the call ends, only if
 * the app was the one that muted it. Phone calls only: apps such as WhatsApp do not report calls
 * through this broadcast. Runs with the app closed (the phone-state broadcast still reaches it).
 */
@AndroidEntryPoint
class CallMuteReceiver : InjectedReceiver() {
    @Inject lateinit var controller: TvController
    @Inject lateinit var store: TvStore
    @Inject lateinit var adBreak: AdBreak
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val callState = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        val pending = goAsync()
        scope.launch {
            try {
                withTimeoutOrNull(RECEIVER_BUDGET_MS) { handle(callState) }
                    ?: DebugLog.w { "Call mute: TV did not answer in time" }
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun handle(callState: String) {
        if (!store.muteOnCalls.first()) return
        when (callState) {
            // Android sends each state twice (with and without the number); the second finds the
            // TV already muted and changes nothing. A TV muted by its owner is not ours to unmute.
            TelephonyManager.EXTRA_STATE_RINGING, TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                // Muted by an ad break: the call takes that mute over, so the break's end doesn't
                // unmute mid-call and the call's end brings the sound back.
                if (adBreak.handOverToCall()) {
                    store.setMutedForCall(true)
                    DebugLog.d { "Call: took the ad break's mute over" }
                } else if (controller.setMutedNow(true) == true) {
                    store.setMutedForCall(true)
                    DebugLog.d { "Call: TV muted" }
                }
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                if (!store.mutedForCall.first()) return
                // Forgotten only once the TV answered; a TV that was off keeps it for the next call's end.
                if (controller.setMutedNow(false) != null) {
                    store.setMutedForCall(false)
                    DebugLog.d { "Call ended: TV unmuted" }
                }
            }
        }
    }

    private companion object {
        const val RECEIVER_BUDGET_MS = 8_500L
    }
}
