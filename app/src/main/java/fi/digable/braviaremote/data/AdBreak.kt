package fi.digable.braviaremote.data

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import fi.digable.braviaremote.MainActivity
import fi.digable.braviaremote.R
import fi.digable.braviaremote.di.ApplicationScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A running ad break on the TV [tvId]: until [endsAt] (epoch ms) the TV is muted, then the sound
 * comes back and the TV returns to [channelUri] ([channelTitle]) if it was switched to another
 * channel meanwhile. [mutedByUs]: the break muted the TV, so ending it unmutes; false when the TV
 * was muted already, the user unmuted it, the sound came on for another channel, or a phone call
 * took the mute over.
 */
@Serializable
data class AdBreakState(
    val tvId: String,
    val endsAt: Long,
    val channelUri: String,
    val channelTitle: String,
    val mutedByUs: Boolean,
    val startedAt: Long = 0,
    /** Ends that found the TV not answering; the end is tried again a few times. */
    val retries: Int = 0,
) {
    /** The channel to go back to when the TV now shows [nowUri]: only from another live-TV channel, not an app. */
    fun returnTo(nowUri: String?): String? =
        channelUri.takeIf { nowUri != null && nowUri.startsWith("tv:") && nowUri != channelUri }

    /** Unmute at the end only what the break muted, and never during a call the app muted the TV for. */
    fun shouldUnmute(mutedForCall: Boolean): Boolean = mutedByUs && !mutedForCall
}

/**
 * Ad break: one tap mutes the TV through a commercial break and brings the sound back after a few
 * minutes, also with the app closed. Watch another channel meanwhile and its sound comes on; the
 * end of the break goes back to the channel it started on. An exact alarm ends it (like the sleep
 * timer); a notification counts down, with "+1 min" and "Back now".
 *
 * The break never toggles: it unmutes only a mute it made and still holds. Unmuting by hand, or a
 * phone call muting the TV meanwhile ([handOverToCall]), takes that mute away from it.
 */
