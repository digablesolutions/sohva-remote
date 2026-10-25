package fi.digable.braviaremote.data

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.annotation.CallSuper
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import fi.digable.braviaremote.R
import fi.digable.braviaremote.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns the TV off after a chosen time, also with the app closed. An exact alarm wakes the phone a
 * minute early; [SleepTimerReceiver] starts [SleepFadeService], which lowers the volume over that
 * minute and then switches the TV off. The end time is kept in [TvStore] so the remote can show
 * it. A reboot drops the alarm; the next app start sets it again if the time is still ahead.
 */
@Singleton
class SleepTimer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: TvStore,
) {
    val endsAt: Flow<Long?> = store.sleepAt

    suspend fun start(minutes: Int) {
        val at = System.currentTimeMillis() + minutes * 60_000L
        store.setSleepAt(at)
        schedule(at)
        DebugLog.d { "Sleep timer: TV off in $minutes min" }
    }

    suspend fun cancel() {
        alarms().cancel(alarmIntent())
        // A fade already under way stops too, and puts the volume back.
        context.stopService(Intent(context, SleepFadeService::class.java))
        store.setSleepAt(null)
        DebugLog.d { "Sleep timer cancelled" }
    }

    /** Called at app start: sets the alarm again after a reboot, forgets a timer whose time has passed. */
    suspend fun restore() {
        val at = store.sleepAt.first() ?: return
        if (at > System.currentTimeMillis()) schedule(at) else store.setSleepAt(null)
    }

    private fun schedule(endsAt: Long) {
        val at = (endsAt - FADE_MS).coerceAtLeast(System.currentTimeMillis())
        val alarms = alarms()
        // USE_EXACT_ALARM is granted at install on Android 13+; older versions ask for SCHEDULE_EXACT_ALARM,
        // which the user can deny on 12. Without it the TV goes off a few minutes late rather than never.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent())
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent())
        }
    }

    private fun alarms() = context.getSystemService(AlarmManager::class.java)

    private fun alarmIntent(): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, SleepTimerReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Forgets the timer that ended at [endsAt]; a timer set since then is kept. */
    internal suspend fun clear(endsAt: Long?) {
        if (store.sleepAt.first() == endsAt) store.setSleepAt(null)
    }

    companion object {
        /** The volume fades out over the timer's last minute. */
        const val FADE_MS = 60_000L
    }
}

/**
 * Hilt injects a receiver in the onReceive it generates, which runs only through a super call; an
 * abstract onReceive cannot be called from Kotlin, so receivers extend this instead.
 */
abstract class InjectedReceiver : BroadcastReceiver() {
    @CallSuper
    override fun onReceive(context: Context, intent: Intent) = Unit
}

/** The sleep timer's alarm: switches the TV off (never on), within the few seconds a receiver gets. */
@AndroidEntryPoint
class SleepTimerReceiver : InjectedReceiver() {
    @Inject lateinit var controller: TvController
    @Inject lateinit var timer: SleepTimer
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        // The fade needs a minute, more than a receiver gets; an exact alarm may start a service.
        val started = runCatching {
            context.startForegroundService(Intent(context, SleepFadeService::class.java))
        }.onFailure { DebugLog.w(it) { "Sleep timer: fade service not started; turning off now" } }.isSuccess
        if (started) return
        // Without the service there is no fade: the TV goes off now, a minute early.
        val pending = goAsync()
        scope.launch {
            val endsAt = timer.endsAt.first()
            try {
                withTimeoutOrNull(RECEIVER_BUDGET_MS) {
                    val session = controller.awaitSession()
                    DebugLog.d { "Sleep timer fired; TV is ${session?.javaClass?.simpleName}" }
                    if (session is ConnectionState.Connected) controller.powerOff()
                } ?: DebugLog.w { "Sleep timer: TV did not answer in time" }
            } finally {
                timer.clear(endsAt)
                pending.finish()
            }
        }
    }

    private companion object {
        /** A receiver is killed after about 10 s. */
        const val RECEIVER_BUDGET_MS = 8_500L
    }
}

/**
 * The sleep timer's last minute: lowers the TV's volume step by step, puts it back while muted (so
 * the TV is not silent next time, and the jump is not heard), then switches the TV off. A short
 * foreground service, so the minute is not cut short; a wake lock keeps the phone's CPU awake.
 */
@AndroidEntryPoint
class SleepFadeService : Service() {
    @Inject lateinit var controller: TvController
    @Inject lateinit var timer: SleepTimer
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification())
        }
        if (job?.isActive == true) return START_NOT_STICKY
        job = scope.launch {
            val wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BraviaRemote:sleepFade")
            wakeLock.acquire(WAKE_LOCK_MS)
            val endsAt = timer.endsAt.first()
            try {
                DebugLog.d { "Sleep timer: fading out" }
                controller.fadeOutAndOff(endsAt ?: System.currentTimeMillis()) { timer.endsAt.first() == endsAt }
            } finally {
                withContext(NonCancellable) { timer.clear(endsAt) }
                if (wakeLock.isHeld) wakeLock.release()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    /** A short service gets about three minutes; the fade takes one. */
    override fun onTimeout(startId: Int) {
        job?.cancel()
        stopSelf()
    }

    override fun onDestroy() {
        job?.cancel()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.sleep_title), NotificationManager.IMPORTANCE_LOW),
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_power)
            .setContentTitle(getString(R.string.sleep_fading))
            .build()
    }

    private companion object {
        const val CHANNEL_ID = "sleep_timer"
        const val NOTIFICATION_ID = 1
        const val WAKE_LOCK_MS = 3 * 60_000L
    }
}