@Singleton
class AdBreak @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: TvStore,
    private val controller: TvController,
    @ApplicationScope private val scope: CoroutineScope,
) {
    val state: Flow<AdBreakState?> = store.adBreak

    /** Start, extend and end one at a time: an alarm and a tap can arrive together. */
    private val mutex = Mutex()

    init {
        scope.launch {
            controller.playing.collect { playing ->
                val uri = playing?.uri?.takeIf { it.startsWith("tv:") } ?: return@collect
                mutex.withLock {
                    val running = runningOnActiveTv() ?: return@withLock
                    if (uri == running.channelUri || !running.mutedByUs || store.mutedForCall.first()) return@withLock
                    // Switched to another channel to watch meanwhile: hear it. The break still comes back.
                    // The mute stays the break's until the TV has taken the unmute.
                    if (controller.setMutedNow(false) != null) {
                        DebugLog.d { "Ad break: watching $uri meanwhile, sound on" }
                        store.setAdBreak(running.copy(mutedByUs = false))
                    }
                }
            }
        }
        scope.launch {
            // Unmuted by hand during the break: a later mute is the user's, not the break's to undo.
            // Also after a restart, when the first reading is already unmuted. Right after the start
            // the TV can still report its old, unmuted state, so the first seconds don't count.
            controller.state.collect { state ->
                val muted = (state as? ConnectionState.Connected)?.volume?.mute
                if (muted != false) return@collect
                mutex.withLock {
                    val running = runningOnActiveTv()?.takeIf { it.mutedByUs } ?: return@withLock
                    val settled = System.currentTimeMillis() - running.startedAt > START_SETTLE_MS
                    if (settled) store.setAdBreak(running.copy(mutedByUs = false))
                }
            }
        }
    }

    /** Mutes the TV for [minutes] on the live-TV channel it shows. False when not on live TV or the TV is off. */
    suspend fun start(minutes: Int = DEFAULT_MINUTES): Boolean = withContext(NonCancellable) {
        // Not cut short by leaving the screen: a TV muted here must also get its alarm.
        mutex.withLock {
            // A second tap before the chip changed: the break is on already, and its mute stays its own.
            if (store.adBreak.first() != null) return@withLock true
            val tvId = controller.activeTv.value?.id ?: return@withLock false
            val playing = controller.playing.value
            val channel = playing?.uri?.takeIf { it.startsWith("tv:") } ?: return@withLock false
            val muted = controller.setMutedNow(true) ?: return@withLock false
            val now = System.currentTimeMillis()
            val running = AdBreakState(tvId, now + minutes * 60_000L, channel, playing.title, muted, startedAt = now)
            store.setAdBreak(running)
            schedule(running)
            DebugLog.d { "Ad break: $minutes min on ${playing.title} (muted by us: $muted)" }
            true
        }
    }

    /** One more minute. */
    suspend fun extend() = mutex.withLock {
        val running = store.adBreak.first() ?: return@withLock
        val longer = running.copy(endsAt = maxOf(running.endsAt, System.currentTimeMillis()) + EXTEND_MS)
        store.setAdBreak(longer)
        schedule(longer)
    }

    /**
     * Ends the break: the sound on, and back to its channel if the TV shows another one. With
     * [onlyIfDue] (the alarm) a break that was extended in the meantime goes on.
     */
    suspend fun end(onlyIfDue: Boolean = false) = mutex.withLock {
        val running = store.adBreak.first() ?: return@withLock
        if (onlyIfDue && running.endsAt - System.currentTimeMillis() > DUE_SLACK_MS) {
            schedule(running)
            return@withLock
        }
        endLocked(running)
    }

    /**
     * A phone call is muting the TV while the break holds the mute: the call takes it over, so the
     * break's end does not unmute during the call and the call's end brings the sound back. True
     * when the break held it.
     */
    suspend fun handOverToCall(): Boolean = mutex.withLock {
        // Called before the call mutes the TV itself, so an end running now finishes first.
        val running = runningOnActiveTv()?.takeIf { it.mutedByUs } ?: return false
        store.setAdBreak(running.copy(mutedByUs = false))
        true
    }

    /**
     * At app start and after a reboot: sets the alarm again. A break that ended while the phone was
     * off is ended now if that was only minutes ago; much later the TV is left as it is.
     */
    suspend fun restore() = mutex.withLock {
        val running = store.adBreak.first() ?: return@withLock
        val late = System.currentTimeMillis() - running.endsAt
        when {
            late < 0 -> schedule(running)
            late < STALE_MS -> endLocked(running)
            else -> clear()
        }
    }

    private suspend fun endLocked(running: AdBreakState) {
        if (store.activeTvId.first() != running.tvId) {
            // Another TV is in use now; this one is not ours to change.
            clear()
            return
        }
        try {
            // The sound first: it matters most if the TV is slow to answer.
            if (running.shouldUnmute(store.mutedForCall.first()) && controller.setMutedNow(false) == null) {
                // Off: nothing to bring back. On but not answering: try again shortly, a few times.
                if (controller.state.value !is ConnectionState.Standby && running.retries < MAX_RETRIES) {
                    retryLater(running)
                    return
                }
            }
            controller.playingNow()?.uri?.let(running::returnTo)?.let { uri ->
                DebugLog.d { "Ad break over: back to ${running.channelTitle}" }
                controller.playNow(uri)
            }
            clear()
        } catch (e: CancellationException) {
            // Out of time (a receiver gets seconds): keep the break and try again shortly.
            withContext(NonCancellable) { retryLater(running) }
            throw e
        }
    }

    private suspend fun retryLater(running: AdBreakState) {
        val retry = running.copy(endsAt = System.currentTimeMillis() + RETRY_MS, retries = running.retries + 1)
        store.setAdBreak(retry)
        schedule(retry)
    }

    /**
     * The alarm's receiver ran out of time before the end got its turn: wake again shortly. The
     * break's end time has passed, so the next alarm ends it.
     */
    fun alarmAgainSoon() {
        setAlarm(System.currentTimeMillis() + RETRY_MS)
    }

    private suspend fun runningOnActiveTv(): AdBreakState? =
        store.adBreak.first()?.takeIf { it.tvId == store.activeTvId.first() }

    private suspend fun clear() {
        store.setAdBreak(null)
        alarms().cancel(intent(ACTION_END))
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    private fun schedule(running: AdBreakState) {
        setAlarm(running.endsAt)
        notify(running)
    }

    private fun setAlarm(at: Long) {
        val alarms = alarms()
        // As the sleep timer: without the exact-alarm permission the break ends late rather than never.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent(ACTION_END))
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent(ACTION_END))
        }
    }

    /** A countdown with "+1 min" and "Back now"; without the notification permission it is just not shown. */
    private fun notify(running: AdBreakState) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            // Default importance, so the countdown sits with the other notifications rather than
            // folded away under "silent"; the channel itself makes no sound and doesn't vibrate.
            NotificationChannel(CHANNEL_ID, context.getString(R.string.ad_break), NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_volume_off)
            .setContentTitle(context.getString(R.string.ad_break))
            .setContentText(context.getString(R.string.ad_break_back_to, running.channelTitle))
            .setWhen(running.endsAt)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setTimeoutAfter(running.endsAt - System.currentTimeMillis() + STALE_MS)
            .setContentIntent(
                PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE),
            )
            .addAction(Notification.Action.Builder(null, context.getString(R.string.ad_break_more), intent(ACTION_EXTEND)).build())
            .addAction(Notification.Action.Builder(null, context.getString(R.string.ad_break_end), intent(ACTION_BACK_NOW)).build())
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
            .onFailure { DebugLog.w(it) { "Ad break notification not shown" } }
    }

    private fun alarms() = context.getSystemService(AlarmManager::class.java)

    private fun intent(action: String): PendingIntent = PendingIntent.getBroadcast(
        context, action.hashCode(), Intent(context, AdBreakReceiver::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        /** Commercial breaks on Finnish channels run about three minutes; "+1 min" covers a long one. */
        const val DEFAULT_MINUTES = 3
        const val EXTEND_MS = 60_000L

        /** An alarm this close to the end counts as the end. */
        private const val DUE_SLACK_MS = 2_000L

        /** A break that ended this long ago (phone off, process gone) is not acted on any more. */
        private const val STALE_MS = 10 * 60_000L

        /** An end cut short (the TV slow to answer) is tried again after this. */
        private const val RETRY_MS = 30_000L
        private const val MAX_RETRIES = 10

        /** The TV's mute state needs this long after a start to be read back reliably. */
        private const val START_SETTLE_MS = 5_000L

        const val ACTION_END = "fi.digable.braviaremote.AD_BREAK_END"
        const val ACTION_BACK_NOW = "fi.digable.braviaremote.AD_BREAK_BACK_NOW"
        const val ACTION_EXTEND = "fi.digable.braviaremote.AD_BREAK_EXTEND"
        /** Named apart from the first build's channel ("ad_break"), whose importance can't be raised. */
        private const val CHANNEL_ID = "ad_break_countdown"
        private const val NOTIFICATION_ID = 4
    }
}

/** The ad break's alarm and its notification buttons, within the few seconds a receiver gets. */
@AndroidEntryPoint
class AdBreakReceiver : InjectedReceiver() {
    @Inject lateinit var adBreak: AdBreak
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val pending = goAsync()
        scope.launch {
            try {
                withTimeoutOrNull(RECEIVER_BUDGET_MS) {
                    when (intent.action) {
                        AdBreak.ACTION_END -> adBreak.end(onlyIfDue = true)
                        AdBreak.ACTION_BACK_NOW -> adBreak.end()
                        AdBreak.ACTION_EXTEND -> adBreak.extend()
                    }
                } ?: run {
                    DebugLog.w { "Ad break: TV did not answer in time" }
                    if (intent.action == AdBreak.ACTION_END) adBreak.alarmAgainSoon()
                }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        /** A receiver is killed after about 10 s. */
        const val RECEIVER_BUDGET_MS = 8_500L
    }
}
